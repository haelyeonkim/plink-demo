import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  dislikeChat, fetchChat, likeChat, reportChat, sendChat, type ChatMessage, type ChatView, type Reaction,
} from '../ticket/api';

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

const ThumbDownIcon = ({ filled }: { filled: boolean }) => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor"
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M17 3v11l-4 7a2 2 0 0 1-2-2v-4H5a2 2 0 0 1-2-2.3l1.2-7A2 2 0 0 1 6.2 3zM17 3h3v11h-3" />
  </svg>
);

const HeartIcon = ({ filled }: { filled: boolean }) => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor"
    strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z" />
  </svg>
);

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
  const [noticeOpen, setNoticeOpen] = useState(false);
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

  return (
    <div className="chat-room">
      <header className="chat-head">
        <h2>채팅</h2>
        <span className="chat-inside">장내 {view.inside.toLocaleString()}명</span>
        <span className="chat-me" title="채팅에서 보이는 내 이름">나: {me.nickname}</span>
      </header>

      {(view.notice || view.hot) && (
        <div className="chat-pins">
          {view.notice && (
            <button type="button" className={`chat-pin notice${noticeOpen ? ' open' : ''}`}
              aria-expanded={noticeOpen} onClick={() => setNoticeOpen(open => !open)}>
              <b>공지</b><span>{view.notice.body}</span>
            </button>
          )}
          {view.hot && (
            <a className="chat-pin hot" href={`#chat-${view.hot.id}`}>
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
                strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M12 3c1 4 5 5 5 10a5 5 0 0 1-10 0c0-2 1-3 2-4 0 2 1 3 2 3 0-3-1-5 1-9z" /></svg>
              <b>인기</b><span>{view.hot.body}</span><small>좋아요 {view.hot.likes}</small>
            </a>
          )}
        </div>
      )}

      <div className="chat-list" ref={list} aria-label="메시지" aria-live="polite"
        onScroll={event => {
          const box = event.currentTarget;
          stick.current = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
        }}>
        <p className="chat-day">입장한 관람객만 볼 수 있어요</p>
        {view.messages.length === 0 && <p className="console-none">아직 메시지가 없어요. 첫 인사를 남겨 보세요.</p>}
        {view.messages.map(message => (
          <article key={message.id} id={`chat-${message.id}`}
            className={`chat-line${message.mine ? ' mine' : ''}${message.staff ? ' staff' : ''}${view.hot?.id === message.id ? ' hot' : ''}`}>
            {!message.mine && (
              <span className="chat-author">{message.author}{message.staff && <em>운영</em>}</span>
            )}
            <div className="chat-bubble">
              {message.reply && <span className="chat-quote"><b>{message.reply.author}</b> {message.reply.body}</span>}
              <span>{message.body}</span>
            </div>
            <span className="chat-meta">
              <time dateTime={message.createdAt}>{clock(message.createdAt)}</time>
              {message.mine ? (
                // Their own line: what others thought of it, nothing to press.
                <>
                  {message.likes > 0 && <span className="chat-count">
                    <HeartIcon filled={false} /><span className="visually-hidden">좋아요</span> {message.likes}</span>}
                  {message.dislikes > 0 && <span className="chat-count">
                    <ThumbDownIcon filled={false} /><span className="visually-hidden">싫어요</span> {message.dislikes}</span>}
                </>
              ) : (<>
                <button type="button" className={`chat-like${message.liked ? ' on' : ''}`} aria-pressed={message.liked}
                  aria-label={`좋아요 ${message.likes}${message.liked ? ', 누름' : ''}`} onClick={() => void like(message)}>
                  <HeartIcon filled={message.liked} /> {message.likes > 0 ? message.likes : ''}
                </button>
                <button type="button" className={`chat-dislike${message.disliked ? ' on' : ''}`} aria-pressed={message.disliked}
                  aria-label={`싫어요 ${message.dislikes}${message.disliked ? ', 누름' : ''}`} onClick={() => void dislike(message)}>
                  <ThumbDownIcon filled={message.disliked} /> {message.dislikes > 0 ? message.dislikes : ''}
                </button>
              </>)}
              <button type="button" onClick={() => { setReplyTo(message); setMenuFor(null); }}>답장</button>
              {!message.mine && !message.staff && (
                <button type="button" aria-label={`${message.author}의 메시지 더보기`} onClick={() => setMenuFor(message)}>⋯</button>
              )}
            </span>
          </article>
        ))}
      </div>

      {error && <p className="error-text chat-error" role="alert">{error}</p>}

      <form className="chat-compose" onSubmit={send}>
        {replyTo && (
          <div className="chat-replying">
            <span><b>{replyTo.author}</b>에게 답장 · {replyTo.body}</span>
            <button type="button" aria-label="답장 취소" onClick={() => setReplyTo(null)}>✕</button>
          </div>
        )}
        {me.canWrite ? (
          <div className="chat-input">
            <label className="visually-hidden" htmlFor="chat-text">메시지</label>
            <input id="chat-text" value={text} maxLength={view.maxLength * 2} disabled={wait > 0 || sending}
              placeholder={wait > 0 ? `${wait}초 뒤에 보낼 수 있어요` : '메시지 보내기'}
              onChange={event => setText(event.target.value)} />
            {wait > 0 ? (
              <span className="chat-timer" role="timer" aria-label={`${wait}초 남음`}>
                <svg width="44" height="44" viewBox="0 0 44 44" aria-hidden="true">
                  <circle cx="22" cy="22" r="19" />
                  <circle cx="22" cy="22" r="19" className="run"
                    strokeDasharray="119.4" strokeDashoffset={119.4 * (1 - wait / Math.max(1, view.cooldownSeconds))} />
                </svg>
                <b>{wait}</b>
              </span>
            ) : (
              <button type="submit" className="chat-send" aria-label="보내기" disabled={!text.trim() || sending || left < 0}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
                  strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
              </button>
            )}
          </div>
        ) : (
          <p className="chat-blocked">{me.blocked}</p>
        )}
        <span className="chat-hint">
          {left < 0 ? `${-left}자 넘었어요 · ` : ''}
          {view.cooldownSeconds > 0 ? `${view.cooldownSeconds >= 60 ? `${Math.round(view.cooldownSeconds / 60)}분` : `${view.cooldownSeconds}초`}에 한 번 보낼 수 있어요` : ''}
          {` · 좋아요 ${view.hotLikes}개면 잠시 위에 고정돼요`}
        </span>
      </form>

      {menuFor && (
        <div className="sheet-scrim" role="presentation" onClick={() => setMenuFor(null)}>
          <div className="chat-sheet" role="dialog" aria-modal="true" aria-label="메시지 메뉴" onClick={e => e.stopPropagation()}>
            <p className="chat-sheet-quote"><b>{menuFor.author}</b> {menuFor.body}</p>
            <button type="button" onClick={() => { setReplyTo(menuFor); setMenuFor(null); }}>답장하기</button>
            <button type="button" onClick={() => { void navigator.clipboard?.writeText(menuFor.body); setMenuFor(null); }}>복사</button>
            <button type="button" className="danger" onClick={() => void report(menuFor)}>신고하기</button>
            <button type="button" className="quiet" onClick={() => setMenuFor(null)}>닫기</button>
          </div>
        </div>
      )}
    </div>
  );
}
