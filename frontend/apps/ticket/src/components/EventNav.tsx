import ConsoleNav, { NAV_ICONS, type ConsoleNavEntry } from '@shared/components/ConsoleNav';
import { dayTime, eventWhen, type SessionRow } from '../console/model';

export type ConsoleTab = 'overview' | 'tickets' | 'issue' | 'movements' | 'gates' | 'coupons' | 'chat' | 'ads' | 'settings';

const WHEN_LABEL = { today: '오늘 진행', upcoming: '예정', past: '종료' } as const;

/** An event's own menu, down the left of its console. */
export default function EventNav({ session, tab, issued, inside, gatesUp, gatesAll }: {
  session: SessionRow; tab: ConsoleTab;
  issued: number; inside: number; gatesUp: number; gatesAll: number;
}) {
  const when = eventWhen(session.startsAt);
  const groups: Array<[string, ConsoleNavEntry<ConsoleTab>[]]> = [
    ['', [{ key: 'overview', label: '개요', icon: NAV_ICONS.overview }]],
    ['입장권', [
      { key: 'tickets', label: '발급 현황', icon: NAV_ICONS.list, badge: issued.toLocaleString(), badgeLabel: `발급 ${issued}장` },
      { key: 'issue', label: '발급', icon: NAV_ICONS.add },
    ]],
    ['현장 운영', [
      { key: 'movements', label: '실시간 입·퇴장', icon: NAV_ICONS.movements,
        ...(when === 'today' ? { badge: inside.toLocaleString(), badgeLabel: `지금 장내 ${inside}명` } : {}) },
      { key: 'gates', label: '게이트', icon: NAV_ICONS.gates,
        ...(gatesAll > 0 ? { badge: `${gatesUp}/${gatesAll}`, badgeLabel: `게이트 ${gatesAll}곳 중 ${gatesUp}곳 연결`,
          warn: gatesUp < gatesAll } : {}) },
      { key: 'coupons', label: '쿠폰', icon: NAV_ICONS.coupons },
    ]],
    ['장내 소통', [
      { key: 'chat', label: '장내 채팅', icon: NAV_ICONS.chat },
      { key: 'ads', label: '광고판', icon: NAV_ICONS.ads },
    ]],
    ['설정', [{ key: 'settings', label: '행사 설정', icon: NAV_ICONS.settings }]],
  ];
  return (
    <ConsoleNav label="행사 메뉴" back={{ to: '/tickets', label: '행사 목록' }} current={tab} groups={groups}
      card={<>
        <span className={`pill pill-${when === 'today' ? 'ok' : when === 'past' ? 'out' : 'in'}`}>{WHEN_LABEL[when]}</span>
        <strong>{session.name}</strong>
        <span>{dayTime(session.startsAt)}{session.venue ? <><br />{session.venue}</> : null}</span>
      </>} />
  );
}
