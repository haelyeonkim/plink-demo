import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  dislikeChat, fetchChat, likeChat, reportChat, sendChat, type ChatMessage, type ChatView, type Reaction,
} from '../ticket/api';
import { boothColor } from '../ticket/coupons';

function clock(value: string): string {
  const d = new Date(value);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Seconds until this holder may write again, ticking down; 0 when they may. */
function useCountdown(nextAt: string | null): number {
  const [now, setNow] = useState(Date.now());
  const until = nextAt ? new Date(nextAt).getTime() : 0;
  useEffect(() => {
    if (until <= Date.now()) return;
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [until]);
  return Math.max(0, Math.ceil((until - now) / 1000));
}

/**
 * Fits the room to what the phone actually shows: from under the header down to the
 * tabs, or, while the keyboard is up, from the top of the visible screen down to the
 * keys. iOS keeps the layout the same size when the keyboard opens and only shows less
 * of it, so the visible viewport is the measure, not 100vh.
 */
function useFitToScreen(onFit: () => void) {
  useLayoutEffect(() => {
    const root = document.documentElement;
    const fit = () => {
      const view = window.visualViewport;
      const height = view?.height ?? window.innerHeight;
      const offset = view?.offsetTop ?? 0;
      const typing = height < window.innerHeight - 120;
      root.classList.toggle('chat-typing', typing);
      const header = typing ? 0 : (document.querySelector('.header') as HTMLElement | null)?.offsetHeight ?? 0;
      const tabs = typing ? 0 : (document.querySelector('.ticket-tabs') as HTMLElement | null)?.offsetHeight ?? 0;
      root.style.setProperty('--chat-top', `${offset + header}px`);
      root.style.setProperty('--chat-height', `${Math.max(240, height - header - tabs)}px`);
      onFit();
    };
    fit();
    const view = window.visualViewport;
    view?.addEventListener('resize', fit);
    view?.addEventListener('scroll', fit);
    window.addEventListener('resize', fit);
    return () => {
      view?.removeEventListener('resize', fit);
      view?.removeEventListener('scroll', fit);
      window.removeEventListener('resize', fit);
      root.classList.remove('chat-typing');
      root.style.removeProperty('--chat-top');
      root.style.removeProperty('--chat-height');
    };
  }, [onFit]);
}

const ThumbUp = ({ filled = false, size = 14 }: { filled?: boolean; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor"
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M7 11v9H4a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1zM7 11l4-8a2 2 0 0 1 2 2v4h5.5a2 2 0 0 1 2 2.3l-1.2 7A2 2 0 0 1 17.3 20H7" />
  </svg>
);

const ThumbDown = ({ filled = false, size = 14 }: { filled?: boolean; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor"
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M17 13V4h3a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1zM17 13l-4 8a2 2 0 0 1-2-2v-4H5.5a2 2 0 0 1-2-2.3l1.2-7A2 2 0 0 1 6.7 4H17" />
  </svg>
);

/** Who said a line, as a key: a run of lines from one person shows their name once. */
const speaker = (m: ChatMessage) => (m.staff ? 'staff' : m.mine ? 'me' : m.author);

/**
 * The room's chat: everyone who has come in, under names made from their tickets. A line
 * can be answered, liked or reported; one that gathers enough likes is held at the top for
 * a while, below the organiser's notice. Writing waits out a cooldown between lines.
 */
export default function TicketChat({ sessionId, token, tick, onSeen }: {
  sessionId: string; token: string; tick: number; onSeen: (latestId: number) => void;
}) {
  const [view, setView] = useState<ChatView | null>(null);
  const [error, setError] = useState('');
  const [text, setText] = useState('');
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null);
  const [menuFor, setMenuFor] = useState<ChatMessage | null>(null);
  const [sending, setSending] = useState(false);
  const [pinsOpen, setPinsOpen] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  const list = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  // A room that changes size keeps the newest line in sight when that is where the holder was.
  const keepBottom = useCallback(() => {
    const box = list.current;
    if (box && stick.current) box.scrollTop = box.scrollHeight;
  }, []);
  useFitToScreen(keepBottom);

  const load = useCallback(async () => {
    try {
      const next = await fetchChat(sessionId, token);
      setView(next);
      setError('');
      const last = next.messages[next.messages.length - 1];
      if (last) onSeen(last.id);
    } catch (err) { setError(err instanceof Error ? err.message : '채팅을 불러오지 못했어요.'); }
  }, [sessionId, token, onSeen]);

  useEffect(() => { void load(); }, [load, tick]);
  // The socket only says something changed; this keeps a quiet page fresh if it drops.
  useEffect(() => {
    const timer = window.setInterval(() => { void load(); }, 20000);
    return () => window.clearInterval(timer);
  }, [load]);
  // Follow new lines down, unless the holder has scrolled up to read.
  useEffect(() => {
    const box = list.current;
    if (box && stick.current) box.scrollTop = box.scrollHeight;
  }, [view?.messages.length]);

  const wait = useCountdown(view?.me.nextAt ?? null);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    if (!view || !text.trim() || sending || wait > 0) return;
    setSending(true);
    try {
      await sendChat(sessionId, token, text.trim(), replyTo?.id ?? null);
      setText('');
      setReplyTo(null);
      stick.current = true;
      await load();
    } catch (err) { setError(err instanceof Error ? err.message : '보내지 못했어요.'); await load(); }
    finally { setSending(false); }
  }

  async function react(message: ChatMessage, press: () => Promise<Reaction>) {
    try {
      const result = await press();
      setView(current => current && ({
        ...current,
        messages: current.messages.map(m => m.id === message.id ? {
          ...m, likes: result.likes, liked: result.liked, dislikes: result.dislikes, disliked: result.disliked,
        } : m),
      }));
    } catch (err) { setError(err instanceof Error ? err.message : '누르지 못했어요.'); }
  }
  const like = (message: ChatMessage) => react(message, () => likeChat(sessionId, token, message.id));
  const dislike = (message: ChatMessage) => react(message, () => dislikeChat(sessionId, token, message.id));

  async function report(message: ChatMessage) {
    setMenuFor(null);
    if (!window.confirm('이 메시지를 신고할까요? 신고가 모이면 운영팀 확인 전까지 가려져요.')) return;
    try { await reportChat(sessionId, token, message.id); await load(); }
    catch (err) { setError(err instanceof Error ? err.message : '신고하지 못했어요.'); }
  }

  if (!view) {
    return <div className="chat-room"><p className="console-none chat-loading">{error || '채팅을 불러오고 있어요.'}</p></div>;
  }
  const { me } = view;
  const left = view.maxLength - [...text].length;
  const pinned = [view.notice, view.hot].filter((m): m is ChatMessage => m !== null);
  const rules = [
    view.cooldownSeconds > 0
      ? `${view.cooldownSeconds >= 60 ? `${Math.round(view.cooldownSeconds / 60)}분` : `${view.cooldownSeconds}초`}에 한 번 보낼 수 있어요`
      : null,
    `좋아요 ${view.hotLikes}개면 잠시 위에 고정돼요`,
    '입장한 관람객만 볼 수 있고, 장내에 있을 때만 쓸 수 있어요',
  ].filter(Boolean) as string[];
  const current = menuFor ? view.messages.find(m => m.id === menuFor.id) ?? menuFor : null;

  return (
    <div className="chat-room">
      <header className="cr-bar">
        <svg className="cr-mark" width="22" height="22" viewBox="0 0 30 30" aria-hidden="true">
          <path d="M8 0H22A8 8 0 0 1 30 8V11.5A3.5 3.5 0 0 0 30 18.5V22A8 8 0 0 1 22 30H8A8 8 0 0 1 0 22V18.5A3.5 3.5 0 0 0 0 11.5V8A8 8 0 0 1 8 0Z" fill="var(--blue)" />
          <circle cx="15" cy="12" r="4" fill="#fff" /><path d="M12.7 16H17.3L18.7 23H11.3Z" fill="#fff" />
        </svg>
        <span className="cr-title"><h2>채팅</h2><small>장내 {view.inside.toLocaleString()}명</small></span>
        <span className="cr-me" title="채팅에서 보이는 내 이름">
          <span className="cr-avatar small" style={{ background: boothColor(me.nickname) }} aria-hidden="true">{[...me.nickname][0]}</span>
          {me.nickname}
        </span>
        <button type="button" className="cr-icon" aria-label="채팅 규칙" aria-expanded={rulesOpen}
          onClick={() => setRulesOpen(open => !open)}>
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8h.01" /></svg>
        </button>
      </header>
      {rulesOpen && <ul className="cr-rules">{rules.map(rule => <li key={rule}>{rule}</li>)}</ul>}

      {pinned.length > 0 && (
        <div className={`cr-pins${pinsOpen ? ' open' : ''}`}>
          <button type="button" className="cr-pin-bar" aria-expanded={pinsOpen} onClick={() => setPinsOpen(open => !open)}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1zM16 8a5 5 0 0 1 0 8" /></svg>
            <span>{pinned[0].body}</span>
            {pinned.length > 1 && <em>+ 인기 1</em>}
            <svg className="cr-chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
          </button>
          {pinsOpen && (
            <ul className="cr-pin-list">
              {view.notice && <li className="notice"><b>공지</b><span>{view.notice.body}</span></li>}
              {view.hot && (
                <li className="hot">
                  <a href={`#chat-${view.hot.id}`} onClick={() => setPinsOpen(false)}>
                    <b>인기</b><span>{view.hot.body}</span>
                    <small><ThumbUp size={12} /> {view.hot.likes}</small>
                  </a>
                </li>
              )}
            </ul>
          )}
        </div>
      )}

      <div className="cr-list" ref={list} aria-label="메시지" aria-live="polite"
        onScroll={event => {
          const box = event.currentTarget;
          stick.current = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
        }}>
        <p className="cr-day">입장한 관람객만 볼 수 있어요</p>
        {view.messages.length === 0 && <p className="console-none">아직 메시지가 없어요. 첫 인사를 남겨 보세요.</p>}
        {view.messages.map((message, index) => {
          const prev = view.messages[index - 1];
          const next = view.messages[index + 1];
          if (message.staff) {
            return (
              <p key={message.id} id={`chat-${message.id}`} className="cr-staff">
                <b>운영팀</b><span>{message.body}</span>
              </p>
            );
          }
          const first = !prev || prev.staff || speaker(prev) !== speaker(message);
          const last = !next || next.staff || speaker(next) !== speaker(message)
            || clock(next.createdAt) !== clock(message.createdAt);
          const hot = view.hot?.id === message.id;
          return (
            <article key={message.id} id={`chat-${message.id}`}
              className={`cr-line${message.mine ? ' mine' : ''}${first ? ' first' : ''}`}>
              {!message.mine && (first
                ? <span className="cr-avatar" style={{ background: boothColor(message.author) }} aria-hidden="true">{[...message.author][0]}</span>
                : <span className="cr-avatar-gap" aria-hidden="true" />)}
              <div className="cr-stack">
                {!message.mine && first && <span className="cr-name">{message.author}</span>}
                {hot && <span className="cr-hot"><svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <path d="M12 2c1.5 5 6 6 6 12a6 6 0 0 1-12 0c0-2.5 1.2-4 2.5-5 0 2.5 1.2 3.5 2.5 3.5 0-4-1.5-6 1-10.5z" /></svg>인기</span>}
                <div className="cr-row">
                  <button type="button" className="cr-bubble" aria-haspopup="dialog"
                    aria-label={`${message.mine ? '내' : message.author} 메시지: ${message.body}. 메뉴 열기`}
                    onClick={() => setMenuFor(message)}>
                    {message.reply && <span className="cr-quote"><b>{message.reply.author}</b><span>{message.reply.body}</span></span>}
                    <span>{message.body}</span>
                  </button>
                  {last && <time dateTime={message.createdAt}>{clock(message.createdAt)}</time>}
                </div>
                {(message.likes > 0 || message.dislikes > 0) && (
                  <span className="cr-reacts">
                    {message.likes > 0 && (
                      <button type="button" className={message.liked ? 'on' : ''} disabled={message.mine}
                        aria-label={`좋아요 ${message.likes}${message.liked ? ', 누름' : ''}`} onClick={() => void like(message)}>
                        <ThumbUp filled={message.liked} size={12} /> {message.likes}</button>
                    )}
                    {message.dislikes > 0 && (
                      <button type="button" className={message.disliked ? 'on down' : 'down'} disabled={message.mine}
                        aria-label={`싫어요 ${message.dislikes}${message.disliked ? ', 누름' : ''}`} onClick={() => void dislike(message)}>
                        <ThumbDown filled={message.disliked} size={12} /> {message.dislikes}</button>
                    )}
                  </span>
                )}
              </div>
            </article>
          );
        })}
      </div>

      {error && <p className="error-text cr-error" role="alert">{error}</p>}

      <form className="cr-compose" onSubmit={send}>
        {replyTo && (
          <div className="cr-replying">
            <span><b>{replyTo.author}에게 답장</b><small>{replyTo.body}</small></span>
            <button type="button" aria-label="답장 취소" onClick={() => setReplyTo(null)}>✕</button>
          </div>
        )}
        {me.canWrite ? (
          <div className="cr-input">
            <label className="visually-hidden" htmlFor="chat-text">메시지</label>
            <input id="chat-text" value={text} maxLength={view.maxLength * 2} disabled={wait > 0 || sending}
              placeholder={wait > 0 ? `${wait}초 뒤에 보낼 수 있어요` : left < 0 ? `${-left}자 넘었어요` : '메시지 보내기'}
              onChange={event => setText(event.target.value)} />
            {wait > 0 ? (
              <span className="cr-timer" role="timer" aria-label={`${wait}초 남음`}>
                <svg width="42" height="42" viewBox="0 0 42 42" aria-hidden="true">
                  <circle cx="21" cy="21" r="18" />
                  <circle cx="21" cy="21" r="18" className="run"
                    strokeDasharray="113.1" strokeDashoffset={113.1 * (1 - wait / Math.max(1, view.cooldownSeconds))} />
                </svg>
                <b>{wait}</b>
              </span>
            ) : (
              <button type="submit" className="cr-send" aria-label="보내기" disabled={!text.trim() || sending || left < 0}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
                  strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
              </button>
            )}
          </div>
        ) : (
          <p className="cr-blocked">{me.blocked}</p>
        )}
      </form>

      {current && (
        <div className="sheet-scrim" role="presentation" onClick={() => setMenuFor(null)}>
          <section className="cr-sheet" role="dialog" aria-modal="true" aria-label="메시지 메뉴" onClick={e => e.stopPropagation()}>
            <span className="sheet-grip" aria-hidden="true" />
            <p className="cr-sheet-quote"><b>{current.mine ? '내 메시지' : current.author}</b> {current.body}</p>
            {!current.mine && (
              <div className="cr-sheet-reacts">
                <button type="button" className={current.liked ? 'on' : ''} aria-pressed={current.liked}
                  onClick={() => void like(current)}><ThumbUp filled={current.liked} size={20} /> 좋아요{current.likes > 0 ? ` ${current.likes}` : ''}</button>
                <button type="button" className={current.disliked ? 'on down' : 'down'} aria-pressed={current.disliked}
                  onClick={() => void dislike(current)}><ThumbDown filled={current.disliked} size={20} /> 싫어요{current.dislikes > 0 ? ` ${current.dislikes}` : ''}</button>
              </div>
            )}
            <div className="cr-sheet-actions">
              {me.canWrite && <button type="button" onClick={() => { setReplyTo(current); setMenuFor(null); }}>답장하기</button>}
              <button type="button" onClick={() => { void navigator.clipboard?.writeText(current.body); setMenuFor(null); }}>복사</button>
              {!current.mine && !current.staff && (
                <button type="button" className="danger" onClick={() => void report(current)}>신고하기</button>
              )}
            </div>
            <button type="button" className="cr-sheet-close" onClick={() => setMenuFor(null)}>닫기</button>
          </section>
        </div>
      )}
    </div>
  );
}
