import { useCallback, useEffect, useState } from 'react';
import { mutate } from '../auth';

interface SessionRow {
  id: number; name: string; venue: string | null; startsAt: string; gateOpensAt: string | null;
  reentryMode: string; reentryMax: number; reentryGraceMinutes: number; reentryCooldownSeconds: number;
  exitScanRequired: boolean; unmatchedExit: string; autoExitAfterMinutes: number;
}
interface TicketRow {
  ticketId: number; ticketRef: string; seat: string | null; status: string;
  issuedToEmail: string; holderEmail: string | null; reissueCount: number;
  inside: boolean; entryCount: number; reentryCount: number;
}
interface Occupancy {
  inside: number; everEntered: number; tickets: number; bound: number;
  stillInside: Array<Record<string, unknown>>;
  recent: Array<Record<string, unknown>>;
}

async function read(response: Response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || '요청을 처리하지 못했어요.');
  return data;
}

export default function TicketAdmin() {
  const [sessions, setSessions] = useState<SessionRow[]>([]);
  const [selected, setSelected] = useState<number | null>(null);
  const [tickets, setTickets] = useState<TicketRow[]>([]);
  const [occupancy, setOccupancy] = useState<Occupancy | null>(null);
  const [gateToken, setGateToken] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadSessions = useCallback(async () => {
    try { setSessions(await read(await fetch('/api/admin/sessions'))); }
    catch (err) { setError(err instanceof Error ? err.message : '회차를 불러오지 못했어요.'); }
  }, []);

  const loadSession = useCallback(async (id: number) => {
    try {
      setTickets(await read(await fetch(`/api/admin/sessions/${id}/tickets`)));
      setOccupancy(await read(await fetch(`/api/admin/sessions/${id}/occupancy`)));
    } catch (err) { setError(err instanceof Error ? err.message : '회차 정보를 불러오지 못했어요.'); }
  }, []);

  useEffect(() => { void loadSessions(); }, [loadSessions]);
  useEffect(() => { if (selected !== null) void loadSession(selected); }, [selected, loadSession]);
  useEffect(() => {
    if (selected === null) return;
    // Live occupancy is what operations watch during doors.
    const timer = window.setInterval(() => { void loadSession(selected); }, 5000);
    return () => window.clearInterval(timer);
  }, [selected, loadSession]);

  async function act(action: () => Promise<void>) {
    setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
  }

  const createSession = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const startsAt = new Date(String(data.get('startsAt'))).toISOString();
    await read(await mutate('/api/admin/sessions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: data.get('name'), venue: data.get('venue'), startsAt }),
    }));
    form.reset();
    setNotice('회차를 만들었어요.');
    await loadSessions();
  });

  const issueTicket = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const issued = await read(await mutate(`/api/admin/sessions/${selected}/tickets`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: data.get('email'), seat: data.get('seat'), tier: data.get('tier') }),
    }));
    form.reset();
    setNotice(`${issued.ticketRef} 발급 완료 — 개인 링크는 메일로만 전달됩니다.`);
    await loadSession(selected!);
  });

  const registerGate = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const gate = await read(await mutate(`/api/admin/sessions/${selected}/gates`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        gateId: data.get('gateId'), label: data.get('label'),
        zone: data.get('zone'), direction: data.get('direction'),
      }),
    }));
    form.reset();
    setGateToken(gate.gateToken);
  });

  const savePolicy = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    await read(await mutate(`/api/admin/sessions/${selected}/policy`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        reentryMode: data.get('reentryMode'),
        reentryMax: Number(data.get('reentryMax')),
        reentryGraceMinutes: Number(data.get('reentryGraceMinutes')),
        reentryCooldownSeconds: Number(data.get('reentryCooldownSeconds')),
        exitScanRequired: data.get('exitScanRequired') === 'on',
        unmatchedExit: data.get('unmatchedExit'),
      }),
    }));
    setNotice('정책을 저장했어요. 다음 스캔부터 적용됩니다.');
    await loadSessions();
  });

  const session = sessions.find(s => s.id === selected) ?? null;

  return (
    <section className="page-section">
      <p className="eyebrow"><span></span> TICKETING</p>
      <h2>입장권 관리</h2>
      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="detail-grid">
        <article className="detail-card">
          <h3>회차</h3>
          <ul className="session-list">
            {sessions.map(row => (
              <li key={row.id}>
                <button className={row.id === selected ? 'chosen' : ''} onClick={() => setSelected(row.id)}>
                  <b>{row.name}</b>
                  <span>{new Date(row.startsAt).toLocaleString('ko-KR')}</span>
                </button>
              </li>
            ))}
            {sessions.length === 0 && <li className="hint-text">등록된 회차가 없어요.</li>}
          </ul>
          <form onSubmit={e => { e.preventDefault(); createSession(e.currentTarget); }}>
            <div className="field">
              <label htmlFor="session-name">회차 이름</label>
              <input id="session-name" name="name" required />
            </div>
            <div className="field">
              <label htmlFor="session-venue">장소</label>
              <input id="session-venue" name="venue" />
            </div>
            <div className="field">
              <label htmlFor="session-starts">시작 시각</label>
              <input id="session-starts" name="startsAt" type="datetime-local" required />
            </div>
            <button className="btn-secondary" type="submit">회차 추가</button>
          </form>
        </article>

        {session && (
          <>
            <article className="detail-card">
              <h3>실시간 장내</h3>
              {occupancy && (
                <dl className="stat-grid">
                  <div><dt>장내</dt><dd>{occupancy.inside}</dd></div>
                  <div><dt>입장 이력</dt><dd>{occupancy.everEntered}</dd></div>
                  <div><dt>등록 완료</dt><dd>{occupancy.bound}</dd></div>
                  <div><dt>발급</dt><dd>{occupancy.tickets}</dd></div>
                </dl>
              )}
              <h3>최근 기록</h3>
              <ul className="event-list">
                {occupancy?.recent.map((row, index) => (
                  <li key={index} className={String(row.result) === 'DENIED' ? 'denied' : ''}>
                    <span>{String(row.result)}</span>
                    <span>{row.direction ? String(row.direction) : '-'}</span>
                    <span>{row.gate_id ? String(row.gate_id) : '-'}</span>
                    <span>{row.reason ? String(row.reason) : ''}</span>
                  </li>
                ))}
                {occupancy?.recent.length === 0 && <li className="hint-text">기록이 없어요.</li>}
              </ul>
            </article>

            <article className="detail-card">
              <h3>입장권 발급</h3>
              <form onSubmit={e => { e.preventDefault(); issueTicket(e.currentTarget); }}>
                <div className="field">
                  <label htmlFor="ticket-to">수신 이메일</label>
                  <input id="ticket-to" name="email" type="email" required />
                </div>
                <div className="field">
                  <label htmlFor="ticket-seat">좌석</label>
                  <input id="ticket-seat" name="seat" />
                </div>
                <div className="field">
                  <label htmlFor="ticket-tier">등급</label>
                  <input id="ticket-tier" name="tier" />
                </div>
                <button className="btn-primary" type="submit">발급하고 메일 보내기</button>
              </form>
              <table className="ticket-table">
                <thead>
                  <tr><th>참조</th><th>좌석</th><th>상태</th><th>보유자</th><th>장내</th><th>재입장</th></tr>
                </thead>
                <tbody>
                  {tickets.map(row => (
                    <tr key={row.ticketId}>
                      <td>{row.ticketRef}</td>
                      <td>{row.seat ?? '-'}</td>
                      <td>{row.status}</td>
                      <td>{row.holderEmail ?? row.issuedToEmail}</td>
                      <td>{row.inside ? '\u25cb' : '-'}</td>
                      <td>{row.reentryCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </article>

            <article className="detail-card">
              <h3>재입장 · 퇴장 정책</h3>
              <form onSubmit={e => { e.preventDefault(); savePolicy(e.currentTarget); }}>
                <div className="field">
                  <label htmlFor="policy-mode">재입장</label>
                  <select id="policy-mode" name="reentryMode" defaultValue={session.reentryMode}>
                    <option value="DISABLED">DISABLED · 재입장 불가</option>
                    <option value="LIMITED">LIMITED · 횟수 제한</option>
                    <option value="UNLIMITED">UNLIMITED · 무제한</option>
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="policy-max">재입장 횟수</label>
                  <input id="policy-max" name="reentryMax" type="number" min={0} defaultValue={session.reentryMax} />
                </div>
                <div className="field">
                  <label htmlFor="policy-grace">퇴장 후 유효 시간(분)</label>
                  <input id="policy-grace" name="reentryGraceMinutes" type="number" min={0}
                    defaultValue={session.reentryGraceMinutes} />
                </div>
                <div className="field">
                  <label htmlFor="policy-cooldown">중복 스캔 무시(초)</label>
                  <input id="policy-cooldown" name="reentryCooldownSeconds" type="number" min={0}
                    defaultValue={session.reentryCooldownSeconds} />
                </div>
                <div className="field">
                  <label htmlFor="policy-unmatched">퇴장 미스캔</label>
                  <select id="policy-unmatched" name="unmatchedExit" defaultValue={session.unmatchedExit}>
                    <option value="STRICT">STRICT · 안내 데스크만</option>
                    <option value="LENIENT">LENIENT · 장시간 후 자동 보정</option>
                    <option value="AUTO_EXIT">AUTO_EXIT · 자동 정리까지</option>
                  </select>
                </div>
                <label className="field-inline">
                  <input name="exitScanRequired" type="checkbox" defaultChecked={session.exitScanRequired} />
                  퇴장 스캔 필수
                </label>
                <button className="btn-secondary" type="submit">정책 저장</button>
              </form>
              <p className="hint-text">
                장내 상태에서의 재입장은 {session.autoExitAfterMinutes}분이 지나야 보정됩니다. 그 전에는
                어떤 정책이든 거부되어 QR 공유가 통하지 않습니다.
              </p>
            </article>

            <article className="detail-card">
              <h3>게이트 단말</h3>
              <form onSubmit={e => { e.preventDefault(); registerGate(e.currentTarget); }}>
                <div className="field">
                  <label htmlFor="gate-new-id">게이트 ID</label>
                  <input id="gate-new-id" name="gateId" pattern="[A-Za-z0-9_-]{1,32}" required />
                </div>
                <div className="field">
                  <label htmlFor="gate-new-label">이름</label>
                  <input id="gate-new-label" name="label" />
                </div>
                <div className="field">
                  <label htmlFor="gate-new-zone">구역</label>
                  <input id="gate-new-zone" name="zone" />
                </div>
                <div className="field">
                  <label htmlFor="gate-new-direction">방향</label>
                  <select id="gate-new-direction" name="direction" defaultValue="BIDIRECTIONAL">
                    <option value="IN">IN · 입장 전용</option>
                    <option value="OUT">OUT · 퇴장 전용</option>
                    <option value="BIDIRECTIONAL">BIDIRECTIONAL · 겸용</option>
                  </select>
                </div>
                <button className="btn-secondary" type="submit">단말 등록</button>
              </form>
              {gateToken && (
                <p className="notice-text" role="status">
                  게이트 토큰: <code>{gateToken}</code><br />
                  다시 확인할 수 없어요. 단말의 <code>/gate</code> 화면에 바로 입력해 주세요.
                </p>
              )}
            </article>
          </>
        )}
      </div>
    </section>
  );
}
