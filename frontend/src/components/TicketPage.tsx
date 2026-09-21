import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import QRCode from 'qrcode';
import {
  cancelTransfer, fetchFaceStatus, fetchTicket, reissueTicket, requestOtp, verifyOtp,
  type TicketView,
} from '../ticket/api';
import { passkeyError, runCeremony, supportsPasskeys, type CeremonyStage } from '../ticket/passkey';
import { CodeMinter, type Grant } from '../ticket/codes';
import FaceEnrolment from './FaceEnrolment';
import Crowding from './Crowding';
import { openLive } from '../ticket/live';
import ScanFlash, { type Movement } from './ScanFlash';
import { reducedMotion } from '../motion';
import CeremonySeal, { type Ceremony, type SealState } from './CeremonySeal';

type Direction = 'IN' | 'OUT';

const IN_APP = [/KAKAOTALK/i, /Instagram/i, /NAVER\(inapp/i, /Line\//i, /FBAN|FBAV/i];

function inAppBrowser(): boolean {
  return IN_APP.some(pattern => pattern.test(navigator.userAgent));
}

function ticketRequiresNoOtp(ticket: TicketView | null): boolean {
  return ticket != null && ticket.event.claimRequiresOtp === false;
}

/**
 * The countdown ring around the code, as a path.
 *
 * <p>A rounded rectangle written out rather than a {@code <rect>}: {@code pathLength}
 * is only dependably honoured on a path, and without it Safari read the dash pattern in
 * user units and drew a dotted frame instead of a draining one.
 */
const RING = (() => {
  const inset = 1.6, radius = 9, far = 100 - inset;
  return `M${inset + radius} ${inset}H${far - radius}A${radius} ${radius} 0 0 1 ${far} ${inset + radius}`
    + `V${far - radius}A${radius} ${radius} 0 0 1 ${far - radius} ${far}`
    + `H${inset + radius}A${radius} ${radius} 0 0 1 ${inset} ${far - radius}`
    + `V${inset + radius}A${radius} ${radius} 0 0 1 ${inset + radius} ${inset}Z`;
})();

/** A beat, so a finished seal is seen closing rather than only reported. */
function beat(ms: number): Promise<void> {
  return new Promise(resolve => { window.setTimeout(resolve, reducedMotion() ? 0 : ms); });
}

/**
 * How long each step of the ceremony is held on screen.
 *
 * <p>The three steps are real work, but on a fast connection all three land inside a
 * blink and the holder sees a flicker where they were meant to read what their phone
 * was doing. This is not reduced-motion territory: it is legibility, so it holds even
 * for somebody who has asked for less movement.
 */
const STEP_MS = 800;

function timeText(value: string): string {
  return new Date(value).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}

export default function TicketPage() {
  const { sessionId = '', token = '' } = useParams<{ sessionId: string; token: string }>();
  const [ticket, setTicket] = useState<TicketView | null>(null);
  const [loadError, setLoadError] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [stage, setStage] = useState<'email' | 'code' | 'passkey'>('email');
  const [grant, setGrant] = useState<Grant | null>(null);
  const [faceEnrolled, setFaceEnrolled] = useState(false);
  const [transferTo, setTransferTo] = useState('');
  const [showTransfer, setShowTransfer] = useState(false);
  // Everything that is not "get me through the door" folds away, so the ticket itself
  // fits the screen a person is holding in a queue.
  const [manage, setManage] = useState(false);
  // The gate's verdict about this ticket, held only as long as it is worth watching.
  const [movement, setMovement] = useState<Movement | null>(null);
  const [ceremony, setCeremony] = useState<Ceremony | null>(null);
  const wasInside = useRef<boolean | null>(null);

  const reload = useCallback(async () => {
    try {
      setTicket(await fetchTicket(sessionId, token));
      setLoadError('');
      const face = await fetchFaceStatus(sessionId, token).catch(() => null);
      setFaceEnrolled(face?.enrolled ?? false);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '입장권을 불러오지 못했어요.');
    }
  }, [sessionId, token]);

  useEffect(() => { void reload(); }, [reload]);

  // The gate pushes its verdict down this channel, so the phone learns the moment it is
  // scanned rather than on the next poll. The polling below stays as the fallback for a
  // network that will not carry a socket.
  useEffect(() => {
    const live = openLive(`/ws/tickets/${sessionId}/${token}`, message => {
      // The server only sends this ticket's movements down this socket, so anything
      // arriving here is about the person holding the phone.
      if (message.type === 'MOVEMENT') setMovement(message as unknown as Movement);
      if (message.type === 'PRESENCE' || message.type === 'MOVEMENT') void reload();
    });
    return () => live.close();
  }, [sessionId, token, reload]);

  // A network that will not carry the socket still has to show the holder that they were
  // read: the poll notices the ledger moved, and the screen says so with what it knows.
  useEffect(() => {
    if (!ticket) return;
    const inside = ticket.presence.inside;
    const before = wasInside.current;
    wasInside.current = inside;
    if (before === null || before === inside) return;
    setMovement(current => current ?? {
      outcome: inside ? 'ADMITTED' : 'EXITED',
      direction: inside ? 'IN' : 'OUT',
      at: new Date().toISOString(),
    });
  }, [ticket]);

  // A verdict is a moment, not a state: it plays, and then the ticket is a ticket again.
  useEffect(() => {
    if (!movement) return;
    const timer = window.setTimeout(() => setMovement(null), 6000);
    return () => window.clearTimeout(timer);
  }, [movement]);

  // The gate changes this ticket's state, not the phone. Coming back to the screen -
  // after a scan, after the screen locked - has to show where the holder actually is.
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void reload(); };
    document.addEventListener('visibilitychange', refresh);
    window.addEventListener('focus', refresh);
    return () => {
      document.removeEventListener('visibilitychange', refresh);
      window.removeEventListener('focus', refresh);
    };
  }, [reload]);

  /**
   * Runs a ceremony with the seal on screen, and leaves its verdict up for a moment.
   *
   * <p>Steps are queued rather than shown as they arrive: each one waits for the one
   * before it to have been on screen long enough to read, so the narration keeps its
   * order and its pace whatever the network does.
   */
  const sealed = useCallback(async function <T>(
    kind: Ceremony['kind'], run: (onStage: (stage: CeremonyStage) => void) => Promise<T>,
  ): Promise<T> {
    let queue = Promise.resolve();
    /** Shows a step once the one before it has been read, and says when it is up. */
    const show = (state: SealState): Promise<void> => {
      const shown = queue.then(() => new Promise<void>(resolve => {
        setCeremony({ kind, state });
        // One frame, so "it is on screen" is true rather than merely scheduled.
        window.requestAnimationFrame(() => resolve());
      }));
      queue = shown.then(() => new Promise<void>(resolve => {
        window.setTimeout(resolve, STEP_MS);
      }));
      return shown;
    };
    void show('preparing');
    try {
      const result = await run(state => show(state));
      void show('done');
      // The steps finish playing before the caller moves on, so a fast ceremony still
      // reads as three things happening rather than one flash.
      await queue;
      return result;
    } catch (failure) {
      queue = queue.then(() => { setCeremony({ kind, state: 'failed' }); });
      await queue;
      throw failure;
    }
  }, []);

  useEffect(() => {
    if (!ceremony || (ceremony.state !== 'done' && ceremony.state !== 'failed')) return;
    const timer = window.setTimeout(() => setCeremony(null), ceremony.state === 'done' ? 1600 : 2400);
    return () => window.clearTimeout(timer);
  }, [ceremony]);

  async function guard(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try { await action(); }
    catch (err) {
      // A cancelled WebAuthn ceremony can carry an empty message, and setting that would
      // leave the screen looking as though the button did nothing at all.
      setError(passkeyError(err) || '요청을 처리하지 못했어요. 다시 시도해 주세요.');
    } finally { setBusy(false); }
  }

  const sendCode = () => guard(async () => {
    await requestOtp(sessionId, token, email);
    setStage('code');
    setNotice('인증번호를 보냈어요. 메일함을 확인해 주세요.');
  });

  const checkCode = () => guard(async () => {
    await verifyOtp(sessionId, token, email, code);
    setStage('passkey');
    setNotice('이메일 확인이 끝났어요. 이제 이 기기를 입장권에 등록해 주세요.');
  });

  const claim = () => guard(async () => {
    const result = await sealed('claim', onStage =>
      runCeremony(sessionId, token, { email: email.trim(), onStage }));
    const viaTransfer = result.mode === 'register' ? result.viaTransfer
      : 'viaTransfer' in result && Boolean((result as { viaTransfer?: boolean }).viaTransfer);
    setNotice(viaTransfer
      ? '양도받은 입장권을 이 기기에 등록했어요. 보낸 사람의 링크는 이제 사용할 수 없어요.'
      : '입장권을 등록했어요.');
    await reload();
  });

  const present = (direction: Direction) => guard(async () => {
    const result = await sealed('open', onStage => runCeremony(sessionId, token, { direction, onStage }));
    if (result.mode === 'authenticate' && result.intent === 'PRESENT') {
      // The seal closes, and only then does the code appear - the order the holder is
      // being told the story in.
      await beat(600);
      setGrant(result.grant);
      return;
    }
    // The server answered with a registration: this device is not the bound one.
    setNotice('이 기기가 입장권에 등록되어 있지 않아요. 등록을 먼저 완료해 주세요.');
    await reload();
  });

  const startTransfer = () => guard(async () => {
    const result = await runCeremony(sessionId, token, { intent: 'TRANSFER', toEmail: transferTo });
    if (result.mode === 'authenticate' && result.intent === 'TRANSFER') {
      setNotice(`${result.transfer.toEmail} 님에게 양도 링크를 보냈어요. 상대가 등록을 마치기 전까지 취소할 수 있어요.`);
      setShowTransfer(false);
      setTransferTo('');
      await reload();
    }
  });

  const stopTransfer = () => guard(async () => {
    await cancelTransfer(sessionId, token);
    setNotice('양도를 취소했어요.');
    await reload();
  });

  const reissue = () => guard(async () => {
    const result = await reissueTicket(sessionId, token);
    setNotice(`${result.sentTo} 로 새 링크를 보냈어요. 이 링크와 기존 기기는 더 이상 사용할 수 없어요.`);
    setGrant(null);
    await reload();
  });

  if (loadError) {
    return (
      <section className="page-section page-tight">
        <div className="access-card">
          <h2>입장권을 열 수 없어요</h2>
          <p className="error-text" role="alert">{loadError}</p>
          <p className="hint-text">메일로 받은 링크를 휴대폰에서 직접 열어 주세요.</p>
        </div>
      </section>
    );
  }
  if (!ticket) {
    return <section className="page-section page-tight"><p className="loading" role="status">입장권을 확인하고 있어요.</p></section>;
  }
  if (grant) {
    return (
      <section className="page-section page-tight ticket-page">
        <RotatingCode grant={grant} ticket={ticket} movement={movement}
          onDone={async () => { setGrant(null); await reload(); }} onRefresh={reload} />
      </section>
    );
  }

  const skipOtp = ticketRequiresNoOtp(ticket);

  return (
    <section className="page-section page-tight ticket-page">
      <div className="access-card">
        {/* Both overlays sit on the card itself: what is being decided is this ticket. */}
        {ceremony && <CeremonySeal ceremony={ceremony} />}
        {movement && !ceremony && (
          <ScanFlash movement={movement} spent={false} inside={ticket.presence.inside} />
        )}
        <p className="eyebrow center"><span></span> {ticket.event.name}</p>
        <h2>
          {ticket.seat ? `${ticket.seat} 좌석` : '입장권'}
          {/* Where the holder is belongs beside the ticket's name, not in a row of
              its own halfway down a list. */}
          {ticket.claimed && (
            <span className={`pill pill-${ticket.presence.inside ? 'in' : 'out'}`}>
              {ticket.presence.inside ? '장내' : '장외'}
            </span>
          )}
        </h2>

        <dl className="ticket-meta">
          <dt>일시</dt><dd>{timeText(ticket.event.startsAt)}</dd>
          {ticket.event.venue && <><dt>장소</dt><dd>{ticket.event.venue}</dd></>}
          {ticket.tier && <><dt>등급</dt><dd>{ticket.tier}</dd></>}
          <dt>등록 이메일</dt><dd>{ticket.holderEmailMasked}</dd>
          {ticket.presence.reentryRemaining !== null && (
            <><dt>남은 재입장</dt><dd>{ticket.presence.reentryRemaining}회</dd></>
          )}
        </dl>

        {inAppBrowser() && (
          <p className="warn-text" role="alert">
            카카오톡 등 앱 안의 브라우저에서는 지문·얼굴 인증을 쓸 수 없어요.
            오른쪽 위 메뉴에서 <b>기본 브라우저로 열기</b>를 선택해 주세요.
          </p>
        )}
        {!supportsPasskeys() && (
          <p className="warn-text" role="alert">
            이 브라우저에서는 패스키를 사용할 수 없어요. 최신 Safari 또는 Chrome에서 열어 주세요.
          </p>
        )}
        {notice && <p className="notice-text" role="status">{notice}</p>}
        {error && <p className="error-text" role="alert">{error}</p>}

        {ticket.transfer && (
          <div className="warn-text" role="status">
            <b>양도 진행 중</b> — {ticket.transfer.toEmail} 님이 등록을 마치면 이 링크는 사용할 수 없게 됩니다.
            {ticket.role === 'HOLDER' && (
              <button className="btn-tiny" onClick={stopTransfer} disabled={busy}>양도 취소</button>
            )}
          </div>
        )}

        {ticket.claimed ? (
          <>
            {faceEnrolled ? (
              <p className="notice-text" role="status">
                얼굴이 등록되어 있어요. 게이트 카메라를 보고 지나가시면 됩니다 — QR은 필요하지 않아요.
              </p>
            ) : !ticket.presence.inside ? (
              <button className="btn-primary" onClick={() => present('IN')} disabled={busy}>입장하기</button>
            ) : (
              <button className="btn-primary" onClick={() => present('OUT')} disabled={busy}>퇴장하기</button>
            )}

            {!faceEnrolled && (
              <p className="hint-text">
                지문·얼굴 인증을 거친 뒤에만 QR이 열립니다. QR은 10초마다 새로 만들어져요.
              </p>
            )}

            {/* Folded by default: the door comes first, and the rest is housekeeping. */}
            <button className="manage-toggle" aria-expanded={manage}
              onClick={() => setManage(value => !value)}>
              입장권 관리
              <span className="entity-chevron" aria-hidden="true">{manage ? '▴' : '▾'}</span>
            </button>
            {manage && (
              <div className="manage-panel">
                <div className="button-row">
                  {!ticket.transfer && !ticket.presence.inside && (
                    <button className="btn-secondary" onClick={() => setShowTransfer(value => !value)} disabled={busy}>
                      {showTransfer ? '양도 취소' : '양도하기'}
                    </button>
                  )}
                  <button className="btn-secondary" onClick={reissue} disabled={busy}>기기를 바꿨어요</button>
                </div>
                {showTransfer && !ticket.transfer && !ticket.presence.inside && (
                  <form onSubmit={e => { e.preventDefault(); startTransfer(); }}>
                    <div className="field">
                      <label htmlFor="transfer-to">받는 사람 이메일</label>
                      <input id="transfer-to" type="email" required value={transferTo}
                        onChange={e => setTransferTo(e.target.value)} placeholder="friend@example.com" />
                    </div>
                    <button className="btn-primary" type="submit" disabled={busy}>지문 인증하고 양도하기</button>
                  </form>
                )}
                <FaceEnrolment sessionId={sessionId} token={token}
                  inside={ticket.presence.inside} onChange={reload} />
              </div>
            )}
          </>
        ) : ticket.claimExpired ? (
          <p className="error-text" role="alert">등록 기한이 지났어요. 주최 측에 새 링크를 요청해 주세요.</p>
        ) : (
          <>
            <h3>{ticket.role === 'RECIPIENT' ? '양도받은 입장권 등록' : '입장권 등록'}</h3>
            <p className="hint-text">
              {ticket.role === 'RECIPIENT'
                ? '받는 분의 이메일로 본인 확인을 한 뒤, 이 휴대폰에 입장권을 등록합니다. 등록을 마치면 보낸 사람의 링크는 사용할 수 없게 됩니다.'
                : skipOtp
                  ? '입장권을 받은 이메일 주소를 입력하면 이 휴대폰 하나에 입장권을 등록합니다. 먼저 등록한 기기에 묶이니 링크를 공유하지 마세요.'
                  : '입장권을 받은 이메일로 본인 확인을 한 뒤, 이 휴대폰 하나에만 입장권을 등록합니다.'}
            </p>
            {skipOtp ? (
              /* No code to prove the inbox, so the address is typed instead: it catches a
                 link that reached the wrong person by mistake. */
              <form onSubmit={e => { e.preventDefault(); claim(); }}>
                <div className="field">
                  <label htmlFor="ticket-email">입장권을 받은 이메일</label>
                  <input id="ticket-email" type="email" autoComplete="email" required
                    value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" />
                </div>
                <button className="btn-primary" type="submit"
                  disabled={busy || !email.trim() || !supportsPasskeys()}>
                  이 휴대폰에 등록하기
                </button>
              </form>
            ) : (<>
            {stage === 'email' && (
              <form onSubmit={e => { e.preventDefault(); sendCode(); }}>
                <div className="field">
                  <label htmlFor="ticket-email">이메일</label>
                  <input id="ticket-email" type="email" autoComplete="email" required
                    value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" />
                </div>
                <button className="btn-primary" type="submit" disabled={busy}>인증번호 받기</button>
              </form>
            )}
            {stage === 'code' && (
              <form onSubmit={e => { e.preventDefault(); checkCode(); }}>
                <div className="field">
                  <label htmlFor="ticket-code">인증번호 6자리</label>
                  <input id="ticket-code" inputMode="numeric" maxLength={6} required
                    value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} />
                </div>
                <button className="btn-primary" type="submit" disabled={busy}>확인</button>
                <button className="btn-secondary" type="button" onClick={() => setStage('email')} disabled={busy}>
                  이메일 다시 입력
                </button>
              </form>
            )}
            {stage === 'passkey' && (
              <button className="btn-primary" onClick={claim} disabled={busy || !supportsPasskeys()}>
                이 휴대폰에 등록하기
              </button>
            )}
            </>)}
          </>
        )}
      </div>

      {/* Under the ticket, where somebody standing in the queue is already looking. */}
      {ticket.claimed && !manage && <Crowding sessionId={sessionId} token={token} />}
    </section>
  );
}

/**
 * Draws the rotating code and polls the ticket so the page can confirm the gate read it.
 * The code is minted locally from the grant secret, so it keeps rotating even if the
 * phone loses signal in the queue.
 */
function RotatingCode({ grant, ticket, movement, onDone, onRefresh }: {
  grant: Grant;
  ticket: TicketView;
  /** The gate's verdict, once it has been read. Until then the code is still live. */
  movement: Movement | null;
  onDone: () => Promise<void>;
  onRefresh: () => Promise<void>;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [left, setLeft] = useState(0);
  const [rotation, setRotation] = useState(0);
  // Which rotation is on screen and how much of it was already gone when it arrived.
  // The ring runs from this in CSS rather than being redrawn on every tick, so it
  // sweeps at the screen's own refresh rate instead of stepping four times a second.
  const [cycle, setCycle] = useState<{ index: number; elapsed: number } | null>(null);
  const [failed, setFailed] = useState('');
  const wasInside = useRef(ticket.presence.inside);
  // Held in refs, not read as dependencies: the poll below re-renders the parent every
  // few seconds, and a callback that changes identity on every render would tear the
  // timers down and mint a new code each time - the QR was rotating on the poll, not
  // on the period.
  const done = useRef(onDone);
  const refresh = useRef(onRefresh);
  done.current = onDone;
  refresh.current = onRefresh;

  useEffect(() => {
    const minter = new CodeMinter(grant);
    let stopped = false;

    async function draw(replacing: boolean) {
      if (stopped) return;
      try {
        const next = await minter.next();
        if (canvas.current) {
          await QRCode.toCanvas(canvas.current, next, {
            errorCorrectionLevel: 'M', margin: 2, width: 260,
            color: { dark: '#122145', light: '#ffffff' },
          });
          // A new code replaces the old one under the holder's hand. Fading it in says
          // that something changed without the snap of a repaint.
          if (replacing && !reducedMotion() && typeof canvas.current.animate === 'function') {
            canvas.current.animate(
              [{ opacity: 0.25, filter: 'blur(4px)' }, { opacity: 1, filter: 'none' }],
              { duration: 420, easing: 'ease-out' });
          }
        }
      } catch {
        setFailed('QR을 만들지 못했어요. 다시 인증해 주세요.');
      }
    }

    void draw(false);
    const period = grant.periodSeconds;
    // The ring starts part-way through the window the phone happened to open in.
    setCycle({ index: minter.windowIndex(), elapsed: period - minter.secondsToRotation() });
    // Redrawn when the rotation window actually turns over, so the QR changes exactly
    // when the countdown says it does - not on a timer started at mount, and not on
    // every poll.
    let shown = minter.windowIndex();
    const tick = window.setInterval(() => {
      const current = minter.windowIndex();
      if (current !== shown) {
        shown = current;
        void draw(true);
        setCycle({ index: current, elapsed: 0 });
      }
      setLeft(minter.secondsLeft());
      setRotation(minter.secondsToRotation());
      if (minter.secondsLeft() <= 0) { void done.current(); }
    }, 250);
    // The gate consumes the grant server-side, so the phone watches for the state flip.
    const poll = window.setInterval(() => { void refresh.current(); }, 3000);

    return () => {
      stopped = true;
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [grant]);

  useEffect(() => {
    if (ticket.presence.inside === wasInside.current) return;
    wasInside.current = ticket.presence.inside;
    if (movement) return;
    // The ledger moved, so this code has been spent. The verdict lands a beat later -
    // on the socket, or on the poll that noticed - and burning the code is what closes
    // this screen. Closing now would take the QR away before either could be seen.
    const timer = window.setTimeout(() => { void done.current(); }, 1500);
    return () => window.clearTimeout(timer);
  }, [ticket.presence.inside, movement]);

  // The code was spent: show it being spent, then hand the holder back their ticket.
  useEffect(() => {
    if (!movement) return;
    const timer = window.setTimeout(() => { void done.current(); }, 3400);
    return () => window.clearTimeout(timer);
  }, [movement]);

  return (
    <div className="access-card">
      {/* Laid over the whole card: the code burns underneath, and what is left is the
          row the gate wrote about this ticket. */}
      {movement && <ScanFlash movement={movement} spent inside={ticket.presence.inside} />}
      <p className="eyebrow center"><span></span> {grant.direction === 'IN' ? '입장' : '퇴장'}</p>
      <h2>{movement ? '읽혔어요' : '게이트 단말에 비춰 주세요'}</h2>
      <div className={`code-stage${movement ? ' code-spent' : ''}`}>
        <canvas ref={canvas} width={260} height={260} className="code-canvas" aria-label="입장 QR 코드" />
        {/* The ring is this code's own life, drawn where it is being used: one unbroken
            sweep per rotation, keyed so each new code starts its own. */}
        {!movement && cycle && (
          <svg className="code-life" viewBox="0 0 100 100" aria-hidden="true">
            <path className="life-track" d={RING} pathLength="1" />
            <path key={cycle.index} className="life-run" d={RING} pathLength="1" style={{
              animationDuration: `${grant.periodSeconds}s`,
              animationDelay: `-${cycle.elapsed}s`,
            }} />
          </svg>
        )}
      </div>
      {failed
        ? <p className="error-text" role="alert">{failed}</p>
        : !movement && <p className="code-timer" role="status">{left}초 후 만료 · {rotation}초 후 갱신</p>}
      {!movement && (
        <p className="hint-text">
          화면 밝기를 최대로 올리면 인식이 빨라요. 캡처한 QR은 다음 코드가 만들어지는 순간 무효가 됩니다.
        </p>
      )}
      <button className="btn-secondary" onClick={() => { void onDone(); }}>닫기</button>
    </div>
  );
}
