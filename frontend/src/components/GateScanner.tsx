import { useCallback, useEffect, useRef, useState } from 'react';
import jsQR from 'jsqr';
import { gateFaceChallenge, gateFaceScan, gateInfo, gateScan } from '../ticket/api';
import { captureFrames } from '../ticket/camera';

interface GateInfo {
  gateId: string;
  label: string | null;
  zone: string | null;
  direction: 'IN' | 'OUT' | 'BIDIRECTIONAL';
  sessionName: string;
}

interface Outcome {
  kind: 'ok' | 'deny';
  headline: string;
  detail: string;
  at: number;
}

const STORAGE = 'plink.gate.credentials';

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
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [scanning, setScanning] = useState(false);
  const [faceMode, setFaceMode] = useState(false);
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

  async function connect(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const info = await gateInfo(gateId.trim(), gateToken.trim()) as GateInfo;
      setGate(info);
      localStorage.setItem(STORAGE, JSON.stringify({ gateId: gateId.trim(), gateToken: gateToken.trim() }));
    } catch (err) {
      setError(err instanceof Error ? err.message : '단말을 연결하지 못했어요.');
    }
  }

  const submit = useCallback(async (code: string) => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const result = await gateScan(gateId.trim(), gateToken.trim(), code);
      const seat = result.seat ? ` · ${result.seat}` : '';
      setOutcome({
        kind: result.outcome === 'DENIED' ? 'deny' : 'ok',
        headline: result.outcome === 'EXITED' ? '퇴장' : result.outcome === 'DUPLICATE' ? '중복 스캔' : '입장',
        detail: `${result.ticketRef}${seat}${result.message ? ` · ${result.message}` : ''}`,
        at: Date.now(),
      });
    } catch (err) {
      setOutcome({
        kind: 'deny',
        headline: '거부',
        detail: err instanceof Error ? err.message : '확인할 수 없는 코드예요.',
        at: Date.now(),
      });
    } finally {
      // Hold briefly so the operator sees the result before the next read.
      window.setTimeout(() => { inFlight.current = false; }, 1200);
    }
  }, [gateId, gateToken]);

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
        setOutcome({
          kind: 'ok',
          headline: result.outcome === 'EXITED' ? '퇴장' : result.outcome === 'DUPLICATE' ? '중복' : '입장',
          detail: `얼굴 · ${result.ticketRef}${seat}`,
          at: Date.now(),
        });
      } catch (err) {
        const message = err instanceof Error ? err.message : '';
        // "no match" is the normal state between visitors, not something to flash.
        if (message && !message.includes('찾지 못했')) {
          setOutcome({ kind: 'deny', headline: '거부', detail: message, at: Date.now() });
        }
      } finally {
        window.setTimeout(() => { inFlight.current = false; }, 1200);
      }
    }, 2000);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [gate, scanning, faceMode, gateId, gateToken]);

  useEffect(() => {
    if (!gate || !scanning) return;
    let stream: MediaStream | null = null;
    let raf = 0;
    let stopped = false;

    async function start() {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'user', width: { ideal: 1920 }, height: { ideal: 1080 } },
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
  }, [gate, scanning, submit]);

  if (!gate) {
    return (
      <section className="page-section">
        <div className="gate-card">
          <h2>게이트 단말 연결</h2>
          <p className="ticket-hint">주최자 콘솔에서 발급한 게이트 ID와 토큰을 입력하세요. 토큰은 이 단말에만 저장됩니다.</p>
          <form onSubmit={connect}>
            <label htmlFor="gate-id">게이트 ID</label>
            <input id="gate-id" value={gateId} onChange={e => setGateId(e.target.value)} required />
            <label htmlFor="gate-token">게이트 토큰</label>
            <input id="gate-token" type="password" value={gateToken}
              onChange={e => setGateToken(e.target.value)} required />
            <button className="primary" type="submit">연결</button>
          </form>
          {error && <p className="ticket-error" role="alert">{error}</p>}
        </div>
      </section>
    );
  }

  const fresh = outcome && Date.now() - outcome.at < 4000;
  return (
    <section className={`gate-screen ${fresh ? (outcome!.kind === 'ok' ? 'gate-ok' : 'gate-deny') : ''}`}>
      <header className="gate-head">
        <span className={`gate-direction gate-${gate.direction.toLowerCase()}`}>
          {gate.direction === 'IN' ? '입장' : gate.direction === 'OUT' ? '퇴장' : '입·퇴장'}
        </span>
        <span className="gate-label">{gate.label || gate.gateId} · {gate.sessionName}</span>
        <button className="secondary" onClick={() => setScanning(value => !value)}>
          {scanning ? '스캔 중지' : '스캔 시작'}
        </button>
        <button className="secondary" onClick={() => setFaceMode(value => !value)}>
          {faceMode ? '얼굴 인식 끄기' : '얼굴 인식 켜기'}
        </button>
      </header>

      <div className="gate-viewport">
        <video ref={video} muted playsInline />
        <canvas ref={frame} hidden />
        <div className="gate-guides" aria-hidden="true">
          <span className="gate-guide-qr" />
          <span className="gate-guide-face" />
        </div>
        {!scanning && <p className="gate-idle">스캔 시작을 누르면 QR을 인식합니다. 얼굴 인식은 따로 켤 수 있어요.</p>}
      </div>

      <div className="gate-result" role="status" aria-live="polite">
        {outcome
          ? <><strong>{outcome.headline}</strong><span>{outcome.detail}</span></>
          : <span>입장권을 비춰 주세요.</span>}
      </div>
      {error && <p className="ticket-error" role="alert">{error}</p>}
    </section>
  );
}
