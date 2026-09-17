import { useCallback, useEffect, useState } from 'react';
import { fetchCrowding, type Crowding as CrowdingData } from '../ticket/api';

const LABELS: Record<string, string> = {
  BUSY: '혼잡', STEADY: '보통', QUIET: '여유', EMPTY: '비어 있음',
};

/**
 * Live crowding by place, for the holder rather than the operator.
 *
 * <p>The numbers come from the gates: what walked into a zone minus what walked back
 * out. They describe a crowd, never a person. The bar is relative to the busiest place
 * right now, because "worse than the lobby?" is the question being asked.
 */
export default function Crowding({ sessionId, token }: { sessionId: string; token: string }) {
  const [data, setData] = useState<CrowdingData | null>(null);

  const load = useCallback(async () => {
    try { setData(await fetchCrowding(sessionId, token)); }
    catch { /* the ticket itself still works without this */ }
  }, [sessionId, token]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(); }, 30000);
    // Coming back to the screen should show now, not thirty seconds ago.
    const onVisible = () => { if (document.visibilityState === 'visible') void load(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [load]);

  if (!data || data.zones.length === 0) return null;

  return (
    <div className="access-card crowding-card">
      <p className="eyebrow center"><span></span> 지금 붐비는 정도</p>
      <ul className="crowding-list">
        {data.zones.map(zone => (
          <li key={zone.zone}>
            <div className="crowding-head">
              <b>{zone.zone}</b>
              <span className={`pill pill-${zone.level === 'BUSY' ? 'off'
                : zone.level === 'STEADY' ? 'warn' : 'ok'}`}>
                {LABELS[zone.level] ?? zone.level}
              </span>
              <strong>{zone.inside}명</strong>
            </div>
            <div className="zone-bar" aria-hidden="true">
              <span className={`level-${zone.level.toLowerCase()}`}
                style={{ width: `${Math.round(zone.share * 100)}%` }} />
            </div>
          </li>
        ))}
      </ul>
      <p className="hint-text center">
        게이트 통과 기록으로 집계하며 30초마다 갱신됩니다. 막대는 가장 붐비는 곳 기준입니다.
      </p>
    </div>
  );
}
