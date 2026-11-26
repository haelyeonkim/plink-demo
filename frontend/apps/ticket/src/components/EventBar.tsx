import { Link } from 'react-router-dom';
import { useEffect, useRef, useState } from 'react';
import { clock, eventTime, eventWhen, type SessionRow } from '../console/model';
import type { LiveState } from '../ticket/live';
import type { ConsoleTab } from './EventNav';

const WHEN_LABEL = { today: '오늘 진행', upcoming: '예정', past: '종료' } as const;

/**
 * Where you are and what is happening, held in one place above every screen of an event.
 *
 * <p>It replaces three things that used to repeat: the event card in the sidebar, the line of
 * date and venue under each screen's title, and the "event list" link that went to the same
 * place as the site menu. Naming the event also switches it, so moving between events no
 * longer means going back to the list first. The one action an organiser takes most - issuing
 * a ticket - stays in reach on every screen.
 */
export default function EventBar({ session, sessions, tab, issued, inside, gatesUp, gatesAll, liveState }: {
  session: SessionRow; sessions: SessionRow[]; tab: ConsoleTab;
  issued: number; inside: number; gatesUp: number; gatesAll: number; liveState: LiveState;
}) {
  const when = eventWhen(session.startsAt, session.scheduleEndsAt);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => { setOpen(false); }, [session.id, tab]);
  useEffect(() => {
    if (!open) return;
    const onDown = (event: PointerEvent) => { if (!box.current?.contains(event.target as Node)) setOpen(false); };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    window.addEventListener('pointerdown', onDown);
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('pointerdown', onDown); window.removeEventListener('keydown', onKey); };
  }, [open]);

  const others = sessions.filter(s => s.id !== session.id);
  const gatesWarn = when === 'today' && gatesAll > 0 && gatesUp < gatesAll;
  return (
    <div className="event-bar" role="region" aria-label="이벤트">
      <div className="eb-switch" ref={box}>
        <button type="button" className="eb-switch-button" aria-haspopup="menu" aria-expanded={open}
          onClick={() => setOpen(value => !value)}>
          <span className={`pill pill-${when === 'today' ? 'ok' : when === 'past' ? 'out' : 'in'}`}>{WHEN_LABEL[when]}</span>
          <strong>{session.name}</strong>
          <svg className="eb-chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
        </button>
        {open && (
          <div className="eb-menu" role="menu">
            {others.length > 0 && <p className="eb-menu-label">다른 이벤트</p>}
            {others.map(other => (
              <Link key={other.id} role="menuitem" to={`/tickets/admin/${other.id}?tab=${tab}`}>
                <b>{other.name}</b>
                <span>{eventTime(other)}</span>
              </Link>
            ))}
            <div className="eb-menu-foot">
              <Link role="menuitem" to="/tickets">모든 이벤트</Link>
              <Link role="menuitem" to="/tickets/sessions/new">이벤트 추가</Link>
            </div>
          </div>
        )}
      </div>

      <p className="eb-meta">
        {eventTime(session)}
        {session.gateOpensAt && <span> 입장 {clock(session.gateOpensAt)}부터</span>}
        {session.venue && <span> {session.venue}</span>}
        {session.testEvent && <span className="eb-test">테스트</span>}
      </p>

      <dl className="eb-stats">
        <div><dt>발급</dt><dd>{issued.toLocaleString()}</dd></div>
        {when === 'today' && <div><dt>장내</dt><dd>{inside.toLocaleString()}</dd></div>}
        {gatesAll > 0 && (
          <div className={gatesWarn ? 'warn' : ''}><dt>게이트</dt><dd>{gatesUp}/{gatesAll}</dd></div>
        )}
      </dl>

      {when === 'today' && (
        <span className={`eb-live live-${liveState}`} role="status"
          title={liveState === 'open' ? '실시간으로 연결돼 있어요' : liveState === 'retrying' ? '연결이 끊겨 다시 연결하는 중이에요' : '연결하는 중이에요'}>
          {liveState === 'open' ? '실시간' : liveState === 'retrying' ? '재연결 중' : '연결 중'}
        </span>
      )}

      {tab !== 'issue' && when !== 'past' && (
        <Link className="btn-primary eb-issue" to="?tab=issue">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
            strokeLinecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          입장권 발급
        </Link>
      )}
    </div>
  );
}
