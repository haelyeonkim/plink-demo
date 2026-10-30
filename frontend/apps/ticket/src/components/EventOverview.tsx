import { Link } from 'react-router-dom';
import {
  OUTCOMES, clock, count, eventWhen, hourLabel, percent, text, validity,
  type GateRow, type Occupancy, type SessionRow, type TicketSummary,
} from '../console/model';

/** How full a place is, in the event's own thresholds; null when it has no capacity. */
export function crowdLevel(session: SessionRow, inside: number, capacity: number | undefined) {
  if (!capacity) return null;
  const pct = percent(inside, capacity);
  if (pct >= session.crowdBusyPercent) return { pct, label: '붐빔', tone: 'busy' };
  if (pct >= session.crowdSteadyPercent) return { pct, label: '보통', tone: 'steady' };
  return { pct, label: '여유', tone: 'calm' };
}

interface Todo { tone: 'warn' | 'info' | 'done'; title: string; detail: string; to: string; action: string }

/**
 * The first screen of an event. On the day it answers "how is it going"; before the day,
 * "what is left to get ready". Each line that needs doing links to the screen that does it.
 */
export default function EventOverview({ session, summary, occupancy, gates }: {
  session: SessionRow; summary: TicketSummary | null; occupancy: Occupancy | null; gates: GateRow[];
}) {
  const when = eventWhen(session.startsAt);
  const counts = summary?.counts ?? { all: 0, unclaimed: 0, bound: 0, inside: 0, revoked: 0, live: 0 };
  const doors = gates.filter(gate => gate.role !== 'BOOTH');
  const terminals = gates.filter(gate => gate.role === 'BOOTH');
  const connected = doors.filter(gate => gate.boundDevice);
  const offline = doors.filter(gate => !gate.boundDevice);
  const offlineTerminals = terminals.filter(gate => !gate.boundDevice);
  const expiring = gates.filter(gate => ['warn', 'off'].includes(validity(gate).tone));
  const capacity = Object.values(session.zoneCapacity ?? {}).reduce((sum, n) => sum + n, 0);
  const inside = occupancy?.inside ?? counts.inside;
  const name = (gate: GateRow) => gate.label || gate.gateId;

  const todos: Todo[] = [];
  if (counts.all === 0) {
    todos.push({ tone: 'warn', title: '아직 발급한 입장권이 없어요', detail: '명단을 올리면 한 사람씩 링크가 나가요.',
      to: '?tab=issue', action: '발급하기' });
  }
  if (doors.length === 0) {
    todos.push({ tone: 'warn', title: '입장 게이트가 없어요', detail: '게이트를 추가하고 태블릿을 연결하세요.',
      to: '?tab=gates', action: '게이트 추가' });
  }
  if (offline.length > 0) {
    todos.push({ tone: 'warn', title: `게이트 ${offline.length}곳이 연결되지 않았어요`,
      detail: `${offline.slice(0, 3).map(name).join(', ')}${offline.length > 3 ? ' 외' : ''} · 설정 링크를 태블릿에서 열어 주세요`,
      to: '?tab=gates', action: '게이트로' });
  }
  if (offlineTerminals.length > 0) {
    todos.push({ tone: 'warn', title: `부스 단말 ${offlineTerminals.length}대가 연결되지 않았어요`,
      detail: `${offlineTerminals.slice(0, 3).map(name).join(', ')}${offlineTerminals.length > 3 ? ' 외' : ''} · 설정 링크를 태블릿에서 열어 주세요`,
      to: '?tab=booths', action: '부스로' });
  }
  if (expiring.length > 0) {
    todos.push({ tone: 'warn', title: `유효기간이 곧 끝나는 단말 ${expiring.length}대`,
      detail: `${expiring.slice(0, 3).map(name).join(', ')} · 끝나면 스캔이 멈춰요`,
      to: expiring.every(gate => gate.role === 'BOOTH') ? '?tab=booths' : '?tab=gates', action: '유효기간 연장' });
  }
  if (counts.unclaimed > 0 && when !== 'past') {
    todos.push({ tone: 'info', title: `등록하지 않은 입장권 ${counts.unclaimed.toLocaleString()}장`,
      detail: '안내 메일을 다시 보내거나 링크를 직접 전해 주세요.', to: '?tab=tickets', action: '발급 현황' });
  }
  if (when === 'upcoming') {
    if (counts.all > 0) todos.push({ tone: 'done', title: `입장권 ${counts.all.toLocaleString()}장 발급`,
      detail: `등록 완료 ${counts.bound.toLocaleString()}장`, to: '?tab=tickets', action: '발급 현황' });
    if (doors.length > 0 && offline.length === 0) todos.push({ tone: 'done', title: `게이트 ${doors.length}곳 모두 연결`,
      detail: '이벤트 당일 입장을 받을 준비가 됐어요.', to: '?tab=gates', action: '게이트' });
  }

  const policy = [
    session.reentryMode === 'DISABLED' ? '재입장 불가'
      : session.reentryMode === 'LIMITED' ? `재입장 최대 ${session.reentryMax}회` : '재입장 허용',
    session.dayCloseExit ? `${hourLabel(session.dayCloseHour)}에 자동 퇴장` : null,
    session.exitScanRequired ? '퇴장 스캔 필수' : null,
  ].filter(Boolean).join(' · ');

  const zones = (occupancy?.byZone ?? []).map(row => {
    const now = Math.max(0, count(row.admitted) - count(row.exited));
    const zone = text(row.zone);
    return { zone, now, cap: session.zoneCapacity?.[zone], level: crowdLevel(session, now, session.zoneCapacity?.[zone]) };
  });
  const busiest = Math.max(1, ...zones.map(z => z.now));
  // Who is inside but came in without a gate - let in by hand - has no place to count in.
  const unplaced = Math.max(0, (occupancy?.inside ?? 0) - zones.reduce((sum, z) => sum + z.now, 0));

  return (
    <div className="console-stack">
      <div className="kpi-grid">
        <div className="kpi">
          <span className="kpi-label">발급</span>
          <strong className="kpi-num">{counts.all.toLocaleString()}</strong>
          <span className="kpi-note">비활성 {counts.revoked.toLocaleString()}장 포함</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">등록 완료</span>
          <strong className="kpi-num">{counts.bound.toLocaleString()}</strong>
          <span className="meter-track"><span style={{ width: `${percent(counts.bound, counts.all)}%` }} /></span>
          <span className="kpi-note">{percent(counts.bound, counts.all)}% · 미등록 {counts.unclaimed.toLocaleString()}</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">지금 장내</span>
          <strong className="kpi-num">{inside.toLocaleString()}
            {capacity > 0 && <small> / {capacity.toLocaleString()}</small>}</strong>
          <span className="kpi-note">{when === 'today' ? '실시간' : when === 'upcoming' ? '이벤트 당일부터 집계' : '이벤트 종료'}</span>
        </div>
        <div className={`kpi${offline.length > 0 ? ' kpi-warn' : ''}`}>
          <span className="kpi-label">게이트 연결</span>
          <strong className="kpi-num">{connected.length}<small> / {doors.length}</small></strong>
          <span className="kpi-note">{doors.length === 0 ? '게이트 없음'
            : offline.length > 0 ? `${offline.length}곳 연결 안 됨` : '모두 연결됨'}</span>
        </div>
      </div>

      <div className="console-two">
        <section className="console-card" aria-labelledby="overview-todo">
          <h3 id="overview-todo">{when === 'upcoming' ? '이벤트 준비' : '확인할 일'}</h3>
          {todos.length === 0 ? (
            <p className="console-none">지금 확인할 일이 없어요.</p>
          ) : (
            <ul className="todo-list">
              {todos.map(todo => (
                <li key={todo.title} className={`todo todo-${todo.tone}`}>
                  <span className="todo-mark" aria-hidden="true">{todo.tone === 'done' ? '✓' : todo.tone === 'warn' ? '!' : 'i'}</span>
                  <span className="todo-text">
                    <span className="visually-hidden">{todo.tone === 'done' ? '완료: ' : todo.tone === 'warn' ? '확인 필요: ' : '안내: '}</span>
                    <b>{todo.title}</b><small>{todo.detail}</small>
                  </span>
                  <Link to={todo.to}>{todo.action}</Link>
                </li>
              ))}
            </ul>
          )}
          <p className="console-foot">적용 중인 입장 정책: {policy} · <Link to="?tab=settings">이벤트 설정</Link></p>
        </section>

        <section className="console-card" aria-labelledby="overview-zones">
          <div className="console-card-head">
            <h3 id="overview-zones">장소별 혼잡도</h3>
            <Link to="?tab=movements">자세히</Link>
          </div>
          {zones.length === 0 && unplaced === 0 ? (
            <p className="console-none">게이트에 장소를 정하면 여기에 장소별 인원이 보여요.</p>
          ) : zones.map(z => (
            <div className="zone-meter" key={z.zone}>
              <div className="zone-meter-head">
                <b>{z.zone}</b>
                <span>{z.now.toLocaleString()}{z.cap ? ` / ${z.cap.toLocaleString()}` : '명'}
                  {z.level && <em className={`level level-${z.level.tone}`}>{z.level.label}</em>}</span>
              </div>
              <span className={`meter-track meter-lg level-${z.level?.tone ?? 'none'}`}>
                <span style={{ width: `${z.level ? Math.min(100, z.level.pct) : percent(z.now, busiest)}%` }} />
              </span>
            </div>
          ))}
          {unplaced > 0 && (
            <div className="zone-meter unplaced">
              <div className="zone-meter-head">
                <b>장소 미지정</b>
                <span>{unplaced.toLocaleString()}명</span>
              </div>
              <span className="meter-track meter-lg level-none"><span style={{ width: `${percent(unplaced, Math.max(busiest, unplaced))}%` }} /></span>
              <div className="zone-meter-foot">게이트를 거치지 않고 관리자가 처리한 입장이에요.</div>
            </div>
          )}
        </section>
      </div>

      <section className="console-card" aria-labelledby="overview-recent">
        <div className="console-card-head">
          <h3 id="overview-recent">최근 입·퇴장</h3>
          <Link to="?tab=movements">전체 기록</Link>
        </div>
        {(occupancy?.recent ?? []).length === 0 ? (
          <p className="console-none">아직 게이트를 지난 기록이 없어요.</p>
        ) : (
          <div className="console-table-box flat">
            <table className="console-table">
              <tbody>
                {(occupancy?.recent ?? []).slice(0, 6).map((row, index) => (
                  <tr key={index}>
                    <td className="muted num-left">{clock(text(row.occurred_at) || null)}</td>
                    <td><span className={`pill pill-${outcomeTone(text(row.result))}`}>
                      {OUTCOMES[text(row.result)] ?? text(row.result)}</span></td>
                    <td className="strong">{text(row.ticket_ref)}{row.seat ? ` · ${text(row.seat)}` : ''}</td>
                    <td>{text(row.gate_label) || text(row.gate_id) || '–'}</td>
                    <td className="muted">{text(row.reason)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

export function outcomeTone(result: string): string {
  return result === 'ADMITTED' ? 'ok' : result === 'EXITED' ? 'out' : result === 'DENIED' ? 'off' : 'wait';
}
