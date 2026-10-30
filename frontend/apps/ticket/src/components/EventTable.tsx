import { useState } from 'react';
import { Link } from 'react-router-dom';
import { clock, dayTime, eventWhen, percent, type SessionRow } from '../console/model';

type Period = 'all' | 'active' | 'past';

const WHEN_LABEL = { today: '오늘', upcoming: '예정', past: '종료' } as const;

/**
 * The events, as a table: one row each, the columns an organiser compares across events.
 * What is today or still ahead comes first, soonest at the top; what has run follows,
 * most recent first.
 */
export default function EventTable({ sessions, loading }: { sessions: SessionRow[]; loading: boolean }) {
  const [period, setPeriod] = useState<Period>('all');
  const [query, setQuery] = useState('');
  const [showTest, setShowTest] = useState(true);

  const rows = [...sessions]
    .map(row => ({ row, when: eventWhen(row.startsAt) }))
    .sort((a, b) => {
      const pa = a.when === 'past', pb = b.when === 'past';
      if (pa !== pb) return pa ? 1 : -1;
      const ta = new Date(a.row.startsAt).getTime(), tb = new Date(b.row.startsAt).getTime();
      return pa ? tb - ta : ta - tb;
    });
  const active = rows.filter(item => item.when !== 'past').length;
  const needle = query.trim().toLowerCase();
  const shown = rows
    .filter(item => period === 'all' || (period === 'past') === (item.when === 'past'))
    .filter(item => showTest || !item.row.testEvent)
    .filter(item => !needle || item.row.name.toLowerCase().includes(needle)
      || (item.row.venue ?? '').toLowerCase().includes(needle));
  const periods: Array<[Period, string, number]> = [
    ['all', '전체', rows.length], ['active', '오늘·예정', active], ['past', '종료', rows.length - active],
  ];

  if (!loading && sessions.length === 0) {
    return (
      <div className="console-empty">
        <h3>첫 이벤트를 만들어 보세요</h3>
        <ol>
          <li><b>이벤트 만들기</b> 일시와 장소를 정해요.</li>
          <li><b>입장권 발급</b> 명단을 올리면 한 사람씩 링크가 나가요.</li>
          <li><b>게이트 연결</b> 태블릿을 연결하고 입장을 확인해요.</li>
        </ol>
        <Link className="btn-primary" to="/tickets/sessions/new">이벤트 만들기</Link>
      </div>
    );
  }

  return (
    <>
      <div className="console-toolbar">
        <fieldset className="segmented">
          <legend className="visually-hidden">기간</legend>
          {periods.map(([key, label, n]) => (
            <label key={key} className={period === key ? 'on' : ''}>
              <input type="radio" name="event-period" value={key} checked={period === key}
                onChange={() => setPeriod(key)} />
              {label} <span>{n}</span>
            </label>
          ))}
        </fieldset>
        <label className="console-search">
          <span className="visually-hidden">이벤트 검색</span>
          <input type="search" placeholder="이벤트명·장소 검색" value={query}
            onChange={event => setQuery(event.target.value)} />
        </label>
        <label className="console-check">
          <input type="checkbox" checked={showTest} onChange={event => setShowTest(event.target.checked)} />
          테스트 이벤트 보기
        </label>
      </div>

      <div className="console-table-box">
        <table className="console-table event-table">
          <thead>
            <tr>
              <th>상태</th><th>이벤트</th><th>일시</th><th>입장 시작</th><th>장소</th>
              <th className="num">발급</th><th>등록</th><th className="num">장내</th>
            </tr>
          </thead>
          <tbody>
            {shown.map(({ row, when }) => {
              const issued = row.ticketCount ?? 0;
              const bound = row.boundCount ?? 0;
              const rate = percent(bound, issued);
              return (
                <tr key={row.id} className={when === 'past' ? 'is-past' : ''}>
                  <td><span className={`pill pill-${when === 'today' ? 'ok' : when === 'past' ? 'out' : 'in'}`}>
                    {WHEN_LABEL[when]}</span></td>
                  <td className="event-name">
                    <Link to={`/tickets/admin/${row.id}`}>{row.name}</Link>
                    {row.testEvent && <span className="pill pill-warn">테스트</span>}
                  </td>
                  <td>{dayTime(row.startsAt)}</td>
                  <td className="muted">{row.gateOpensAt ? clock(row.gateOpensAt) : '–'}</td>
                  <td className="muted">{row.venue || '–'}</td>
                  <td className="num strong">{issued.toLocaleString()}</td>
                  <td>
                    <div className="meter" title={`등록 ${bound.toLocaleString()} / ${issued.toLocaleString()}`}>
                      <span className="meter-track"><span style={{ width: `${rate}%` }} /></span>
                      <span className="meter-value">{issued > 0 ? `${rate}%` : '–'}</span>
                    </div>
                  </td>
                  <td className="num">{when === 'today' ? (row.insideCount ?? 0).toLocaleString() : '–'}</td>
                </tr>
              );
            })}
            {shown.length === 0 && (
              <tr><td colSpan={8} className="console-none">
                {loading ? '이벤트를 불러오고 있어요.' : '조건에 맞는 이벤트가 없어요.'}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="console-foot">이벤트 이름을 누르면 들어갑니다. 오늘·예정 이벤트가 위에, 종료된 이벤트는 아래에 놓입니다.</p>
    </>
  );
}
