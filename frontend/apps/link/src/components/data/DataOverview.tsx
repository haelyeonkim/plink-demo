import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { DataBar } from './DataLayout';
import { useEvents } from './useEvents';
import {
  clock, day, duration, eventDay, filterQuery, fromLocalInput, getJson, num, percent, toLocalInput, toOffsetIso,
  type Filters, type Overview,
} from './dataApi';

type Range = 'all' | 'day' | '7d' | '30d' | 'custom';
const RANGE_LABEL: Record<Range, string> = {
  all: '전체 기간', day: '행사 당일', '7d': '최근 7일', '30d': '최근 30일', custom: '직접 고르기',
};

type Bar = { at: string; entries: number; exits: number };

/** More than two days of hours is too many bars to read: they are added up by day. */
function bars(hourly: Bar[]): { rows: Bar[]; daily: boolean } {
  if (hourly.length <= 48) return { rows: hourly, daily: false };
  const byDay = new Map<string, Bar>();
  for (const row of hourly) {
    const key = new Date(row.at).toDateString();
    const current = byDay.get(key);
    if (current) { current.entries += row.entries; current.exits += row.exits; }
    else byDay.set(key, { ...row });
  }
  return { rows: [...byDay.values()], daily: true };
}

function HourlyChart({ hourly }: { hourly: Bar[] }) {
  const { rows, daily } = bars(hourly);
  const total = rows.reduce((sum, row) => sum + row.entries + row.exits, 0);
  if (rows.length === 0 || total === 0) {
    return <p className="data-empty">아직 입장 · 퇴장 기록이 없어요.</p>;
  }
  const max = Math.max(1, ...rows.map(row => Math.max(row.entries, row.exits)));
  const name = (row: Bar, first: boolean) => daily ? day(row.at)
    : `${new Date(row.at).getHours()}${first ? '시' : ''}`;
  const peak = rows.reduce((best, row) => row.entries > best.entries ? row : best, rows[0]);
  const spoken = (row: Bar) => daily ? day(row.at) : `${new Date(row.at).getHours()}시`;
  const summary = `${spoken(rows[0])}부터 ${spoken(rows[rows.length - 1])}까지 ${daily ? '날짜별' : '시간대별'} 입장과 퇴장 막대그래프. `
    + `입장은 ${spoken(peak)}에 가장 많아요(${num(peak.entries)}명).`;
  // A label under every bar would overlap; every few, and always the first.
  const step = Math.max(1, Math.ceil(rows.length / 12));
  const columns = { gridTemplateColumns: `repeat(${rows.length}, minmax(0, 1fr))` };
  return (
    <>
      <div className="hourly-bars" role="img" aria-label={summary} style={columns}>
        {rows.map(row => (
          <div key={row.at} className="hourly-pair"
            title={`${daily ? day(row.at) : `${day(row.at)} ${clock(row.at)}`} · 입장 ${num(row.entries)} · 퇴장 ${num(row.exits)}`}>
            <span className="bar-in" style={{ height: `${(row.entries / max) * 100}%` }} />
            <span className="bar-out" style={{ height: `${(row.exits / max) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="hourly-axis" aria-hidden="true" style={columns}>
        {rows.map((row, index) => <span key={row.at}>{index % step === 0 ? name(row, index === 0) : ''}</span>)}
      </div>
    </>
  );
}

/**
 * The data console's front page: what happened at an event, in the numbers an organiser
 * asks for first, and how much has been logged.
 */
export default function DataOverview() {
  const [params, setParams] = useSearchParams();
  const { events, eventsError } = useEvents();
  const sessionId = params.get('sessionId') || '';
  const rangeParam = params.get('range') as Range | null;
  const range: Range = rangeParam && rangeParam in RANGE_LABEL ? rangeParam : 'all';
  const event = events.find(row => String(row.id) === sessionId);
  const [data, setData] = useState<Overview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // The range as times: worked out on every load, so "최근 7일" stays the last seven days.
  const period = useMemo((): { from?: string; to?: string } => {
    if (range === 'custom') return { from: params.get('from') || undefined, to: params.get('to') || undefined };
    if (range === 'day') return event ? eventDay(event.startsAt) : {};
    if (range === '7d' || range === '30d') {
      const from = new Date();
      from.setDate(from.getDate() - (range === '7d' ? 7 : 30));
      return { from: toOffsetIso(from) };
    }
    return {};
  }, [range, params, event]);
  const filters: Filters = { sessionId: sessionId || undefined, ...period };
  const query = filterQuery(filters).toString();
  const waiting = range === 'day' && sessionId !== '' && !event && events.length === 0;

  useEffect(() => {
    if (waiting) return;
    const controller = new AbortController();
    setLoading(true); setError('');
    getJson<Overview>(`/api/admin/data/overview?${query}`, controller.signal)
      .then(setData)
      .catch(err => { if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '숫자를 불러오지 못했어요.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [query, waiting]);

  function change(patch: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value) next.set(key, value); else next.delete(key);
    }
    setParams(next, { replace: true });
  }

  const logsLink = (extra: Filters = {}) => `/admin/data/logs?${filterQuery({ ...filters, ...extra })}`;
  const k = data?.kpis;
  const topBooth = data && [...data.booths].sort((a, b) => b.redeemed - a.redeemed)[0];
  const funnel: Array<[string, number, boolean]> = k ? [
    ['발급', k.issued, false],
    ['링크 열어 봄', k.opened, false],
    ['등록 (패스키)', k.bound, false],
    ['입장', k.admittedPeople, false],
    ['부스 쿠폰 사용', k.couponsRedeemed, true],
  ] : [];
  const funnelMax = Math.max(1, ...funnel.map(([, value]) => value));
  const unbound = k ? Math.max(0, k.issued - k.bound) : 0;

  return (
    <>
      <DataBar>
        <label className="visually-hidden" htmlFor="overview-event">이벤트</label>
        <select id="overview-event" className="data-select" value={sessionId}
          onChange={e => change({
            sessionId: e.target.value || undefined,
            // 행사 당일 belongs to one event; with all of them there is no such day.
            ...(!e.target.value && range === 'day' ? { range: undefined } : {}),
          })}>
          <option value="">모든 이벤트</option>
          {events.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
        </select>
        <label className="visually-hidden" htmlFor="overview-range">기간</label>
        <select id="overview-range" className="data-select" value={range}
          onChange={e => {
            const next = e.target.value as Range;
            change({ range: next === 'all' ? undefined : next, from: undefined, to: undefined });
          }}>
          {(Object.keys(RANGE_LABEL) as Range[])
            .filter(key => key !== 'day' || event)
            .map(key => <option key={key} value={key}>
              {key === 'day' && event ? `행사 당일 · ${day(event.startsAt)}` : RANGE_LABEL[key]}
            </option>)}
        </select>
        {range === 'custom' && (
          <>
            <label className="visually-hidden" htmlFor="overview-from">시작</label>
            <input id="overview-from" className="data-select" type="datetime-local" value={toLocalInput(params.get('from') || undefined)}
              onChange={e => change({ from: fromLocalInput(e.target.value) })} />
            <label className="visually-hidden" htmlFor="overview-to">끝</label>
            <input id="overview-to" className="data-select" type="datetime-local" value={toLocalInput(params.get('to') || undefined)}
              onChange={e => change({ to: fromLocalInput(e.target.value) })} />
          </>
        )}
      </DataBar>

      {eventsError && <p className="error-text" role="alert">{eventsError}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}
      {loading && !data && <p className="loading">숫자를 모으고 있어요.</p>}

      {k && data && (
        <div className={`data-overview${loading ? ' is-loading' : ''}`} aria-busy={loading}>
          <section aria-label="핵심 지표" className="kpi-grid">
            <div className="kpi"><span>발급</span><b>{num(k.issued)}</b><small>초대로 발급 {num(k.invited)}</small></div>
            <div className="kpi"><span>등록</span><b>{num(k.bound)}</b><small>발급의 {percent(k.bound, k.issued)}</small></div>
            <div className="kpi"><span>입장한 사람</span><b>{num(k.admittedPeople)}</b><small>재입장 {num(k.reentries)}회</small></div>
            <div className="kpi"><span>최대 동시 체류</span><b>{num(k.peakInside)}</b>
              <small>{k.peakAt ? `${clock(k.peakAt)} 무렵` : '아직 기록이 없어요'}</small></div>
            <div className="kpi"><span>평균 체류</span><b>{duration(k.avgStayMinutes)}</b><small>첫 입장~마지막 퇴장</small></div>
            <div className="kpi"><span>쿠폰 사용</span><b>{num(k.couponsRedeemed)}</b>
              <small>{k.couponsIssued ? `발급된 쿠폰의 ${percent(k.couponsRedeemed, k.couponsIssued)}` : '발급된 쿠폰 없음'}</small></div>
          </section>

          <div className="data-row">
            <section aria-labelledby="overview-hourly" className="data-card data-card-wide">
              <div className="data-card-head">
                <h3 id="overview-hourly">시간대별 입장 · 퇴장</h3>
                <span className="chart-legend">
                  <span><i className="bar-in" />입장</span>
                  <span><i className="bar-out" />퇴장</span>
                </span>
              </div>
              <HourlyChart hourly={data.hourly} />
            </section>

            <section aria-labelledby="overview-funnel" className="data-card">
              <h3 id="overview-funnel">발급에서 입장까지</h3>
              {k.issued === 0 ? <p className="data-empty">아직 발급한 입장권이 없어요.</p> : (
                <>
                  <ol className="funnel">
                    {funnel.map(([label, value, soft]) => (
                      <li key={label}>
                        <span><span>{label}</span><b>{num(value)}</b></span>
                        <span className={`funnel-bar${soft ? ' soft' : ''}`} style={{ width: `${Math.max(2, (value / funnelMax) * 100)}%` }} />
                      </li>
                    ))}
                  </ol>
                  {(k.mailFailed > 0 || unbound > 0) && (
                    <p className="data-note">
                      {[k.mailFailed > 0 && `메일을 못 받은 ${num(k.mailFailed)}명`, unbound > 0 && `등록 안 한 ${num(unbound)}명`]
                        .filter(Boolean).join(' · ')}은{' '}
                      <Link to={logsLink({ categories: ['DELIVERY', 'TICKET'] })}>로그에서 보기</Link>
                    </p>
                  )}
                </>
              )}
            </section>
          </div>

          <div className="data-row">
            <section aria-labelledby="overview-gates" className="data-card">
              <h3 id="overview-gates">게이트별</h3>
              {data.gates.length === 0 ? <p className="data-empty">게이트에서 확인한 기록이 없어요.</p> : (
                <div className="data-table-scroll">
                  <table className="data-mini-table">
                    <thead><tr><th scope="col">게이트</th><th scope="col">입장</th><th scope="col">거절</th><th scope="col">얼굴</th></tr></thead>
                    <tbody>
                      {data.gates.map(gate => (
                        <tr key={gate.gateId}>
                          <td>{gate.name}</td>
                          <td>{num(gate.admitted)}</td>
                          <td className={gate.denied > 0 ? 'data-bad' : undefined}>{num(gate.denied)}</td>
                          <td>{Math.round(gate.faceShare * 100)}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            <section aria-labelledby="overview-booths" className="data-card">
              <h3 id="overview-booths">부스 · 커뮤니티</h3>
              <ul className="data-list">
                <li><span>쿠폰 사용 많은 부스</span>
                  <b>{topBooth && topBooth.redeemed > 0 ? `${topBooth.name} · ${num(topBooth.redeemed)}` : '아직 없어요'}</b></li>
                <li><span>채팅 메시지</span><b>{num(k.chatMessages)} · 신고 {num(k.chatReports)}</b></li>
                <li><span>홍보글 조회</span><b>{num(k.adViews)}</b></li>
                <li><span>초대 · 취소</span><b>{num(k.invited)} · {num(k.invitesCancelled)}</b></li>
              </ul>
            </section>

            <section aria-labelledby="overview-logs" className="data-card">
              <h3 id="overview-logs">쌓인 로그</h3>
              <ul className="data-list">
                <li><span>오늘</span><b>{num(data.logs.today)}건</b></li>
                <li><span>전체</span><b>{num(data.logs.total)}건</b></li>
                <li><span>오류</span>
                  {data.logs.errors > 0
                    ? <Link className="data-bad" to={logsLink({ categories: ['ERROR'] })}><b>{num(data.logs.errors)}건</b></Link>
                    : <b>0건</b>}</li>
                <li><span>보관</span><b>기한 없음 · 전체 원본</b></li>
              </ul>
              <Link className="data-more" to={logsLink()}>로그 살펴보기 ›</Link>
            </section>
          </div>
        </div>
      )}
    </>
  );
}
