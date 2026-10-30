import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import QRCode from 'qrcode';
import {
  ApiError, cancelTransfer, fetchCommunity, presentCoupon, fetchFaceStatus, fetchTicket, presentTicket, reissueTicket, requestOtp, verifyOtp,
  type CommunitySummary, type TicketCoupon, type TicketView,
} from '../ticket/api';
import { coarsePosition, passkeyError, runCeremony, supportsPasskeys, type CeremonyStage } from '../ticket/passkey';
import { CodeMinter, type Grant } from '../ticket/codes';
import { holdZoom } from '@shared/zoom';
import FaceEnrolment from './FaceEnrolment';
import Crowding from './Crowding';
import { openLive } from '../ticket/live';
import { chatCounts, useCoalesced, type ChatCounts } from '../ticket/coalesce';
import ScanFlash, { type Movement } from './ScanFlash';
import CouponWallet from './CouponWallet';
import { boothColor, boothInitial, groupOf, type CouponGroup } from '../ticket/coupons';
import CouponFlash, { type Handover } from './CouponFlash';
import { clockText, reducedMotion } from '../motion';
import CeremonySeal, { type Ceremony, type SealState } from './CeremonySeal';
import TicketChat from './TicketChat';
import TicketAds from './TicketAds';

/** The ticket's own screens, along the bottom once it is registered and open. */
type View = 'ticket' | 'coupons' | 'chat' | 'ads';
const VIEWS: View[] = ['ticket', 'coupons', 'chat', 'ads'];

/** What this phone has already read, so the tabs can say when there is more. */
interface Seen { chat: number; ads: number; alert: number }
function readSeen(key: string): Seen {
  try { return { chat: 0, ads: 0, alert: 0, ...JSON.parse(window.localStorage.getItem(key) ?? '{}') }; }
  catch { return { chat: 0, ads: 0, alert: 0 }; }
}

function clockOf(value: string): string {
  const d = new Date(value);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

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


/**
 * How long each step of the ceremony is held on screen.
 *
 * <p>The three steps are real work, but on a fast connection all three land inside a
 * blink and the holder sees a flicker where they were meant to read what their phone
 * was doing. This is not reduced-motion territory: it is legibility, so it holds even
 * for somebody who has asked for less movement.
 */
const STEP_MS = 800;
/** The server's check is the step people want to see happen, so it stays up longer. */
const VERIFY_MS = 1600;

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
  // The coupon a booth has just taken, and which coupons the open code is for.
  const [used, setUsed] = useState<Handover | null>(null);
  const [codeFor, setCodeFor] = useState<TicketCoupon | null>(null);
  const wasInside = useRef<boolean | null>(null);

  // Which screen is open is part of the address, so the back button walks back through them.
  // The holder's ticket is used like an app on a phone: no zooming while it is open.
  useEffect(() => holdZoom(), []);
  const [params, setParams] = useSearchParams();
  const view: View = VIEWS.includes(params.get('view') as View) ? params.get('view') as View : 'ticket';
  const go = useCallback((next: View) => {
    setParams(next === 'ticket' ? {} : { view: next });
    window.scrollTo(0, 0);
  }, [setParams]);
  // The room's chat and board: whether they are open to this holder, and a nudge each
  // time something new is said there - one per channel, so a line in the chat does not
  // reload the board. A like carries its counts and is patched in, not reloaded.
  const [community, setCommunity] = useState<CommunitySummary | null>(null);
  const [roomTick, setRoomTick] = useState(0);
  const [chatTick, setChatTick] = useState(0);
  const [adsTick, setAdsTick] = useState(0);
  const [counts, setCounts] = useState<ChatCounts | null>(null);
  const nudgeRoom = useCoalesced(useCallback(() => setRoomTick(value => value + 1), []), 400, 400);
  const nudgeChat = useCoalesced(useCallback(() => setChatTick(value => value + 1), []), 400, 400);
  const nudgeAds = useCoalesced(useCallback(() => setAdsTick(value => value + 1), []), 400, 400);
  const seenKey = `plink.seen.${sessionId}.${token.slice(0, 12)}`;
  const [seen, setSeen] = useState<Seen>(() => readSeen(seenKey));
  const markSeen = useCallback((kind: keyof Seen, id: number) => {
    setSeen(current => {
      if (current[kind] >= id) return current;
      const next = { ...current, [kind]: id };
      try { window.localStorage.setItem(seenKey, JSON.stringify(next)); } catch { /* the badge just stays */ }
      return next;
    });
  }, [seenKey]);
  const markChat = useCallback((id: number) => markSeen('chat', id), [markSeen]);
  const markAds = useCallback((id: number) => markSeen('ads', id), [markSeen]);

  // Set once a fresh link has been mailed: this link is dead from then on, and the page
  // keeps saying where the new one went instead of failing to reload a token that is gone.
  const [recoveredTo, setRecoveredTo] = useState<string | null>(null);
  const recoveredRef = useRef(false);

  /**
   * One read of the ticket at a time. A call while one is running asks for one more
   * after it, so whoever awaits it sees what they just changed; with only one in flight,
   * an older answer can never land on top of a newer one. {@code ifIdle} is for the
   * nudges that only mean "check again" - focus and visibilitychange fire together on
   * coming back to the screen - and joins a read already going instead.
   */
  const reading = useRef<Promise<void> | null>(null);
  const readAgain = useRef(false);
  const reload = useCallback((options?: { ifIdle?: boolean }): Promise<void> => {
    if (recoveredRef.current) return Promise.resolve();
    if (reading.current) {
      if (!options?.ifIdle) readAgain.current = true;
      return reading.current;
    }
    const run = async () => {
      do {
        readAgain.current = false;
        try {
          const view = await fetchTicket(sessionId, token);
          setTicket(view);
          setLoadError('');
          if (view.locked) continue;
          const face = await fetchFaceStatus(sessionId, token).catch(() => null);
          setFaceEnrolled(face?.enrolled ?? false);
        } catch (err) {
          setLoadError(err instanceof Error ? err.message : '입장권을 불러오지 못했어요.');
        }
      } while (readAgain.current && !recoveredRef.current);
    };
    const current = run().finally(() => { reading.current = null; });
    reading.current = current;
    return current;
  }, [sessionId, token]);
  // A scan sends MOVEMENT and PRESENCE together, and a hand-over COUPON: one read for all.
  const nudgeTicket = useCoalesced(useCallback(() => { void reload(); }, [reload]), 150);

  useEffect(() => { void reload(); }, [reload]);

  // The gate pushes its verdict down this channel, so the phone learns the moment it is
  // scanned rather than on the next poll. The polling below stays as the fallback for a
  // network that will not carry a socket.
  // Opened only for a registered ticket this phone has proved: a locked ticket's channel,
  // or a transfer link's, would be refused anyway.
  const showing = ticket !== null && !ticket.locked && ticket.claimed;
  useEffect(() => {
    if (!showing) return;
    const live = openLive(`/ws/tickets/${sessionId}/${token}`, message => {
      // The server only sends this ticket's movements down this socket, so anything
      // arriving here is about the person holding the phone.
      if (message.type === 'MOVEMENT') setMovement(message as unknown as Movement);
      // A booth took a coupon: the ticket says so on the spot, as a door does.
      if (message.type === 'COUPON') {
        setUsed(message as unknown as Handover);
      }
      if (message.type === 'PRESENCE' || message.type === 'MOVEMENT' || message.type === 'COUPON') nudgeTicket();
      // The room said something: only that it did, the content comes from the server,
      // which checks this holder is in. A like says only a line's counts, which change
      // nothing on the tiles.
      if (message.type === 'CHAT_LIKE') {
        const changed = chatCounts(message);
        if (changed) { setCounts(changed); return; }
      }
      if (['CHAT', 'CHAT_LIKE', 'CHAT_HIDE'].includes(message.type)) nudgeChat();
      if (message.type === 'ADS') nudgeAds();
      if (['CHAT', 'CHAT_LIKE', 'CHAT_HIDE', 'ADS', 'ALERT'].includes(message.type)) nudgeRoom();
    });
    return () => live.close();
  }, [sessionId, token, showing, nudgeTicket, nudgeChat, nudgeAds, nudgeRoom]);

  const presenceKey = ticket?.presence ? `${ticket.presence.inside}` : '';
  // Only the latest summary may land: an older one arriving late would bring back a
  // taken-down alert.
  const summarySeq = useRef(0);
  useEffect(() => {
    if (!showing) return;
    const seq = ++summarySeq.current;
    fetchCommunity(sessionId, token)
      .then(next => { if (seq === summarySeq.current) setCommunity(next); })
      .catch(() => { if (seq === summarySeq.current) setCommunity(null); });
  }, [sessionId, token, showing, roomTick, presenceKey]);

  // An organiser alert that arrives while the page is open buzzes the phone once; one that
  // was already there when the page opened only shows.
  const alertId = community?.alert?.id ?? 0;
  const lastAlert = useRef<number | null>(null);
  useEffect(() => {
    if (community === null) return;
    if (lastAlert.current !== null && alertId > lastAlert.current && alertId > seen.alert) {
      try { navigator.vibrate?.(200); } catch { /* not every phone lets a page buzz */ }
    }
    lastAlert.current = alertId;
  }, [community, alertId, seen.alert]);

  // A network that will not carry the socket still has to show the holder that they were
  // read: the poll notices the ledger moved, and the screen says so with what it knows.
  useEffect(() => {
    if (!ticket || ticket.locked) return;
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

  useEffect(() => {
    if (!used) return;
    const timer = window.setTimeout(() => setUsed(null), 6000);
    return () => window.clearTimeout(timer);
  }, [used]);

  // The gate changes this ticket's state, not the phone. Coming back to the screen -
  // after a scan, after the screen locked - has to show where the holder actually is.
  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') void reload({ ifIdle: true }); };
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
        window.setTimeout(resolve, state === 'verifying' ? VERIFY_MS : STEP_MS);
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

  /**
   * The code opens on the passkey that opened the ticket: no second prompt. Only when
   * that proof has lapsed (idle too long, or hours later) is the passkey asked for again,
   * and the code follows straight after.
   */
  const present = (direction: Direction) => guard(async () => {
    const open = async () => presentTicket(sessionId, token, direction, await coarsePosition());
    try {
      setGrant(await open());
    } catch (err) {
      if (!(err instanceof ApiError) || err.status !== 403) throw err;
      const view = await fetchTicket(sessionId, token);
      if (!view.locked) throw err;
      await sealed('unlock', onStage => runCeremony(sessionId, token, { intent: 'VIEW', onStage }));
      await reload();
      setGrant(await open());
    }
  });

  /**
   * The code of one coupon of a group - the oldest - for its booth to read. Asks for the
   * passkey only if this browser has not.
   */
  const openCouponCode = (group: CouponGroup) => guard(async () => {
    const open = () => presentCoupon(sessionId, token, group.ids[0]);
    let opened;
    try {
      opened = await open();
    } catch (err) {
      if (!(err instanceof ApiError) || err.status !== 403) throw err;
      const view = await fetchTicket(sessionId, token);
      if (!view.locked) throw err;
      await sealed('unlock', onStage => runCeremony(sessionId, token, { intent: 'VIEW', onStage }));
      await reload();
      opened = await open();
    }
    setUsed(null);
    setCodeFor(group.first);
    setGrant(opened);
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

  /** Proves this phone holds the ticket, which is what opens the rest of the page. */
  const unlock = () => guard(async () => {
    await sealed('unlock', onStage => runCeremony(sessionId, token, { intent: 'VIEW', onStage }));
    await reload();
  });

  // A locked ticket asks for the passkey straight away; a phone that will not raise the
  // prompt without a tap still has the button.
  const askedOnce = useRef(false);
  useEffect(() => {
    if (!ticket?.locked || askedOnce.current || !supportsPasskeys() || inAppBrowser()) return;
    askedOnce.current = true;
    void unlock();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ticket?.locked]);

  // The holder on a new phone has no passkey here: the mailbox proves it is theirs, and
  // a fresh link goes to it.
  const [recovering, setRecovering] = useState(false);
  const sendRecoveryCode = () => guard(async () => {
    await requestOtp(sessionId, token, email);
    setStage('code');
    // The server answers alike for any address, so the page cannot say more than this.
    setNotice('입장권을 받은 주소가 맞다면 인증번호가 메일로 갔어요. 메일함을 확인해 주세요.');
  });
  const recoverByEmail = () => guard(async () => {
    await verifyOtp(sessionId, token, email, code);
    const result = await reissueTicket(sessionId, token);
    recoveredRef.current = true;
    setRecovering(false);
    setRecoveredTo(String(result.sentTo));
  });

  const reissue = () => guard(async () => {
    const result = await reissueTicket(sessionId, token);
    setNotice(`${result.sentTo} 로 새 링크를 보냈어요. 이 링크와 기존 기기는 더 이상 사용할 수 없어요.`);
    setGrant(null);
    await reload();
  });

  if (recoveredTo) {
    return (
      <section className="page-section page-tight">
        <div className="access-card">
          <h2>새 링크를 보냈어요</h2>
          <p className="notice-text" role="status">{recoveredTo} 로 새 입장권 링크를 보냈어요.</p>
          <p className="hint-text">
            메일의 새 링크를 이 휴대폰에서 열어 다시 등록해 주세요. 이전 기기와 지금 이 링크는 더 이상
            사용할 수 없어요.
          </p>
        </div>
      </section>
    );
  }
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
  if (ticket.locked) {
    return (
      <section className="page-section page-tight ticket-page">
        <div className="access-card">
          {ceremony && <CeremonySeal ceremony={ceremony} />}
          <p className="eyebrow center"><span></span> {ticket.event.name}</p>
          <h2>본인 확인이 필요해요</h2>
          <dl className="ticket-meta">
            <dt>일시</dt><dd>{timeText(ticket.event.startsAt)}</dd>
            {ticket.event.venue && <><dt>장소</dt><dd>{ticket.event.venue}</dd></>}
          </dl>
          <p className="hint-text">
            이미 등록된 입장권이에요. 등록한 휴대폰의 지문·얼굴 인증을 거쳐야 좌석과 입장 정보가 보여요.
          </p>
          {inAppBrowser() && (
            <p className="warn-text" role="alert">
              카카오톡 등 앱 안의 브라우저에서는 지문·얼굴 인증을 쓸 수 없어요.
              오른쪽 위 메뉴에서 <b>기본 브라우저로 열기</b>를 선택해 주세요.
            </p>
          )}
          {notice && <p className="notice-text" role="status">{notice}</p>}
          {error && <p className="error-text" role="alert">{error}</p>}
          <button className="btn-primary" onClick={unlock} disabled={busy || !supportsPasskeys()}>
            패스키로 열기
          </button>

          {/* Not on the registered phone: the only way forward is a new link to the
              mailbox, which retires this one. */}
          <button className="manage-toggle" aria-expanded={recovering}
            onClick={() => { setRecovering(value => !value); setStage('email'); setError(''); }}>
            휴대폰을 바꿨거나 패스키가 없어요
            <span className="entity-chevron" aria-hidden="true">{recovering ? '▴' : '▾'}</span>
          </button>
          {recovering && (
            <div className="manage-panel">
              <p className="hint-text">
                입장권을 받은 이메일로 인증하면 새 링크를 보내 드려요. 새 링크로 이 휴대폰에 다시
                등록하면 이전 기기와 지금 링크는 사용할 수 없게 됩니다.
              </p>
              {stage !== 'code' ? (
                <form onSubmit={e => { e.preventDefault(); sendRecoveryCode(); }}>
                  <div className="field">
                    <label htmlFor="recover-email">입장권을 받은 이메일</label>
                    <input id="recover-email" type="email" autoComplete="email" required
                      value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" />
                  </div>
                  <button className="btn-secondary" type="submit" disabled={busy}>인증번호 받기</button>
                </form>
              ) : (
                <form onSubmit={e => { e.preventDefault(); recoverByEmail(); }}>
                  <div className="field">
                    <label htmlFor="recover-code">인증번호 6자리</label>
                    <input id="recover-code" inputMode="numeric" maxLength={6} required
                      value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} />
                  </div>
                  <button className="btn-secondary" type="submit" disabled={busy}>확인하고 새 링크 받기</button>
                </form>
              )}
            </div>
          )}
        </div>
      </section>
    );
  }
  if (grant) {
    return (
      <section className="page-section page-tight ticket-page ticket-present">
        <RotatingCode key={grant.grantId} grant={grant} ticket={ticket} movement={movement} used={used}
          coupon={grant.direction === 'CPN' ? codeFor : null}
          onAnother={group => openCouponCode(group)}
          onDone={async () => { setGrant(null); setCodeFor(null); await reload(); }}
          onRefresh={() => reload({ ifIdle: true })} />
      </section>
    );
  }

  const skipOtp = ticketRequiresNoOtp(ticket);


  const tabs = ticket.claimed;
  const entered = community?.entered === true;
  const liveCoupons = ticket.coupons?.filter(row => row.status !== 'REDEEMED' && row.status !== 'VOID').length ?? 0;
  const newChat = entered && (community?.latestChatId ?? 0) > seen.chat;
  const newAds = entered && (community?.latestAdId ?? 0) > seen.ads;

  const alert = community?.alert && community.alert.id > seen.alert ? community.alert : null;
  const alertBanner = alert && (
    <aside className="ticket-alert" role="status" aria-live="polite">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1zM16 8a5 5 0 0 1 0 8" /></svg>
      <span>
        <b>운영팀 알림 <small>{clockOf(alert.createdAt)}</small></b>
        <span>{alert.body}</span>
      </span>
      <button type="button" aria-label="알림 닫기" onClick={() => markSeen('alert', alert.id)}>✕</button>
    </aside>
  );

  const tabBar = tabs && (
    <nav className="ticket-tabs" aria-label="입장권 메뉴">
      {([
        ['ticket', '입장권', 'M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z', null, false],
        ['coupons', '쿠폰', 'M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8zM7.5 6a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z',
          liveCoupons > 0 ? String(liveCoupons) : null, false],
        ['chat', '채팅', 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z', newChat ? '새' : null, !entered],
        ['ads', '알림판', 'M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1zM16 8a5 5 0 0 1 0 8', newAds ? '새' : null, !entered],
      ] as Array<[View, string, string, string | null, boolean]>).map(([key, label, icon, badge, locked]) => (
        <button key={key} type="button" aria-current={view === key ? 'page' : undefined}
          className={view === key ? 'on' : ''} disabled={locked}
          aria-label={locked ? `${label} · 입장 후 이용` : badge ? `${label} · ${badge === '새' ? '새 소식' : `${badge}장`}` : label}
          onClick={() => go(key)}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d={locked ? 'M5 11h14v10H5zM8 11V7a4 4 0 0 1 8 0v4' : icon} /></svg>
          <span>{label}</span>
          {badge && !locked && <em aria-hidden="true">{badge}</em>}
        </button>
      ))}
    </nav>
  );

  if (tabs && view !== 'ticket') {
    return (
      <>
        <section className={`page-section page-tight ticket-tab ticket-tab-${view}`}>
          {view !== 'chat' && alertBanner}
          {view === 'coupons' && (
            <CouponWallet coupons={ticket.coupons} busy={busy} onCode={openCouponCode}
              onNews={entered ? () => go('ads') : null} />
          )}
          {view === 'chat' && (entered
            ? <TicketChat sessionId={sessionId} token={token} tick={chatTick} counts={counts} onSeen={markChat} />
            : <p className="console-none">입장한 뒤에 볼 수 있어요.</p>)}
          {view === 'ads' && (entered
            ? <TicketAds sessionId={sessionId} token={token} tick={adsTick} seenId={seen.ads} onSeen={markAds} />
            : <p className="console-none">입장한 뒤에 볼 수 있어요.</p>)}
        </section>
        {tabBar}
      </>
    );
  }

  return (
    <>
    <section className={`page-section page-tight ticket-page${tabs ? ' with-tabs' : ''}`}>
      {alertBanner}
      <div className="access-card">
        {/* Both overlays sit on the card itself: what is being decided is this ticket. */}
        {ceremony && <CeremonySeal ceremony={ceremony} />}
        {movement && !ceremony && (
          <ScanFlash movement={movement} spent={false} inside={ticket.presence.inside} />
        )}
        {used && !ceremony && !movement && <CouponFlash used={used} spent={false} />}
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
            <b>양도 진행 중</b> - {ticket.transfer.toEmail} 님이 등록을 마치면 이 링크는 사용할 수 없게 됩니다.
            {ticket.role === 'HOLDER' && (
              <button className="btn-tiny" onClick={stopTransfer} disabled={busy}>양도 취소</button>
            )}
          </div>
        )}

        {ticket.claimed ? (
          <>
            {faceEnrolled ? (
              <p className="notice-text" role="status">
                얼굴이 등록되어 있어요. 게이트 카메라를 보고 지나가시면 됩니다 - QR은 필요하지 않아요.
              </p>
            ) : !ticket.presence.inside ? (
              <button className="btn-primary" onClick={() => present('IN')} disabled={busy}>입장하기</button>
            ) : (
              <button className="btn-primary" onClick={() => present('OUT')} disabled={busy}>퇴장하기</button>
            )}

            {/* The room's channels, one tap from the ticket once the holder is in. */}
            <section className="room-tiles" aria-labelledby="room-title">
              <h3 id="room-title">장내 소식</h3>
              {entered ? (<>
                <button type="button" className="room-tile" onClick={() => go('chat')}>
                  <b>장내 채팅{newChat && <em>새</em>}</b>
                  {community?.notice && <span className="room-notice"><strong>공지</strong> {community.notice}</span>}
                  <small>{community?.lastMessage
                    ? <><strong>{community.lastMessage.author}</strong> {community.lastMessage.body}</>
                    : '아직 메시지가 없어요'}</small>
                </button>
                <button type="button" className="room-tile" onClick={() => go('ads')}>
                  <b>알림판{newAds && <em>새</em>}</b>
                  <small>{community?.topAd
                    ? <><strong>{community.topAd.booth}</strong> {community.topAd.title}</>
                    : '아직 올라온 소식이 없어요'}</small>
                </button>
              </>) : (
                <p className="room-locked">입장하면 장내 채팅과 알림판이 열려요.</p>
              )}
            </section>

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
                  inside={ticket.presence.inside} onChange={() => reload()} />
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
    {tabBar}
    </>
  );
}

/**
 * Draws the rotating code and polls the ticket so the page can confirm the gate read it.
 * The code is minted locally from the grant secret, so it keeps rotating even if the
 * phone loses signal in the queue.
 */
function RotatingCode({ grant, ticket, movement, used, coupon, onAnother, onDone, onRefresh }: {
  grant: Grant;
  ticket: TicketView;
  /** For a coupon's own code: the coupon it spends, to draw the stub and say what is left. */
  coupon: TicketCoupon | null;
  /** Opens the next coupon of the same kind, straight from the "used" screen. */
  onAnother: (group: CouponGroup) => void;
  /** The gate's verdict, once it has been read. Until then the code is still live. */
  movement: Movement | null;
  /** A stand took a coupon against this code, which spends it exactly as a door does. */
  used: Handover | null;
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
            errorCorrectionLevel: 'M', margin: 2, width: 520,
            color: { dark: '#122145', light: '#ffffff' },
          });
          // The library writes its pixel size onto the element. The stage decides how big
          // the code shows - smaller on a short screen - so that size is taken off again;
          // drawn at twice the size, it stays sharp whatever the stage makes of it.
          canvas.current.style.width = '';
          canvas.current.style.height = '';
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

  // A coupon's code ends on its own screen, which stays until the holder moves on: the
  // parent lets the live message go after a few seconds, so it is kept here.
  const [spentBy, setSpentBy] = useState<Handover | null>(null);
  useEffect(() => { if (coupon && used) setSpentBy(used); }, [coupon, used]);
  // How many of this kind there were when the code opened: "2장 중 1장".
  const [held] = useState(() => (coupon ? groupOf(ticket.coupons, coupon)?.ids.length ?? 1 : 0));

  // The code was spent: show it being spent, then hand the holder back their ticket.
  useEffect(() => {
    if (coupon || (!movement && !used)) return;
    const timer = window.setTimeout(() => { void done.current(); }, 3400);
    return () => window.clearTimeout(timer);
  }, [coupon, movement, used]);

  if (coupon && spentBy) {
    const left = groupOf(ticket.coupons, coupon);
    const taken = spentBy.outcome === 'REDEEMED';
    return (
      <div className="coupon-done">
        <section className="coupon-done-card" role="status" aria-live="assertive">
          <span className={`coupon-done-mark${taken ? '' : ' repeat'}`} aria-hidden="true">
            <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6"
              strokeLinecap="round" strokeLinejoin="round">{taken ? <path d="M5 12.5l4.5 4.5L19 7.5" /> : <path d="M12 7v6M12 17h.01" />}</svg>
          </span>
          <h2>{taken ? '쿠폰을 사용했어요' : '이미 사용한 쿠폰이에요'}</h2>
          <div className="coupon-done-row">
            <span className="stub-mark" style={{ background: boothColor(coupon.booth) }} aria-hidden="true">{boothInitial(coupon.booth)}</span>
            <span>
              <b>{spentBy.title || coupon.title}</b>
              <small>{[spentBy.booth ?? coupon.booth, clockText(spentBy.at)].filter(Boolean).join(' · ')}</small>
            </span>
          </div>
          {left && <p>이 쿠폰이 <b>{left.ids.length}장</b> 더 남아 있어요.</p>}
          <p className="coupon-done-note">방금 보인 QR은 폐기됐어요.</p>
        </section>
        <div className="coupon-done-actions">
          {left && <button type="button" className="btn-primary" onClick={() => onAnother(left)}>한 장 더 쓰기</button>}
          <button type="button" className="btn-secondary" onClick={() => { void onDone(); }}>쿠폰 목록</button>
        </div>
      </div>
    );
  }

  if (coupon) {
    return (
      <div className="access-card code-panel coupon-code">
        <div className="coupon-code-head" style={{ background: boothColor(coupon.booth) }}>
          <span className="stub-mark" aria-hidden="true">{boothInitial(coupon.booth)}</span>
          <span>
            <small>쿠폰 · {coupon.booth ?? '부스'}</small>
            <b>{coupon.title}</b>
          </span>
          {held > 1 && <em>{held}장 중 1장</em>}
        </div>
        <h2>{coupon.booth ?? '부스'} 단말에 비춰 주세요</h2>
        <div className="code-stage">
          <canvas ref={canvas} width={260} height={260} className="code-canvas" aria-label="쿠폰 QR 코드" />
          {cycle && (
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
          : <p className="code-timer" role="status">{left}초 후 만료 · {rotation}초 후 갱신</p>}
        <p className="hint-text">QR은 10초마다 바뀌어 캡처해 넘길 수 없어요. 읽히면 이 쿠폰 한 장만 쓰여요.</p>
        <button className="btn-secondary" onClick={() => { void onDone(); }}>닫기</button>
      </div>
    );
  }

  return (
    <div className="access-card code-panel">
      {/* Laid over the whole card: the code burns underneath, and what is left is the
          row the gate wrote about this ticket. */}
      {movement && <ScanFlash movement={movement} spent inside={ticket.presence.inside} />}
      {!movement && used && <CouponFlash used={used} spent />}
      <p className="eyebrow center"><span></span> {grant.direction === 'CPN' ? '쿠폰' : grant.direction === 'IN' ? '입장' : '퇴장'}</p>
      <h2>{movement || used ? '읽혔어요'
        : grant.direction === 'CPN' ? `${grant.booth ?? '부스'} 단말에 비춰 주세요` : '게이트 단말에 비춰 주세요'}</h2>
      {grant.direction === 'CPN' && grant.title && <p className="code-coupon">{grant.title}</p>}
      <div className={`code-stage${movement || used ? ' code-spent' : ''}`}>
        <canvas ref={canvas} width={260} height={260} className="code-canvas"
          aria-label={grant.direction === 'CPN' ? '쿠폰 QR 코드' : '입장 QR 코드'} />
        {/* The ring is this code's own life, drawn where it is being used: one unbroken
            sweep per rotation, keyed so each new code starts its own. */}
        {!movement && !used && cycle && (
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
        : !movement && !used && (
          <p className="code-timer" role="status">{left}초 후 만료 · {rotation}초 후 갱신</p>
        )}
      {!movement && !used && (
        <p className="hint-text">
          화면 밝기를 최대로 올리면 인식이 빨라요. 캡처한 QR은 다음 코드가 만들어지는 순간 무효가 됩니다.
        </p>
      )}
      <button className="btn-secondary" onClick={() => { void onDone(); }}>닫기</button>
    </div>
  );
}
