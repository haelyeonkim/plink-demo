import { useCallback, useEffect, useState } from 'react';
import { fetchCrowding, type Crowding as CrowdingData } from '../ticket/api';
import { openLive } from '../ticket/live';

const LABELS: Record<string, string> = {
  BUSY: '혼잡', STEADY: '보통', QUIET: '여유', EMPTY: '비어 있음',
};

/**
 * Live crowding by place, for the holder rather than the operator.
 *
 * <p>The numbers come from the gates: what walked into a zone minus what walked back
 * out. They describe a crowd, never a person. Where the organiser has said how many a
 * place holds, the bar is how full it is; where they have not, it is only how that place
 * compares with the busiest one, and the caption says which of the two you are reading.
 */
export default function Crowding({ sessionId, token }: { sessionId: string; token: string }) {
  const [data, setData] = useState<CrowdingData | null>(null);

  const load = useCallback(async () => {
    try { setData(await fetchCrowding(sessionId, token)); }
    catch { /* the ticket itself still works without this */ }
  }, [sessionId, token]);

  useEffect(() => {
    // Pushed on every scan; the interval below is the fallback, not the source.
    const live = openLive(`/ws/tickets/${sessionId}/${token}`, message => {
      if (message.type === 'CROWDING') setData(message as unknown as CrowdingData);
    });
    return () => live.close();
  }, [sessionId, token]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(); }, 60000);
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
                style={{ width: `${Math.min(100, Math.max(0, zone.percent))}%` }} />
            </div>
            {zone.capacity != null && (
              <span className="hint-text">정원 {zone.capacity}명 중 {zone.percent}%</span>
            )}
          </li>
        ))}
      </ul>
      <p className="hint-text center">
        게이트를 지날 때마다 실시간으로 갱신됩니다.
        {data.zones.some(zone => zone.basis === 'CAPACITY')
          ? ' 정원이 정해진 곳은 정원 대비, 그 밖은 가장 붐비는 곳 기준입니다.'
          : ' 막대는 가장 붐비는 곳 기준입니다.'}
      </p>
    </div>
  );
}
