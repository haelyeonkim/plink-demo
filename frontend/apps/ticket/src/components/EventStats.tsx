import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { read, type SessionRow } from '../console/model';

/** One event's numbers, as /api/admin/sessions/{id}/stats answers. */
interface Stats {
  kpis: {
    issued: number; invited: number; bound: number; opened: number; admittedPeople: number; reentries: number;
    peakInside: number; peakAt: string | null; avgStayMinutes: number | null;
    couponsIssued: number; couponsRedeemed: number; chatMessages: number; chatReports: number; adViews: number;
    invitesCancelled: number; mailSent: number; mailFailed: number;
  };
  hourly: Bar[];
  gates: Array<{ gateId: number | string; name: string; admitted: number; denied: number; faceShare: number }>;
  booths: Array<{ boothId: number | string; name: string; redeemed: number }>;
}
type Bar = { at: string; entries: number; exits: number };

const num = (value: number) => value.toLocaleString('ko-KR');
const pad = (value: number) => String(value).padStart(2, '0');
const day = (iso: string) => { const d = new Date(iso); return `${d.getMonth() + 1}. ${d.getDate()}.`; };
const clock = (iso: string) => { const d = new Date(iso); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
const share = (part: number, whole: number) => whole > 0 ? `${Math.round((part / whole) * 100)}%` : '–';

function duration(minutes: number | null): string {
  if (minutes == null) return '–';
  const hours = Math.floor(minutes / 60), rest = Math.round(minutes % 60);
  if (hours === 0) return `${rest}분`;
  return rest === 0 ? `${hours}시간` : `${hours}시간 ${rest}분`;
}

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
  if (rows.every(row => row.entries + row.exits === 0)) {
    return <div className="console-empty"><p>게이트를 지난 사람이 생기면 시간대별로 여기에 쌓여요.</p></div>;
  }
  const max = Math.max(1, ...rows.map(row => Math.max(row.entries, row.exits)));
  const spoken = (row: Bar) => daily ? day(row.at) : `${new Date(row.at).getHours()}시`;
  const peak = rows.reduce((best, row) => row.entries > best.entries ? row : best, rows[0]);
  const summary = `${spoken(rows[0])}부터 ${spoken(rows[rows.length - 1])}까지 ${daily ? '날짜별' : '시간대별'} 입장과 퇴장 막대그래프. `
    + `입장은 ${spoken(peak)}에 가장 많아요(${num(peak.entries)}명).`;
  // A label under every bar would overlap; every few, and always the first.
  const step = Math.max(1, Math.ceil(rows.length / 12));
  // A bar no wider than a hand's breadth: one hour of data is a pair of bars, not a wall.
  const columns = { gridTemplateColumns: `repeat(${rows.length}, minmax(0, 56px))` };
  return (
    <>
      <div className="stats-bars" role="img" aria-label={summary} style={columns}>
        {rows.map(row => (
          <div key={row.at} className="stats-pair"
            title={`${daily ? day(row.at) : `${day(row.at)} ${clock(row.at)}`} · 입장 ${num(row.entries)} · 퇴장 ${num(row.exits)}`}>
            <span className="stats-in" style={{ height: `${(row.entries / max) * 100}%` }} />
            <span className="stats-out" style={{ height: `${(row.exits / max) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="stats-axis" aria-hidden="true" style={columns}>
        {rows.map((row, index) => <span key={row.at}>{index % step === 0 ? (daily ? day(row.at) : spoken(row)) : ''}</span>)}
      </div>
    </>
  );
}

/**
 * What came of an event, in the numbers an organiser asks for afterwards: how many of the
 * tickets were registered and used, when people came and went, and what the gates, booths
 * and the room saw. Counts only - nobody's contact.
 */
export default function EventStats({ session }: { session: SessionRow }) {
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let live = true;
    setLoading(true); setError('');
    fetch(`/api/admin/sessions/${session.id}/stats`)
      .then(read)
      .then(data => { if (live) setStats(data as Stats); })
      .catch(err => { if (live) setError(err instanceof Error ? err.message : '통계를 불러오지 못했어요.'); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [session.id]);

  if (error) return <p className="error-text" role="alert">{error}</p>;
  if (!stats) return <p className="console-none">{loading ? '숫자를 모으고 있어요.' : ''}</p>;

  const k = stats.kpis;
  const funnel: Array<[string, number]> = [
    ['발급', k.issued], ['링크 열어 봄', k.opened], ['등록 (패스키)', k.bound], ['입장', k.admittedPeople],
  ];
  const booths = [...stats.booths].sort((a, b) => b.redeemed - a.redeemed);
  const boothMax = Math.max(1, ...booths.map(b => b.redeemed));
  const unbound = Math.max(0, k.issued - k.bound);

  return (
    <div className="console-stack">
      <div className="kpi-grid stats-kpis">
        <div className="kpi">
          <span className="kpi-label">등록</span>
          <strong className="kpi-num">{num(k.bound)}<small> / {num(k.issued)}</small></strong>
          <span className="kpi-note">발급의 {share(k.bound, k.issued)}{k.invited > 0 ? ` · 초대로 발급 ${num(k.invited)}` : ''}</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">입장한 사람</span>
          <strong className="kpi-num">{num(k.admittedPeople)}</strong>
          <span className="kpi-note">{k.bound > 0 ? `등록의 ${share(k.admittedPeople, k.bound)} · ` : ''}재입장 {num(k.reentries)}회</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">최대 동시 체류</span>
          <strong className="kpi-num">{num(k.peakInside)}</strong>
          <span className="kpi-note">{k.peakAt ? `${day(k.peakAt)} ${clock(k.peakAt)} 무렵` : '아직 기록이 없어요'}</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">평균 체류</span>
          <strong className="kpi-num">{duration(k.avgStayMinutes)}</strong>
          <span className="kpi-note">입장부터 퇴장까지 한 번 머문 시간</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">쿠폰 사용</span>
          <strong className="kpi-num">{num(k.couponsRedeemed)}<small> / {num(k.couponsIssued)}</small></strong>
          <span className="kpi-note">{k.couponsIssued ? `발급한 쿠폰의 ${share(k.couponsRedeemed, k.couponsIssued)}` : '발급한 쿠폰 없음'}</span>
        </div>
        <div className={`kpi${k.mailFailed > 0 ? ' kpi-warn' : ''}`}>
          <span className="kpi-label">안내 메일</span>
          <strong className="kpi-num">{num(k.mailSent)}</strong>
          <span className="kpi-note">{k.mailFailed > 0 ? `${num(k.mailFailed)}건 보내지 못함` : '모두 보냄'}</span>
        </div>
      </div>

      <section className="console-card" aria-labelledby="stats-hourly">
        <div className="console-card-head">
          <h3 id="stats-hourly">시간대별 입장 · 퇴장</h3>
          <span className="stats-legend">
            <span><i className="stats-in" />입장</span>
            <span><i className="stats-out" />퇴장</span>
          </span>
        </div>
        <HourlyChart hourly={stats.hourly} />
      </section>

      <div className="console-two">
        <section className="console-card" aria-labelledby="stats-funnel">
          <h3 id="stats-funnel">발급에서 입장까지</h3>
          {k.issued === 0 ? (
            <div className="console-empty">
              <p>입장권을 발급하면 등록과 입장까지 몇 명이 이어졌는지 보여요.</p>
              <Link className="btn-secondary" to="?tab=issue">입장권 발급</Link>
            </div>
          ) : (
            <>
              {funnel.map(([label, value]) => (
                <div className="zone-meter" key={label}>
                  <div className="zone-meter-head"><b>{label}</b><span>{num(value)}명 <em className="level">{share(value, k.issued)}</em></span></div>
                  <span className="meter-track meter-lg"><span style={{ width: `${(value / k.issued) * 100}%` }} /></span>
                </div>
              ))}
              {unbound > 0 && (
                <p className="console-foot">등록하지 않은 입장권 {num(unbound)}장 · <Link to="?tab=tickets">발급 현황</Link></p>
              )}
            </>
          )}
        </section>

        <section className="console-card" aria-labelledby="stats-booths">
          <h3 id="stats-booths">부스 쿠폰 사용</h3>
          {booths.length === 0 ? (
            <div className="console-empty"><p>부스를 추가하면 부스마다 쿠폰이 얼마나 쓰였는지 보여요.</p></div>
          ) : booths.map(booth => (
            <div className="zone-meter" key={booth.boothId}>
              <div className="zone-meter-head"><b>{booth.name}</b><span>{num(booth.redeemed)}장</span></div>
              <span className="meter-track meter-lg"><span style={{ width: `${(booth.redeemed / boothMax) * 100}%` }} /></span>
            </div>
          ))}
        </section>
      </div>

      <div className="console-two">
        <section className="console-card" aria-labelledby="stats-gates">
          <h3 id="stats-gates">게이트별</h3>
          {stats.gates.length === 0 ? (
            <div className="console-empty"><p>게이트에서 확인한 기록이 생기면 게이트마다 보여요.</p></div>
          ) : (
            <div className="console-table-box flat">
              <table className="console-table">
                <thead><tr><th scope="col">게이트</th><th scope="col" className="num">입장</th>
                  <th scope="col" className="num">거절</th><th scope="col" className="num">얼굴</th></tr></thead>
                <tbody>
                  {stats.gates.map(gate => (
                    <tr key={gate.gateId}>
                      <td className="strong">{gate.name}</td>
                      <td className="num">{num(gate.admitted)}</td>
                      <td className={`num${gate.denied > 0 ? ' stats-bad' : ''}`}>{num(gate.denied)}</td>
                      <td className="num">{Math.round(gate.faceShare * 100)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="console-card" aria-labelledby="stats-room">
          <h3 id="stats-room">장내 소통</h3>
          <dl className="stats-list">
            <div><dt>채팅 메시지</dt><dd>{num(k.chatMessages)}<small> · 신고 {num(k.chatReports)}</small></dd></div>
            <div><dt>알림판 조회</dt><dd>{num(k.adViews)}</dd></div>
            <div><dt>초대 · 취소</dt><dd>{num(k.invited)}<small> · 취소 {num(k.invitesCancelled)}</small></dd></div>
          </dl>
        </section>
      </div>
    </div>
  );
}
