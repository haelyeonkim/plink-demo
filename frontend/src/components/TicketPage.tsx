import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import QRCode from 'qrcode';
import {
  cancelTransfer, fetchTicket, reissueTicket, requestOtp, verifyOtp, type TicketView,
} from '../ticket/api';
import { runCeremony, supportsPasskeys } from '../ticket/passkey';
import { CodeMinter, type Grant } from '../ticket/codes';
import FaceEnrolment from './FaceEnrolment';

type Direction = 'IN' | 'OUT';

const IN_APP = [/KAKAOTALK/i, /Instagram/i, /NAVER\(inapp/i, /Line\//i, /FBAN|FBAV/i];

function inAppBrowser(): boolean {
  return IN_APP.some(pattern => pattern.test(navigator.userAgent));
}

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
  const [transferTo, setTransferTo] = useState('');
  const [showTransfer, setShowTransfer] = useState(false);

  const reload = useCallback(async () => {
    try {
      setTicket(await fetchTicket(sessionId, token));
      setLoadError('');
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '입장권을 불러오지 못했어요.');
    }
  }, [sessionId, token]);

  useEffect(() => { void reload(); }, [reload]);

  async function guard(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
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
    const result = await runCeremony(sessionId, token);
    setNotice(result.mode === 'register' && result.viaTransfer
      ? '양도받은 입장권을 이 기기에 등록했어요. 보낸 사람의 링크는 이제 사용할 수 없어요.'
      : '이 기기에 입장권을 등록했어요.');
    await reload();
  });

  const present = (direction: Direction) => guard(async () => {
    const result = await runCeremony(sessionId, token, { direction });
    if (result.mode === 'authenticate' && result.intent === 'PRESENT') setGrant(result.grant);
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
      <section className="page-section">
        <div className="access-card">
          <h2>입장권을 열 수 없어요</h2>
          <p className="error-text" role="alert">{loadError}</p>
          <p className="hint-text">메일로 받은 링크를 휴대폰에서 직접 열어 주세요.</p>
        </div>
      </section>
    );
  }
  if (!ticket) {
    return <section className="page-section"><p className="loading" role="status">입장권을 확인하고 있어요.</p></section>;
  }
  if (grant) {
    return (
      <section className="page-section">
        <RotatingCode grant={grant} ticket={ticket}
          onDone={async () => { setGrant(null); await reload(); }} onRefresh={reload} />
      </section>
    );
  }

  return (
    <section className="page-section">
      <div className="access-card">
        <p className="eyebrow center"><span></span> {ticket.event.name}</p>
        <h2>{ticket.seat ? `${ticket.seat} 좌석` : '입장권'}</h2>

        <dl className="ticket-meta">
          <dt>일시</dt><dd>{timeText(ticket.event.startsAt)}</dd>
          {ticket.event.venue && <><dt>장소</dt><dd>{ticket.event.venue}</dd></>}
          {ticket.tier && <><dt>등급</dt><dd>{ticket.tier}</dd></>}
          <dt>등록 이메일</dt><dd>{ticket.holderEmailMasked}</dd>
          <dt>상태</dt><dd>{ticket.presence.inside ? '장내' : '장외'}</dd>
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
            {!ticket.presence.inside
              ? <button className="btn-primary" onClick={() => present('IN')} disabled={busy}>입장하기</button>
              : <button className="btn-primary" onClick={() => present('OUT')} disabled={busy}>퇴장하기</button>}

            {!ticket.transfer && !ticket.presence.inside && (
              showTransfer ? (
                <form onSubmit={e => { e.preventDefault(); startTransfer(); }}>
                  <div className="field">
                    <label htmlFor="transfer-to">받는 사람 이메일</label>
                    <input id="transfer-to" type="email" required value={transferTo}
                      onChange={e => setTransferTo(e.target.value)} placeholder="friend@example.com" />
                  </div>
                  <button className="btn-primary" type="submit" disabled={busy}>지문 인증하고 양도하기</button>
                  <button className="btn-secondary" type="button" onClick={() => setShowTransfer(false)}>취소</button>
                </form>
              ) : (
                <button className="btn-secondary" onClick={() => setShowTransfer(true)} disabled={busy}>양도하기</button>
              )
            )}
            <button className="btn-secondary" onClick={reissue} disabled={busy}>기기를 바꿨어요</button>
            <p className="hint-text">
              버튼을 누르면 지문·얼굴 인증을 거친 뒤에만 QR이 표시됩니다. QR은 10초마다 새로 만들어지고
              한 번 사용하면 사라져요.
            </p>
            <FaceEnrolment sessionId={sessionId} token={token} />
          </>
        ) : ticket.claimExpired ? (
          <p className="error-text" role="alert">등록 기한이 지났어요. 주최 측에 새 링크를 요청해 주세요.</p>
        ) : (
          <>
            <h3>{ticket.role === 'RECIPIENT' ? '양도받은 입장권 등록' : '입장권 등록'}</h3>
            <p className="hint-text">
              {ticket.role === 'RECIPIENT'
                ? '받는 분의 이메일로 본인 확인을 한 뒤, 이 휴대폰에 입장권을 등록합니다. 등록을 마치면 보낸 사람의 링크는 사용할 수 없게 됩니다.'
                : '입장권을 받은 이메일로 본인 확인을 한 뒤, 이 휴대폰 하나에만 입장권을 등록합니다.'}
            </p>
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
          </>
        )}
      </div>
    </section>
  );
}

/**
 * Draws the rotating code and polls the ticket so the page can confirm the gate read it.
 * The code is minted locally from the grant secret, so it keeps rotating even if the
 * phone loses signal in the queue.
 */
function RotatingCode({ grant, ticket, onDone, onRefresh }: {
  grant: Grant;
  ticket: TicketView;
  onDone: () => Promise<void>;
  onRefresh: () => Promise<void>;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [left, setLeft] = useState(0);
  const [rotation, setRotation] = useState(0);
  const [failed, setFailed] = useState('');
  const wasInside = useRef(ticket.presence.inside);

  useEffect(() => {
    const minter = new CodeMinter(grant);
    let stopped = false;

    async function draw() {
      if (stopped) return;
      try {
        const next = await minter.next();
        if (canvas.current) {
          await QRCode.toCanvas(canvas.current, next, {
            errorCorrectionLevel: 'M', margin: 2, width: 260,
            color: { dark: '#122145', light: '#ffffff' },
          });
        }
      } catch {
        setFailed('QR을 만들지 못했어요. 다시 인증해 주세요.');
      }
    }

    void draw();
    const rotate = window.setInterval(() => { void draw(); }, minter.periodMs);
    const tick = window.setInterval(() => {
      setLeft(minter.secondsLeft());
      setRotation(minter.secondsToRotation());
      if (minter.secondsLeft() <= 0) { void onDone(); }
    }, 250);
    // The gate consumes the grant server-side, so the phone watches for the state flip.
    const poll = window.setInterval(() => { void onRefresh(); }, 3000);

    return () => {
      stopped = true;
      window.clearInterval(rotate);
      window.clearInterval(tick);
      window.clearInterval(poll);
    };
  }, [grant, onDone, onRefresh]);

  useEffect(() => {
    if (ticket.presence.inside !== wasInside.current) { void onDone(); }
  }, [ticket.presence.inside, onDone]);

  return (
    <div className="access-card">
      <p className="eyebrow center"><span></span> {grant.direction === 'IN' ? '입장' : '퇴장'}</p>
      <h2>게이트 단말에 비춰 주세요</h2>
      <canvas ref={canvas} width={260} height={260} className="code-canvas" aria-label="입장 QR 코드" />
      {failed
        ? <p className="error-text" role="alert">{failed}</p>
        : <p className="code-timer" role="status">{left}초 후 만료 · {rotation}초 후 갱신</p>}
      <p className="hint-text">
        화면 밝기를 최대로 올리면 인식이 빨라요. 캡처한 QR은 다음 코드가 만들어지는 순간 무효가 됩니다.
      </p>
      <button className="btn-secondary" onClick={() => { void onDone(); }}>닫기</button>
    </div>
  );
}
