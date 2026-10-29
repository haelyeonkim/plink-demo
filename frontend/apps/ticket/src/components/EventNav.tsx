import { Link } from 'react-router-dom';
import { dayTime, eventWhen, type SessionRow } from '../console/model';

export type ConsoleTab = 'overview' | 'tickets' | 'issue' | 'movements' | 'gates' | 'coupons' | 'settings';

/** One stroke drawing per menu entry, so the menu reads by shape as well as by word. */
const ICONS: Record<ConsoleTab, string> = {
  overview: 'M4 13h6V4H4zM14 20h6v-9h-6zM14 4v4h6V4zM4 20h6v-4H4z',
  tickets: 'M4 6h16M4 12h16M4 18h10',
  issue: 'M12 5v14M5 12h14',
  movements: 'M7 7h11l-3-3M17 17H6l3 3',
  gates: 'M5 4h14v16H5zM9 9h6v6H9z',
  coupons: 'M4 8a2 2 0 0 0 0 4v4h16v-4a2 2 0 0 1 0-4V4H4zM12 4v12',
  settings: 'M4 7h10M18 7h2M4 17h4M12 17h8M14 4v6M8 14v6',
};

interface Entry { key: ConsoleTab; label: string; badge?: string; badgeLabel?: string; warn?: boolean }

const WHEN_LABEL = { today: '오늘 진행', upcoming: '예정', past: '종료' } as const;

/**
 * An event's own menu, down the left of its console. On a narrow screen it becomes a
 * row across the top that scrolls sideways, so the content is not pushed below it.
 */
export default function EventNav({ session, tab, issued, inside, gatesUp, gatesAll }: {
  session: SessionRow; tab: ConsoleTab;
  issued: number; inside: number; gatesUp: number; gatesAll: number;
}) {
  const when = eventWhen(session.startsAt);
  const groups: Array<[string, Entry[]]> = [
    ['', [{ key: 'overview', label: '개요' }]],
    ['입장권', [
      { key: 'tickets', label: '발급 현황', badge: issued.toLocaleString(), badgeLabel: `발급 ${issued}장` },
      { key: 'issue', label: '발급' },
    ]],
    ['현장 운영', [
      { key: 'movements', label: '실시간 입·퇴장',
        ...(when === 'today' ? { badge: inside.toLocaleString(), badgeLabel: `지금 장내 ${inside}명` } : {}) },
      { key: 'gates', label: '게이트',
        ...(gatesAll > 0 ? { badge: `${gatesUp}/${gatesAll}`, badgeLabel: `게이트 ${gatesAll}곳 중 ${gatesUp}곳 연결`,
          warn: gatesUp < gatesAll } : {}) },
      { key: 'coupons', label: '쿠폰' },
    ]],
    ['설정', [{ key: 'settings', label: '행사 설정' }]],
  ];
  return (
    <nav className="event-nav" aria-label="행사 메뉴">
      <Link className="event-nav-back" to="/tickets">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
          strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 18l-6-6 6-6" /></svg>
        행사 목록
      </Link>
      <div className="event-nav-card">
        <span className={`pill pill-${when === 'today' ? 'ok' : when === 'past' ? 'out' : 'in'}`}>
          {WHEN_LABEL[when]}</span>
        <strong>{session.name}</strong>
        <span>{dayTime(session.startsAt)}{session.venue ? <><br />{session.venue}</> : null}</span>
      </div>
      {groups.map(([label, entries]) => (
        <div className="event-nav-group" key={label || 'top'}>
          {label && <span className="event-nav-label">{label}</span>}
          {entries.map(entry => (
            <Link key={entry.key} to={`?tab=${entry.key}`} replace={false}
              className={tab === entry.key ? 'on' : ''} aria-current={tab === entry.key ? 'page' : undefined}>
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"
                strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={ICONS[entry.key]} /></svg>
              <span className="event-nav-text">{entry.label}</span>
              {entry.badge && (
                <span className={`event-nav-badge${entry.warn ? ' warn' : ''}`} aria-label={entry.badgeLabel}>
                  {entry.badge}</span>
              )}
            </Link>
          ))}
        </div>
      ))}
    </nav>
  );
}
