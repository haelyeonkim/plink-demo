import ConsoleNav, { NAV_ICONS, type ConsoleNavEntry } from '@shared/components/ConsoleNav';
import type { LinkDetail } from '../types';
import { STATE_LABEL, linkState } from './LinkTable';

export type LinkTab = 'overview' | 'links' | 'issue' | 'views' | 'settings';

/** A link's own menu, laid out like an event's in the ticket console. */
export default function LinkNav({ detail, tab }: { detail: LinkDetail; tab: LinkTab }) {
  const state = linkState(detail);
  const issued = detail.recipients.length;
  const groups: Array<[string, ConsoleNavEntry<LinkTab>[]]> = [
    ['', [{ key: 'overview', label: '개요', icon: NAV_ICONS.overview }]],
    ['수신자', [
      { key: 'links', label: '발급 현황', icon: NAV_ICONS.list, badge: issued.toLocaleString(), badgeLabel: `발급 ${issued}개` },
      { key: 'issue', label: '발급', icon: NAV_ICONS.add },
    ]],
    ['기록', [
      { key: 'views', label: '열람 기록', icon: NAV_ICONS.views,
        badge: detail.viewCount.toLocaleString(), badgeLabel: `열람 ${detail.viewCount}회` },
    ]],
    ['설정', [{ key: 'settings', label: '링크 설정', icon: NAV_ICONS.settings }]],
  ];
  return (
    <ConsoleNav label="링크 메뉴" back={{ to: '/links', label: '링크 목록' }} current={tab} groups={groups}
      card={<>
        <span className={`pill pill-${state === 'active' ? 'ok' : 'out'}`}>{STATE_LABEL[state]}</span>
        <strong>{detail.title || detail.contentTitle || detail.originalUrl}</strong>
        <span className="event-nav-target">{detail.contentId ? `컨텐츠 · ${detail.contentTitle}` : detail.originalUrl}</span>
      </>} />
  );
}
