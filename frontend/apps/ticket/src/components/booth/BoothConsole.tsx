import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../ticket/api';
import { fetchDashboard, type BoothClient, type BoothDashboard } from '../../booth/api';
import BoothOverview from './BoothOverview';
import BoothOffers from './BoothOffers';
import BoothIssue from './BoothIssue';
import BoothScan from './BoothScan';
import BoothCoupons from './BoothCoupons';
import BoothBoard from './BoothBoard';

export type BoothTab = 'overview' | 'offers' | 'issue' | 'scan' | 'coupons' | 'board';

/** A manager opens on the booth at a glance; a terminal opens on its camera. */
export const MANAGER_TABS: BoothTab[] = ['overview', 'offers', 'issue', 'scan', 'coupons', 'board'];
export const TERMINAL_TABS: BoothTab[] = ['scan', 'overview', 'offers', 'issue', 'coupons', 'board'];

const TAB: Record<BoothTab, [string, string]> = {
  overview: ['현황', 'M4 20V10M10 20V4M16 20v-7M22 20H2'],
  offers: ['쿠폰 종류', 'M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8zM7.5 6a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z'],
  issue: ['발급', 'M22 2 11 13M22 2l-7 20-4-9-9-4z'],
  scan: ['스캔', 'M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3M7 12h10'],
  coupons: ['쿠폰 목록', 'M9 6h12M9 12h12M9 18h12M4 6h.01M4 12h.01M4 18h.01'],
  board: ['알림판', 'M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1zM15 9a3 3 0 0 1 0 6M18 6a7 7 0 0 1 0 12'],
};

/**
 * The console's tabs, along the bottom where a thumb already is - the same bar as the
 * holder's ticket. Dark over the terminal's camera, light over a page.
 */
export function BoothTabBar({ tabs, tab, onTab, dark = false, disabled = false }: {
  tabs: BoothTab[]; tab: BoothTab; onTab: (tab: BoothTab) => void; dark?: boolean; disabled?: boolean;
}) {
  return (
    <nav className={`booth-tabs${dark ? ' booth-tabs-dark' : ''}`} aria-label="부스 관리"
      style={{ '--tabs': tabs.length } as React.CSSProperties}>
      {tabs.map(key => {
        const [label, icon] = TAB[key];
        return (
          <button key={key} type="button" aria-current={tab === key ? 'page' : undefined}
            className={tab === key ? 'on' : ''} disabled={disabled} onClick={() => { if (key !== tab) onTab(key); }}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={icon} /></svg>
            <span>{label}</span>
          </button>
        );
      })}
    </nav>
  );
}

/**
 * One booth's console: what it has given, its kinds of coupon, giving many at once, and
 * the coupons themselves. The manager's phone and the booth's terminal show the same
 * screens; the client says which of them is asking.
 *
 * <p>A lapsed sign-in, a revoked manager or a terminal that is no longer trusted is not an
 * error to show under a button: the page around the console changes, so onLost decides.
 */
export default function BoothConsole({ client, tabs, tab, onTab, onLost, onNotice, onError, className = '' }: {
  client: BoothClient; tabs: BoothTab[]; tab: BoothTab; onTab: (tab: BoothTab) => void;
  onLost: (err: ApiError) => void; onNotice: (text: string) => void; onError: (text: string) => void;
  className?: string;
}) {
  const [dashboard, setDashboard] = useState<BoothDashboard | null>(null);
  const [failed, setFailed] = useState(false);

  const fail = useCallback((err: unknown) => {
    if (err instanceof ApiError && (err.status === 401 || err.status === 403 || err.status === 410)) {
      onLost(err);
      return;
    }
    onError(err instanceof Error && err.message ? err.message : '요청을 처리하지 못했어요. 다시 시도해 주세요.');
  }, [onLost, onError]);

  const refresh = useCallback(async () => {
    try { setDashboard(await fetchDashboard(client)); setFailed(false); }
    catch (err) { setFailed(true); fail(err); }
  }, [client, fail]);
  useEffect(() => { void refresh(); }, [refresh]);

  const notify = useCallback((text: string) => { onError(''); onNotice(text); }, [onError, onNotice]);

  const bar = <BoothTabBar tabs={tabs} tab={tab} onTab={onTab} />;
  if (!dashboard) {
    return (
      <section className={`page-section page-tight booth-manager bm-console${className ? ` ${className}` : ''}`}>
        {failed ? (
          <div className="bm-stack">
            <p className="hint-text center">부스 정보를 불러오지 못했어요.</p>
            <button type="button" className="btn-secondary" onClick={() => void refresh()}>다시 불러오기</button>
          </div>
        ) : <p className="hint-text center">불러오는 중…</p>}
        {bar}
      </section>
    );
  }
  const props = { client, dashboard, refresh, fail, notify };
  return (
    <section className={`page-section booth-manager bm-console${className ? ` ${className}` : ''}`}>
      <header className="bm-head">
        <p className="eyebrow"><span></span> {dashboard.event.name}</p>
        <h2>{dashboard.booth.name}</h2>
        {dashboard.booth.note && <p className="muted bm-sub">{dashboard.booth.note}</p>}
      </header>
      {/* Only the open tab is mounted: leaving 스캔 is what turns the camera off. */}
      <div className="bm-panel">
        {tab === 'overview' && <BoothOverview {...props} onGo={onTab} />}
        {tab === 'offers' && <BoothOffers {...props} />}
        {tab === 'issue' && <BoothIssue {...props} />}
        {tab === 'scan' && <BoothScan {...props} />}
        {tab === 'coupons' && <BoothCoupons {...props} />}
        {tab === 'board' && <BoothBoard {...props} />}
      </div>
      {bar}
    </section>
  );
}
