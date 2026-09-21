import { useCallback, useEffect, useRef, useState } from 'react';
import jsQR from 'jsqr';
import {
  ApiError, gateFaceChallenge, gateFaceScan, gateInfo, gateRenew, gateScan, gateSync,
} from '../ticket/api';
import { captureFrames } from '../ticket/camera';
import GateVerdict, { type Verdict } from './GateVerdict';
import type { SealTone } from './SealMark';
import { clockText } from '../motion';

interface GateInfo {
  gateId: string;
  label: string | null;
  zone: string | null;
  direction: 'IN' | 'OUT' | 'BIDIRECTIONAL';
  sessionName: string;
  /** Null for a terminal registered before tokens had an end date. */
  tokenExpiresAt: string | null;
}

/** Days of validity left, or null when this terminal has no end date. */
function daysLeft(gate: GateInfo | null): number | null {
  if (!gate?.tokenExpiresAt) return null;
  return Math.ceil((new Date(gate.tokenExpiresAt).getTime() - Date.now()) / 86400000);
}

/** Close enough to the end that the terminal renews itself rather than waiting. */
const RENEW_WITHIN_DAYS = 5;

/** How long the verdict holds the screen. A refusal stays longer: it has to be read. */
function hold(tone: SealTone): number {
  return tone === 'admit' || tone === 'exit' ? 2400 : 4200;
}

const HEADLINES: Record<string, { tone: SealTone; headline: string }> = {
  ADMITTED: { tone: 'admit', headline: '입장' },
  EXITED: { tone: 'exit', headline: '퇴장' },
  DUPLICATE: { tone: 'repeat', headline: '중복 스캔' },
  DENIED: { tone: 'deny', headline: '거부' },
};

const STORAGE = 'plink.gate.credentials';
const CAMERA = 'plink.gate.camera';
const FACE_MODE = 'plink.gate.face';
const QUEUE = 'plink.gate.queue';

interface Queued { code: string; method: string; capturedAt: string }

function readQueue(): Queued[] {
  try { return JSON.parse(localStorage.getItem(QUEUE) || '[]') as Queued[]; }
  catch { return []; }
}

function writeQueue(events: Queued[]) {
  localStorage.setItem(QUEUE, JSON.stringify(events));
}

/**
 * Gate terminal. One tablet camera handles both jobs: every frame is offered to the QR
 * decoder, and the same stream will feed face matching. Direction is not asked for here
 * — it comes from the terminal's own registration, which is why a scanned code cannot
 * claim to be an exit.
 */
export default function GateScanner() {
  const [gateId, setGateId] = useState('');
  const [gateToken, setGateToken] = useState('');
  const [gate, setGate] = useState<GateInfo | null>(null);
  const [error, setError] = useState('');
  const faceFailures = useRef(0);
  // Which way the tablet's camera faces. A terminal on a stand usually wants the rear
  // lens; one on a desk wants the front, so the choice is remembered per device.
  const [facing, setFacing] = useState<'user' | 'environment'>(
    () => (localStorage.getItem(CAMERA) === 'environment' ? 'environment' : 'user'));

  const [outcome, setOutcome] = useState<Verdict | null>(null);
  // The standing line keeps the last result; the flash owns the screen for a moment and
  // then gets out of the way, so the camera is never buried under an old verdict.
  const [flash, setFlash] = useState<Verdict | null>(null);
  // A terminal is opened to be used: the camera starts reading as soon as the gate is
  // known, and both ways in are live. Either toggle can still be turned off by staff.
  const [scanning, setScanning] = useState(true);
  const [faceMode, setFaceMode] = useState(
    () => localStorage.getItem(FACE_MODE) !== 'off');
  const [queued, setQueued] = useState(readQueue().length);
  // What this terminal has handled since it was opened. Staff read it as proof the
  // lane is moving, and it costs nothing to keep.
  const [handled, setHandled] = useState(0);
  const video = useRef<HTMLVideoElement>(null);
  const frame = useRef<HTMLCanvasElement>(null);
  const lastCode = useRef<{ code: string; at: number }>({ code: '', at: 0 });
  const inFlight = useRef(false);

  useEffect(() => {
    const saved = localStorage.getItem(STORAGE);
    if (!saved) return;
    try {
      const parsed = JSON.parse(saved) as { gateId: string; gateToken: string };
      setGateId(parsed.gateId);
      setGateToken(parsed.gateToken);
    } catch { localStorage.removeItem(STORAGE); }
  }, []);

  // Stored credentials come from the setup link, so connecting is automatic.
  useEffect(() => {
    if (!gateId || !gateToken || gate) return;
    gateInfo(gateId, gateToken)
      .then(info => setGate(info as GateInfo))
      .catch(err => setError(err instanceof Error ? err.message : '단말을 연결하지 못했어요.'));
  }, [gateId, gateToken, gate]);

  /** Moves this terminal's end date out. The token and the gate binding are untouched. */
  const renew = useCallback(async (quiet: boolean) => {
    try {
      const result = await gateRenew(gateId.trim(), gateToken.trim());
      setGate(current => (current ? { ...current, tokenExpiresAt: result.tokenExpiresAt } : current));
      if (!quiet) setError('');
    } catch (err) {
      // A terminal that cannot renew is still a terminal that scans, right up to the
      // end date. Saying so once is enough; the header carries the countdown.
      if (!quiet) setError(err instanceof Error ? err.message : '유효기간을 갱신하지 못했어요.');
    }
  }, [gateId, gateToken]);

  // A tablet left on a stand for a season should not stop working because nobody in the
  // office noticed a date. While it is still valid and still holds the gate, it renews
  // itself; once it has lapsed only the console can, which is the point of the date.
  const renewedAt = useRef(0);
  useEffect(() => {
    const left = daysLeft(gate);
    if (gate == null || left == null || left > RENEW_WITHIN_DAYS || left <= 0) return;
    // Renewing moves the date, which lands back here. Once in a while is the whole
    // need, and it stops a window shorter than the band from renewing in a loop.
    if (Date.now() - renewedAt.current < 12 * 3600_000) return;
    renewedAt.current = Date.now();
    void renew(true);
  }, [gate, renew]);

  // Every lane ends here, so the screen reacts the same way whether the visitor held up
  // a phone or simply walked past the camera.
  const announce = useCallback((verdict: Omit<Verdict, 'at'>) => {
    const settled = { ...verdict, at: Date.now() };
    setOutcome(settled);
    setFlash(settled);
    setHandled(count => count + 1);
  }, []);

  useEffect(() => {
    if (!flash) return;
    const timer = window.setTimeout(() => setFlash(null), hold(flash.tone));
    return () => window.clearTimeout(timer);
  }, [flash]);

  const ledgerLine = useCallback((kind: string, entryCount?: number) => {
    const where = gate?.label || gate?.gateId || '게이트';
    const round = typeof entryCount === 'number' && entryCount > 0 ? ` · 입장 ${entryCount}회차` : '';
    return `${kind} ${clockText()} · ${where}${round}`;
  }, [gate]);

  const submit = useCallback(async (code: string) => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const result = await gateScan(gateId.trim(), gateToken.trim(), code);
      const seat = result.seat ? ` · ${result.seat}` : '';
      const verdict = HEADLINES[result.outcome as string] ?? HEADLINES.ADMITTED;
      announce({
        ...verdict,
        detail: `${result.ticketRef}${seat}${result.message ? ` · ${result.message}` : ''}`,
        ledger: ledgerLine(verdict.tone === 'deny' ? '거부됨' : '기록됨', result.entryCount),
      });
    } catch (err) {
      // A transport failure is not a refusal: the visitor is in front of us and the
      // read was real, so it is queued and replayed when the network returns.
      if (err instanceof TypeError) {
        const events = [...readQueue(), { code, method: 'QR', capturedAt: new Date().toISOString() }];
        writeQueue(events);
        setQueued(events.length);
        announce({
          tone: 'repeat', headline: '오프라인 기록',
          detail: '연결이 돌아오면 서버와 맞춥니다.',
          ledger: ledgerLine('보류됨'),
        });
      } else {
        announce({
          tone: 'deny', headline: '거부',
          detail: err instanceof Error ? err.message : '확인할 수 없는 코드예요.',
          ledger: ledgerLine('거부됨'),
        });
      }
    } finally {
      // Hold briefly so the operator sees the result before the next read.
      window.setTimeout(() => { inFlight.current = false; }, 1200);
    }
  }, [gateId, gateToken, announce, ledgerLine]);

  // Face runs on the same stream as the QR decoder: the operator never switches modes,
  // and a visitor either holds up a phone or simply walks up.
  useEffect(() => {
    if (!gate || !scanning || !faceMode) return;
    let stopped = false;
    const timer = window.setInterval(async () => {
      if (stopped || inFlight.current || !video.current || video.current.videoWidth === 0) return;
      inFlight.current = true;
      try {
        const { challenge } = await gateFaceChallenge(gateId.trim(), gateToken.trim());
        const frames = await captureFrames(video.current, 2, 120);
        const result = await gateFaceScan(gateId.trim(), gateToken.trim(), frames, challenge);
        const seat = result.seat ? ` · ${result.seat}` : '';
        const verdict = HEADLINES[result.outcome as string] ?? HEADLINES.ADMITTED;
        announce({
          ...verdict,
          detail: `얼굴 확인 · ${result.ticketRef}${seat}`,
          ledger: ledgerLine('기록됨', result.entryCount),
        });
        faceFailures.current = 0;
      } catch (err) {
        const status = err instanceof ApiError ? err.status : 0;
        const message = err instanceof Error ? err.message : '';
        // An empty lane, or somebody this event has never seen: the camera looks at
        // that all day and it is not a verdict about anybody. The screen stays as it is.
        if (status === 404) { faceFailures.current = 0; return; }
        if (status === 403) {
          // A face that was seen and refused - a photograph held up, or two holders too
          // alike to tell apart. That one the operator has to see.
          faceFailures.current = 0;
          announce({ tone: 'deny', headline: '거부', detail: message, ledger: ledgerLine('거부됨') });
          return;
        }
        // Anything else is the service rather than the visitor. A face service that is
        // down would otherwise bury the QR lane in red, so it is counted quietly and
        // called out once.
        faceFailures.current += 1;
        if (faceFailures.current >= 3) {
          setFaceMode(false);
          localStorage.setItem(FACE_MODE, 'off');
          setError('얼굴 인식을 사용할 수 없어 껐어요. QR은 그대로 동작합니다.');
        }
      } finally {
        window.setTimeout(() => { inFlight.current = false; }, 1200);
      }
    }, 2000);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [gate, scanning, faceMode, gateId, gateToken, announce, ledgerLine]);

  useEffect(() => {
    if (!gate || !scanning) return;
    let stream: MediaStream | null = null;
    let raf = 0;
    let stopped = false;

    async function start() {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: facing, width: { ideal: 1920 }, height: { ideal: 1080 } },
          audio: false,
        });
        if (stopped || !video.current) return;
        video.current.srcObject = stream;
        await video.current.play();
        loop();
      } catch {
        setError('카메라를 열 수 없어요. 권한을 허용하고 HTTPS로 접속했는지 확인해 주세요.');
        setScanning(false);
      }
    }

    function loop() {
      if (stopped) return;
      const source = video.current;
      const target = frame.current;
      if (source && target && source.videoWidth > 0) {
        const context = target.getContext('2d', { willReadFrequently: true });
        if (context) {
          target.width = source.videoWidth;
          target.height = source.videoHeight;
          context.drawImage(source, 0, 0, target.width, target.height);
          const pixels = context.getImageData(0, 0, target.width, target.height);
          const found = jsQR(pixels.data, pixels.width, pixels.height, { inversionAttempts: 'dontInvert' });
          if (found?.data) {
            const now = Date.now();
            const repeat = found.data === lastCode.current.code && now - lastCode.current.at < 3000;
            if (!repeat) {
              lastCode.current = { code: found.data, at: now };
              void submit(found.data);
            }
          }
        }
      }
      raf = window.requestAnimationFrame(loop);
    }

    void start();
    return () => {
      stopped = true;
      window.cancelAnimationFrame(raf);
      stream?.getTracks().forEach(track => track.stop());
    };
  }, [gate, scanning, submit, facing]);

  function flipCamera() {
    setFacing(current => {
      const next = current === 'user' ? 'environment' : 'user';
      localStorage.setItem(CAMERA, next);
      return next;
    });
  }

  const flush = useCallback(async () => {
    const events = readQueue();
    if (events.length === 0 || !gate) return;
    try {
      const result = await gateSync(gateId.trim(), gateToken.trim(), events);
      writeQueue([]);
      setQueued(0);
      if (result.flagged > 0) {
        setError(`오프라인 기록 ${result.flagged}건이 장내 상태와 어긋나 조사 큐로 넘어갔어요.`);
      }
    } catch { /* stay queued until the network is back */ }
  }, [gate, gateId, gateToken]);

  useEffect(() => {
    if (!gate) return;
    void flush();
    const timer = window.setInterval(() => { void flush(); }, 15000);
    window.addEventListener('online', flush);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('online', flush);
    };
  }, [gate, flush]);

  if (!gate) {
    return (
      <section className="gate-screen gate-setup">
        <div className="setup-card">
          <span className="setup-eyebrow">GATE TERMINAL</span>
          <h2>단말이 연결되어 있지 않아요</h2>
          <p>
            관리 화면에서 발급한 <b>설정 링크</b>를 이 태블릿에서 열고 인증번호를 입력하면 연결됩니다.
            토큰을 직접 입력할 필요는 없어요.
          </p>
          {error && <p className="gate-error" role="alert">{error}</p>}
          <button className="btn-ghost" onClick={() => window.location.reload()}>다시 확인</button>
        </div>
      </section>
    );
  }

  return (
    <section className={`gate-screen ${flash ? `gate-${flash.tone}` : ''}`}>
      <div className={`gate-result${outcome ? ` verdict-${outcome.tone}` : ''}`}
        role="status" aria-live="polite">
        {outcome ? (
          <>
            <span className="result-dot" aria-hidden="true" />
            <strong>{outcome.headline}</strong>
            <span className="result-detail">{outcome.detail}</span>
            <code className="gate-ledger-line">{outcome.ledger}</code>
          </>
        ) : (
          <>
            <span className="result-dot result-dot-idle" aria-hidden="true" />
            <span className="result-detail">입장권을 비춰 주세요</span>
          </>
        )}
        {handled > 0 && <span className="result-count">이 단말 {handled}건</span>}
      </div>

      <div className="gate-viewport">
        <video ref={video} muted playsInline className={facing === 'user' ? 'mirrored' : undefined} />
        <canvas ref={frame} hidden />
        {/* Corners, not a box: the same camera reads a code and a face, so a guide
            shaped like either one would tell the visitor the wrong thing. */}
        <div className="gate-frame" aria-hidden="true" />
        <div className="gate-vignette" aria-hidden="true" />
        {/* A terminal that looks asleep between visitors reads as a terminal that is not
            checking. The sweep runs only while it really is reading. */}
        {scanning && !flash && <span className="gate-scanline" aria-hidden="true" />}
        {flash && <GateVerdict key={flash.at} verdict={flash} />}
        {!scanning && <p className="gate-idle">스캔 시작을 누르면 QR을 인식합니다. 얼굴 인식은 따로 켤 수 있어요.</p>}
        {/* On the picture rather than in the bar below: it is the picture it changes. */}
        <button className="gate-flip" onClick={flipCamera}
          title={`${facing === 'user' ? '전면' : '후면'} 카메라 · 앞뒤 전환`}
          aria-label={`지금 ${facing === 'user' ? '전면' : '후면'} 카메라입니다. 앞뒤 전환`}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M2.8 8.8a2 2 0 0 1 2-2H7l1.1-1.9h7.8L17 6.8h2.2a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2H4.8a2 2 0 0 1-2-2z" />
            {/* A broken ring with a head at each end: the universal swap, big enough to
                read at arm's length. */}
            <path d="M8.4 13.1a3.6 3.6 0 0 1 7.2 0" />
            <path d="M14 11.8l1.6 1.6 1.6-1.6" />
            <path d="M15.6 13.7a3.6 3.6 0 0 1-7.2 0" />
            <path d="M10 15l-1.6-1.6L6.8 15" />
          </svg>
        </button>
      </div>

      <footer className="gate-controls">
        <span className={`gate-direction gate-${gate.direction.toLowerCase()}`}>
          {gate.direction === 'IN' ? '입장' : gate.direction === 'OUT' ? '퇴장' : '입·퇴장'}
        </span>
        <span className="gate-label">
          <b>{gate.label || gate.gateId}</b>
          <small>{gate.sessionName}</small>
        </span>
        {queued > 0 && <span className="gate-queued">오프라인 대기 {queued}건</span>}
        {/* Only once it is worth an operator's attention: a date months away is noise. */}
        {daysLeft(gate) !== null && daysLeft(gate)! <= RENEW_WITHIN_DAYS && (
          <button className="gate-queued gate-expiry" onClick={() => renew(false)}
            title="유효기간을 연장합니다">
            유효기간 {daysLeft(gate)! > 0 ? `${daysLeft(gate)}일 남음` : '지남'} · 갱신
          </button>
        )}
        <span className={`gate-live${scanning ? '' : ' gate-live-off'}`}>
          {scanning ? '읽는 중' : '멈춤'}
        </span>
        <button className="btn-ghost" onClick={() => setScanning(value => !value)}>
          {scanning ? '스캔 중지' : '스캔 시작'}
        </button>
        <button className="btn-ghost" onClick={() => setFaceMode(value => {
          localStorage.setItem(FACE_MODE, value ? 'off' : 'on');
          return !value;
        })}>
          얼굴 인식 {faceMode ? '끄기' : '켜기'}
        </button>
      </footer>
      {error && <p className="gate-error" role="alert">{error}</p>}
    </section>
  );
}
