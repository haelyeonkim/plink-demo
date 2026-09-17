import { Fragment, useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { mutate } from '../auth';
import IssuedLink from './IssuedLink';
import ConfirmDialog from './ConfirmDialog';
import CatalogEditor from './CatalogEditor';
import BulkImport from './BulkImport';
import { openLive } from '../ticket/live';

interface SessionRow {
  id: number; name: string; venue: string | null; startsAt: string; gateOpensAt: string | null;
  reentryMode: string; reentryMax: number; reentryGraceMinutes: number; reentryCooldownSeconds: number;
  exitScanRequired: boolean; unmatchedExit: string; autoExitAfterMinutes: number;
  claimRequiresOtp: boolean;
  seats: string[]; tiers: string[];
  crowdBusyPercent: number; crowdSteadyPercent: number;
  /** Place to capacity, for the places the organiser has measured. */
  zoneCapacity: Record<string, number>;
}
interface TicketRow {
  ticketId: number; ticketRef: string; seat: string | null; status: string;
  issuedToEmail: string; holderEmail: string | null; reissueCount: number;
  tier: string | null;
  inside: boolean; entryCount: number; reentryCount: number;
  lastEventAt: string | null; lastExitAt: string | null; insideSince: string | null;
  phone: string | null; deliveredVia: string | null;
}

/** What the ticket itself is: issued, claimed by a person, or switched off. */
function ticketState(row: TicketRow): { label: string; tone: string } {
  if (row.status === 'REVOKED') return { label: '비활성', tone: 'off' };
  if (row.status === 'TRANSFER_PENDING') return { label: '양도 중', tone: 'warn' };
  if (row.holderEmail) return { label: '등록 완료', tone: 'ok' };
  return { label: '미등록', tone: 'wait' };
}

/** Where the holder is, which is a different question from what the ticket is. */
function presenceState(row: TicketRow): { label: string; tone: string; since: string | null } {
  if (row.inside) return { label: '장내', tone: 'in', since: row.insideSince };
  if (row.entryCount > 0) return { label: '퇴장', tone: 'out', since: row.lastExitAt ?? row.lastEventAt };
  return { label: '미입장', tone: 'wait', since: null };
}

function shortTime(value: string | null): string {
  if (!value) return '';
  return new Date(value).toLocaleString('ko-KR', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

interface IssuedTicket {
  ticketId: number; ticketRef: string; url: string; deliveredVia: string;
  /** Where the link should appear: under the issue form, or in the row it came from. */
  from: 'issue' | 'row';
}

interface GateRow {
  gateId: string; label: string | null; zone: string | null; direction: string;
  boundDevice: string | null; lastSeenAt: string | null;
  setupUrl: string | null; setupCode: string | null; setupExpiresAt: string | null;
}

type Tab = 'issue' | 'tickets' | 'gates' | 'movements' | 'settings';
interface Occupancy {
  inside: number; everEntered: number; tickets: number; bound: number;
  stillInside: Array<Record<string, unknown>>;
  recent: Array<Record<string, unknown>>;
  byGate: Array<Record<string, unknown>>;
  byZone: Array<Record<string, unknown>>;
}

function text(value: unknown): string { return value == null ? '' : String(value); }
function count(value: unknown): number { return Number(value ?? 0); }

/** The ledger stores outcomes in the wire vocabulary; the console reads them aloud. */
const OUTCOMES: Record<string, string> = {
  ADMITTED: '입장', EXITED: '퇴장', DENIED: '거부', DUPLICATE: '중복', TRANSFERRED: '양도',
};

/** "yyyy-MM-ddTHH:mm" in the operator's own zone, which is what the input speaks. */
function localInput(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
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
  const [gateSetup, setGateSetup] = useState<{ gateId: string; setupUrl: string; setupCode: string } | null>(null);
  const [issued, setIssued] = useState<IssuedTicket | null>(null);
  const [gates, setGates] = useState<GateRow[]>([]);
  const [tab, setTab] = useState<Tab>('issue');
  const [confirmDelete, setConfirmDelete] = useState(false);
  // Minting a link puts the old one out of use, so the row's button asks first rather
  // than doing it under a name that sounds like reading.
  const [confirmReissue, setConfirmReissue] = useState<{ row: TicketRow; notify: boolean } | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadSessions = useCallback(async () => {
    try { setSessions(await read(await fetch('/api/admin/sessions'))); }
    catch (err) { setError(err instanceof Error ? err.message : '행사를 불러오지 못했어요.'); }
  }, []);

  const loadSession = useCallback(async (id: number) => {
    try {
      setTickets(await read(await fetch(`/api/admin/sessions/${id}/tickets`)));
      setOccupancy(await read(await fetch(`/api/admin/sessions/${id}/occupancy`)));
      setGates(await read(await fetch(`/api/admin/sessions/${id}/gates`)));
    } catch (err) { setError(err instanceof Error ? err.message : '행사 정보를 불러오지 못했어요.'); }
  }, []);

  useEffect(() => { void loadSessions(); }, [loadSessions]);
  useEffect(() => { if (selected !== null) void loadSession(selected); }, [selected, loadSession]);
  useEffect(() => {
    if (selected === null) return;
    // Scans arrive as they happen; what follows is the fallback for a network that will
    // not carry the socket, which is why it can afford to be slow.
    const live = openLive(`/ws/admin/sessions/${selected}`, message => {
      if (message.type === 'OCCUPANCY') setOccupancy(message as unknown as Occupancy);
      if (message.type === 'MOVEMENT') void loadSession(selected);
    });
    const timer = window.setInterval(() => { void loadSession(selected); }, 30000);
    return () => { live.close(); window.clearInterval(timer); };
  }, [selected, loadSession]);

  async function act(action: () => Promise<void>) {
    setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
  }

  const issueTicket = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const result = await read(await mutate(`/api/admin/sessions/${selected}/tickets`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: data.get('email'), seat: data.get('seat'),
        tier: data.get('tier'), phone: data.get('phone'),
      }),
    }));
    form.reset();
    setIssued({ ...(result as IssuedTicket), from: 'issue' });
    setNotice(`${result.ticketRef} 발급 완료 (${result.deliveredVia}).`);
    await loadSession(selected!);
  });

  const issueBulk = async (rows: Array<Record<string, string>>, notify: boolean) => {
    const result = await read(await mutate(`/api/admin/sessions/${selected}/tickets/bulk`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ notify, rows }),
    }));
    setNotice(`${result.issued}건 발급했어요${result.failed ? ` · ${result.failed}건 실패` : ''}.`);
    await loadSession(selected!);
    return result;
  };

  const setRevoked = (ticketId: number, revoked: boolean) => act(async () => {
    const result = await read(await mutate(`/api/admin/tickets/${ticketId}/status`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revoked }),
    }));
    setNotice(revoked
      ? `${result.ticketRef} 비활성화했어요. 링크가 열리지 않고 게이트도 거부합니다.`
      : `${result.ticketRef} 다시 사용할 수 있어요.`);
    await loadSession(selected!);
  });

  /** Shows the link that was issued. Nothing is minted and nothing is invalidated. */
  const showLink = (ticketId: number) => act(async () => {
    const result = await read(await fetch(`/api/admin/tickets/${ticketId}/link`));
    setIssued({ ...(result as IssuedTicket), from: 'row' });
  });

  const reissueLink = (ticketId: number, notify: boolean) => act(async () => {
    const result = await read(await mutate(`/api/admin/tickets/${ticketId}/link`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ notify }),
    }));
    setIssued({ ...(result as IssuedTicket), from: 'row' });
    setNotice('새 링크를 발급했어요. 이전 링크는 더 이상 열리지 않습니다.');
    await loadSession(selected!);
  });

  const deleteSession = (id: number) => act(async () => {
    // The dialog has already shown what goes with it and taken a typed confirmation,
    // so this is the deliberate force rather than a retry after a refusal.
    await read(await mutate(`/api/admin/sessions/${id}?force=true`, { method: 'DELETE' }));
    setConfirmDelete(false);
    setSelected(null);
    setIssued(null);
    setNotice('행사를 삭제했어요.');
    await loadSessions();
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
    setGateSetup(gate);
    await loadSession(selected!);
  });

  const reopenSetup = (gateId: string) => act(async () => {
    const result = await read(await mutate(`/api/admin/gates/${gateId}/setup`, { method: 'POST' }));
    setGateSetup(result);
    setNotice(`${gateId}의 설정 링크를 새로 발급했어요.`);
    await loadSession(selected!);
  });

  const rotateGateToken = (gateId: string) => act(async () => {
    await read(await mutate(`/api/admin/gates/${gateId}/token`, { method: 'POST' }));
    setNotice(`${gateId} 토큰을 새로 발급했어요. 단말은 설정 링크로 다시 연결해야 합니다.`);
    await loadSession(selected!);
  });

  const releaseGate = (gateId: string) => act(async () => {
    await read(await mutate(`/api/admin/gates/${gateId}/release`, { method: 'POST' }));
    setNotice(`${gateId}의 단말 연결을 해제했어요.`);
    await loadSession(selected!);
  });

  const saveCatalog = (patch: { seats?: string[]; tiers?: string[] }) => act(async () => {
    await read(await mutate(`/api/admin/sessions/${selected}/policy`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch),
    }));
    await loadSessions();
  });

  /**
   * The crowding numbers: where the two lines sit, and how many each place holds.
   *
   * Sent as one action because that is how the operator thinks of it, though the lines
   * belong to the event and the capacities to its places.
   */
  const saveCrowding = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const zones: Record<string, number> = {};
    for (const [key, value] of data.entries()) {
      if (!key.startsWith('zone:')) continue;
      zones[key.slice(5)] = Number(value || 0);
    }
    await read(await mutate(`/api/admin/sessions/${selected}/policy`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        crowdBusyPercent: Number(data.get('crowdBusyPercent')),
        crowdSteadyPercent: Number(data.get('crowdSteadyPercent')),
      }),
    }));
    await read(await mutate(`/api/admin/sessions/${selected}/zones`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ zones }),
    }));
    setNotice('혼잡도 기준을 저장했어요.');
    await loadSessions();
  });

  const saveDetails = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const startsAt = String(data.get('startsAt') || '');
    const gateOpensAt = String(data.get('gateOpensAt') || '');
    await read(await mutate(`/api/admin/sessions/${selected}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: data.get('name'),
        venue: data.get('venue'),
        // The input speaks local time; the API speaks instants.
        startsAt: startsAt ? new Date(startsAt).toISOString() : null,
        gateOpensAt: gateOpensAt ? new Date(gateOpensAt).toISOString() : null,
      }),
    }));
    setNotice('행사 정보를 저장했어요. 발급된 입장권에도 바로 반영됩니다.');
    await loadSessions();
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
        claimRequiresOtp: data.get('claimRequiresOtp') === 'on',
        unmatchedExit: data.get('unmatchedExit'),
      }),
    }));
    setNotice('정책을 저장했어요. 다음 스캔부터 적용됩니다.');
    await loadSessions();
  });

  const session = sessions.find(s => s.id === selected) ?? null;
  // A seat is spoken for as soon as a ticket carries it; the database enforces the same.
  const takenSeats = new Set(tickets.map(row => row.seat).filter((seat): seat is string => !!seat));
  const usedTiers = new Set(tickets.map(row => row.tier).filter((tier): tier is string => !!tier));
  // Places come from the gates. A capacity left over from a gate that has since been
  // renamed still shows, so the operator can see it and clear it.
  const zoneNames = [...new Set([
    ...gates.map(gate => gate.zone).filter((zone): zone is string => !!zone),
    ...Object.keys(session?.zoneCapacity ?? {}),
  ])].sort((a, b) => a.localeCompare(b, 'ko'));
  const tabs: Array<[Tab, string]> = [
    ['issue', '발급'],
    ['tickets', '발급 현황'],
    ['gates', '게이트'],
    ['movements', '입·퇴장'],
    ['settings', '설정'],
  ];

  return (
    <section className="page-section page-wide">
      <p className="eyebrow"><span></span> TICKETING</p>
      <h2>입장권 관리</h2>

      <div className="session-bar">
        <div className="field">
          <select id="session-picker" aria-label="행사 선택" value={selected ?? ''}
            onChange={e => setSelected(e.target.value ? Number(e.target.value) : null)}>
            <option value="">행사를 선택하세요</option>
            {sessions.map(row => (
              <option key={row.id} value={row.id}>
                {row.name} · {new Date(row.startsAt).toLocaleString('ko-KR')}
              </option>
            ))}
          </select>
        </div>
        <Link className="btn-secondary" to="/tickets/sessions/new">행사 추가</Link>
      </div>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="tabs" role="tablist">
        {tabs.map(([key, label]) => (
          <button key={key} role="tab" aria-selected={tab === key}
            className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>{label}</button>
        ))}
      </div>

      {!session && (
        <div className="tab-panel">
          <p className="hint-text">
            {sessions.length === 0
              ? '등록된 행사가 없어요. 행사 추가로 먼저 만들어 주세요.'
              : '먼저 행사를 선택해 주세요.'}
          </p>
        </div>
      )}

      {session && tab === 'issue' && (
        <div className="tab-panel tab-split">
          <form onSubmit={e => { e.preventDefault(); issueTicket(e.currentTarget); }}>
            <h3>입장권 발급</h3>
            <div className="field">
              <label htmlFor="ticket-to">수신 이메일</label>
              <input id="ticket-to" name="email" type="email" required />
            </div>
            <div className="form-row">
              {/* Seats and tiers exist only if this event defined them: the list is the
                  catalogue, never free text, so nothing can be issued for a seat the
                  event does not have. */}
              <div className="field">
                <label htmlFor="ticket-seat">좌석</label>
                <select id="ticket-seat" name="seat" defaultValue=""
                  disabled={session.seats.length === 0}>
                  <option value="">선택 안 함</option>
                  {session.seats.map(seat => (
                    <option key={seat} value={seat} disabled={takenSeats.has(seat)}>
                      {seat}{takenSeats.has(seat) ? ' · 발급됨' : ''}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="ticket-tier">등급</label>
                <select id="ticket-tier" name="tier" defaultValue=""
                  disabled={session.tiers.length === 0}>
                  <option value="">선택 안 함</option>
                  {session.tiers.map(tier => <option key={tier} value={tier}>{tier}</option>)}
                </select>
              </div>
            </div>
            {session.seats.length === 0 && session.tiers.length === 0 && (
              <p className="hint-text">
                좌석·등급은 기본값이 없습니다. 설정 탭에서 추가하면 여기에서 고를 수 있어요.
              </p>
            )}
            <div className="field">
              <label htmlFor="ticket-phone">휴대폰 (선택 · 문자 발송)</label>
              <input id="ticket-phone" name="phone" inputMode="tel" placeholder="01012345678" />
            </div>
            <button className="btn-primary" type="submit">발급하고 보내기</button>
            {issued?.from === 'issue' && (
              <IssuedLink url={issued.url}
                note={`${issued.ticketRef} · 이 링크는 지금만 보여집니다. 다시 보려면 새로 발급해야 해요.`} />
            )}
          </form>
          <div>
            <h3>이 행사</h3>
            <dl className="ticket-meta">
              <dt>발급</dt><dd>{tickets.length}장</dd>
              <dt>등록 완료</dt><dd>{tickets.filter(t => t.status === 'BOUND').length}장</dd>
              <dt>남은 좌석</dt>
              <dd>{session.seats.length > 0 ? `${session.seats.length - takenSeats.size}석` : '제한 없음'}</dd>
            </dl>
            <p className="hint-text">
              발급하면 수신 이메일로 개인 링크가 갑니다. 휴대폰을 넣으면 문자로도 보냅니다.
              발급된 목록은 <b>발급 현황</b> 탭에 있어요.
            </p>

            <BulkImport
              fields={[
                { key: 'email', label: '이메일', match: ['email', '이메일', '메일'], required: true },
                { key: 'seat', label: '좌석', match: ['seat', '좌석'] },
                { key: 'tier', label: '등급', match: ['tier', '등급', '권종'] },
                { key: 'phone', label: '휴대폰', match: ['phone', '휴대폰', '전화', '연락처'] },
              ]}
              sampleName="입장권-발급-양식.csv"
              notifyLabel="발급하면서 수신자에게 메일·문자 보내기"
              onSubmit={issueBulk} />
          </div>
        </div>
      )}

      {session && tab === 'tickets' && (
        <div className="tab-panel">
          <div className="panel-head">
            <h3>발급 현황 ({tickets.length})</h3>
            <button className="btn-tiny" onClick={() => setTab('issue')}>발급하기</button>
          </div>
          <div className="table-scroll">
            <table className="ticket-table">
              <thead>
                <tr>
                  <th>참조</th><th>좌석</th><th>등급</th><th>상태</th><th>입·퇴장</th>
                  <th>보유자</th><th>전달</th><th />
                </tr>
              </thead>
              <tbody>
                {tickets.map(row => (
                  <Fragment key={row.ticketId}>
                  {/* data-label feeds the stacked layout a phone falls back to, where
                      each cell carries its own heading instead of a column. */}
                  <tr className={row.status === 'REVOKED' ? 'row-revoked' : ''}>
                    <td data-label="참조">{row.ticketRef}</td>
                    <td data-label="좌석">{row.seat ?? '-'}</td>
                    <td data-label="등급">{row.tier ?? '-'}</td>
                    <td data-label="상태">
                      <span className={`pill pill-${ticketState(row).tone}`}>{ticketState(row).label}</span>
                    </td>
                    <td data-label="입·퇴장">
                      <span className={`pill pill-${presenceState(row).tone}`}>
                        {presenceState(row).label}
                      </span>
                      {row.entryCount > 0 && (
                        <small className="cell-note">
                          {shortTime(presenceState(row).since)} · 입장 {row.entryCount}회
                          {row.reentryCount > 0 ? ` (재입장 ${row.reentryCount})` : ''}
                        </small>
                      )}
                    </td>
                    <td data-label="보유자">{row.holderEmail ?? row.issuedToEmail}</td>
                    <td data-label="전달">{row.deliveredVia ?? '-'}</td>
                    <td className="cell-buttons">
                      <button className="btn-tiny" onClick={() => showLink(row.ticketId)}>링크 보기</button>
                      <button className="btn-tiny" onClick={() => setConfirmReissue({ row, notify: false })}>
                        재발급
                      </button>
                      <button className="btn-tiny" onClick={() => setConfirmReissue({ row, notify: true })}>
                        재발송
                      </button>
                      {row.status === 'REVOKED' ? (
                        <button className="btn-tiny" onClick={() => setRevoked(row.ticketId, false)}>다시 사용</button>
                      ) : (
                        <button className="btn-tiny" onClick={() => setRevoked(row.ticketId, true)}>비활성화</button>
                      )}
                    </td>
                  </tr>
                  {issued?.from === 'row' && issued.ticketId === row.ticketId && (
                    <tr>
                      <td colSpan={8}>
                        <IssuedLink url={issued.url}
                          note="이 입장권의 링크입니다. 보는 것만으로는 아무것도 바뀌지 않아요." />
                      </td>
                    </tr>
                  )}
                  </Fragment>
                ))}
                {tickets.length === 0 && (
                  <tr><td colSpan={8} className="hint-text">발급된 입장권이 없어요.</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="hint-text">
            <b>링크 보기</b>는 발급된 링크를 그대로 보여 줍니다. 입장권도 등록 상태도 그대로예요.
            <b> 재발급</b>은 새 링크를 만들고 그 순간 이전 링크를 닫습니다.
          </p>
          <p className="hint-text">
            비활성화하면 링크가 열리지 않고 게이트도 거부합니다. 이미 장내에 있는 사람을 내보내지는
            않아요 — 입장한 기록은 사실이니까요.
          </p>
        </div>
      )}

      {session && tab === 'gates' && (
        <div className="tab-panel tab-split">
          <form onSubmit={e => { e.preventDefault(); registerGate(e.currentTarget); }}>
            <h3>단말 등록</h3>
            <div className="field">
              <label htmlFor="gate-new-id">게이트 ID</label>
              <input id="gate-new-id" name="gateId" pattern="[A-Za-z0-9_\-]{1,32}" required />
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="gate-new-label">이름</label>
                <input id="gate-new-label" name="label" />
              </div>
              <div className="field">
                <label htmlFor="gate-new-zone">장소</label>
                <input id="gate-new-zone" name="zone" required placeholder="예: 메인홀" />
              </div>
            </div>
            <div className="field">
              <label htmlFor="gate-new-direction">방향</label>
              <select id="gate-new-direction" name="direction" defaultValue="BIDIRECTIONAL">
                <option value="IN">IN · 입장 전용</option>
                <option value="OUT">OUT · 퇴장 전용</option>
                <option value="BIDIRECTIONAL">BIDIRECTIONAL · 겸용</option>
              </select>
            </div>
            <button className="btn-primary" type="submit">단말 등록</button>
            {gateSetup && (
              <div className="issued-link">
                <p className="hint-text">
                  <b>{gateSetup.gateId}</b> · 이 링크를 태블릿에서 열고 아래 인증번호를 입력하면 연결됩니다.
                </p>
                <code title={gateSetup.setupUrl}>{gateSetup.setupUrl}</code>
                <p className="code-timer">인증번호 {gateSetup.setupCode}</p>
                <div className="share-row">
                  <button type="button" onClick={() => navigator.clipboard?.writeText(gateSetup.setupUrl)}>
                    링크 복사
                  </button>
                </div>
              </div>
            )}
          </form>
          <div>
            <h3>등록된 단말 ({gates.length})</h3>
            {gates.map(gate => (
              <div className="gate-row" key={gate.gateId}>
                <div>
                  <b>{gate.label || gate.gateId}</b> · {gate.direction}
                  {gate.zone && ` · ${gate.zone}`}
                  <br />
                  <code title={gate.setupUrl ?? ''}>{gate.setupUrl ?? '(설정 링크 없음 — 재발급이 필요합니다)'}</code>
                  {gate.setupCode && <span className="gate-code">인증번호 {gate.setupCode}</span>}
                  <div className="gate-meta">
                    {gate.boundDevice
                      ? `단말 ${gate.boundDevice}… 연결됨${gate.lastSeenAt ? ` · 최근 ${new Date(gate.lastSeenAt).toLocaleTimeString('ko-KR')}` : ''}`
                      : '연결된 단말 없음'}
                  </div>
                </div>
                <div className="gate-actions">
                  <button className="btn-tiny" onClick={() => navigator.clipboard?.writeText(gate.setupUrl ?? '')}>
                    링크 복사
                  </button>
                  <button className="btn-tiny" onClick={() => reopenSetup(gate.gateId)}>설정 링크 재발급</button>
                  <button className="btn-tiny" onClick={() => releaseGate(gate.gateId)}
                    disabled={!gate.boundDevice}>연결 해제</button>
                  <button className="btn-tiny" onClick={() => rotateGateToken(gate.gateId)}>토큰 폐기</button>
                </div>
              </div>
            ))}
            {gates.length === 0 && <p className="hint-text">등록된 단말이 없어요.</p>}
            <p className="hint-text">
              태블릿에서 설정 링크를 열고 인증번호를 입력하면 연결됩니다 — 긴 토큰을 직접 입력할 일은 없어요.
              게이트 하나는 단말 한 대에만 연결됩니다. 두 대가 같은 ID를 쓰면 각자 절반의 기록만 보게 되어
              장내 상태가 어긋나기 때문이에요. 단말을 교체하려면 연결을 해제해 주세요.
            </p>
          </div>
        </div>
      )}

      {session && tab === 'settings' && (
        <div className="tab-panel settings-panel">
          {/* What is actually in force, before any of the forms that change it. */}
          <div className="settings-now">
            <h3>지금 적용 중</h3>
            <dl>
              <div><dt>행사 시각</dt><dd>{shortTime(session.startsAt) || '-'}</dd></div>
              <div><dt>입장 시각</dt><dd>{shortTime(session.gateOpensAt) || '제한 없음'}</dd></div>
              <div>
                <dt>재입장</dt>
                <dd>{session.reentryMode === 'DISABLED' ? '불가'
                  : session.reentryMode === 'LIMITED' ? `최대 ${session.reentryMax}회` : '무제한'}</dd>
              </div>
              <div><dt>퇴장 후 유효</dt><dd>{session.reentryGraceMinutes}분</dd></div>
              <div><dt>중복 스캔 무시</dt><dd>{session.reentryCooldownSeconds}초</dd></div>
              <div>
                <dt>퇴장</dt>
                <dd>{session.exitScanRequired ? '스캔 필수' : '스캔 선택'} · {session.unmatchedExit}</dd>
              </div>
              <div><dt>자동 보정</dt><dd>{session.autoExitAfterMinutes}분 경과 후</dd></div>
              <div>
                <dt>등록 인증</dt>
                <dd>{session.claimRequiresOtp ? '이메일 인증' : '주소 입력만'}</dd>
              </div>
              <div>
                <dt>혼잡도 기준</dt>
                <dd>혼잡 {session.crowdBusyPercent}% · 보통 {session.crowdSteadyPercent}%</dd>
              </div>
              <div>
                <dt>정원 지정</dt>
                <dd>{zoneNames.length === 0 ? '장소 없음'
                  : `${Object.keys(session.zoneCapacity ?? {}).length} / ${zoneNames.length}곳`}</dd>
              </div>
              <div><dt>좌석</dt><dd>{session.seats.length}개</dd></div>
              <div><dt>등급</dt><dd>{session.tiers.length}개</dd></div>
            </dl>
          </div>

          <div className="settings-grid">
            <section className="settings-card">
              <h3>행사 정보</h3>
              <form key={`details-${session.id}`}
                onSubmit={e => { e.preventDefault(); saveDetails(e.currentTarget); }}>
                <div className="field">
                  <label htmlFor="event-name">이름</label>
                  <input id="event-name" name="name" required defaultValue={session.name} />
                </div>
                <div className="field">
                  <label htmlFor="event-venue">장소</label>
                  <input id="event-venue" name="venue" defaultValue={session.venue ?? ''} />
                </div>
                <div className="form-row">
                  <div className="field">
                    <label htmlFor="event-starts">행사 시각</label>
                    <input id="event-starts" name="startsAt" type="datetime-local" required
                      defaultValue={localInput(session.startsAt)} />
                  </div>
                  <div className="field">
                    <label htmlFor="event-gate">입장 시각 (선택)</label>
                    <input id="event-gate" name="gateOpensAt" type="datetime-local"
                      defaultValue={localInput(session.gateOpensAt)} />
                  </div>
                </div>
                <p className="hint-text">
                  입장 시각을 지정하면 그 전에는 게이트가 입장을 거부합니다. 비워 두면 언제든 입장할 수
                  있어요. 시각을 바꿔도 이미 발급한 입장권은 그대로 쓰입니다.
                </p>
                <button className="btn-primary" type="submit">행사 정보 저장</button>
              </form>
            </section>

            <section className="settings-card">
              <h3>재입장 · 퇴장</h3>
              <form key={`policy-${session.id}`}
                onSubmit={e => { e.preventDefault(); savePolicy(e.currentTarget); }}>
                <div className="form-row">
                  <div className="field">
                    <label htmlFor="policy-mode">재입장</label>
                    <select id="policy-mode" name="reentryMode" defaultValue={session.reentryMode}>
                      <option value="DISABLED">불가</option>
                      <option value="LIMITED">횟수 제한</option>
                      <option value="UNLIMITED">무제한</option>
                    </select>
                  </div>
                  <div className="field">
                    <label htmlFor="policy-max">재입장 횟수</label>
                    <input id="policy-max" name="reentryMax" type="number" min={0}
                      defaultValue={session.reentryMax} />
                  </div>
                </div>
                <div className="form-row">
                  <div className="field">
                    <label htmlFor="policy-grace">퇴장 후 유효(분)</label>
                    <input id="policy-grace" name="reentryGraceMinutes" type="number" min={0}
                      defaultValue={session.reentryGraceMinutes} />
                  </div>
                  <div className="field">
                    <label htmlFor="policy-cooldown">중복 스캔 무시(초)</label>
                    <input id="policy-cooldown" name="reentryCooldownSeconds" type="number" min={0}
                      defaultValue={session.reentryCooldownSeconds} />
                  </div>
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
                <label className="field-inline">
                  <input name="claimRequiresOtp" type="checkbox" defaultChecked={session.claimRequiresOtp} />
                  등록 시 이메일 인증 필요
                </label>
                <p className="hint-text">
                  이메일 인증을 끄면 <b>입장권을 받은 주소를 입력한 사람</b>이 보유자가 됩니다. 메일로
                  뿌리는 행사라면 켜 두세요. 기기 재발급과 양도 취소는 정책과 무관하게 항상 이메일
                  인증을 요구합니다.
                </p>
                <p className="hint-text">
                  장내 상태에서의 재입장은 {session.autoExitAfterMinutes}분이 지나야 보정됩니다. 그 전에는
                  어떤 정책이든 거부되어 QR 공유가 통하지 않습니다.
                </p>
                <button className="btn-primary" type="submit">정책 저장</button>
              </form>
            </section>

            <section className="settings-card">
              <h3>혼잡도</h3>
              <form key={`crowd-${session.id}`}
                onSubmit={e => { e.preventDefault(); saveCrowding(e.currentTarget); }}>
                <div className="form-row">
                  <div className="field">
                    <label htmlFor="crowd-busy">'혼잡' 기준 (%)</label>
                    <input id="crowd-busy" name="crowdBusyPercent" type="number" min={1} max={100}
                      required defaultValue={session.crowdBusyPercent} />
                  </div>
                  <div className="field">
                    <label htmlFor="crowd-steady">'보통' 기준 (%)</label>
                    <input id="crowd-steady" name="crowdSteadyPercent" type="number" min={1} max={100}
                      required defaultValue={session.crowdSteadyPercent} />
                  </div>
                </div>
                {zoneNames.length === 0 ? (
                  <p className="hint-text">
                    게이트에 장소를 지정하면 여기에서 장소별 정원을 정할 수 있어요.
                  </p>
                ) : (
                  <div className="zone-capacity">
                    {zoneNames.map(zone => (
                      <div className="field" key={zone}>
                        <label htmlFor={`zone-${zone}`}>{zone} 정원</label>
                        <input id={`zone-${zone}`} name={`zone:${zone}`} type="number" min={0}
                          placeholder="비워 두면 상대 표시"
                          defaultValue={session.zoneCapacity?.[zone] ?? ''} />
                      </div>
                    ))}
                  </div>
                )}
                <p className="hint-text">
                  정원을 적은 장소는 정원 대비로, 비워 둔 장소는 가장 붐비는 곳과 비교해 표시됩니다.
                </p>
                <button className="btn-primary" type="submit">혼잡도 기준 저장</button>
              </form>
            </section>

            <section className="settings-card">
              <h3>좌석 · 등급</h3>
              <CatalogEditor label="좌석" placeholder="A-1" items={session.seats} inUse={takenSeats}
                busy={false} onChange={next => saveCatalog({ seats: next })} />
              <CatalogEditor label="등급" placeholder="VIP" items={session.tiers} inUse={usedTiers}
                busy={false} onChange={next => saveCatalog({ tiers: next })} />
              <p className="hint-text">
                기본값은 없습니다. 여기서 추가한 항목만 발급 화면에 나오고, 추가·삭제는 즉시 저장됩니다.
                쉼표나 줄바꿈으로 여러 개를 한 번에 붙여넣을 수 있고, 이미 발급된 값을 지워도 그
                입장권은 그대로 유지돼요.
              </p>
            </section>
          </div>

          <div className="danger-zone">
            <h3>행사 삭제</h3>
            <p className="hint-text">
              발급된 입장권, 입·퇴장 기록, 등록된 단말이 함께 사라집니다. 되돌릴 수 없어요.
            </p>
            <button className="btn-danger" onClick={() => setConfirmDelete(true)}>이 행사 삭제</button>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmReissue != null}
        title={confirmReissue?.notify ? '링크를 다시 보낼까요?' : '새 링크를 발급할까요?'}
        message={'이미 보낸 링크는 이 순간부터 열리지 않고, 등록된 기기도 함께 풀립니다. '
          + '보유자는 새 링크로 다시 등록해야 해요.'
          + (confirmReissue?.notify ? '' : ' 발급된 링크는 서버에 남기지 않아 지난 링크를 다시 볼 수는 없습니다.')}
        details={confirmReissue ? [
          ['입장권', confirmReissue.row.ticketRef],
          ['받는 사람', confirmReissue.row.holderEmail ?? confirmReissue.row.issuedToEmail ?? '-'],
          ['상태', ticketState(confirmReissue.row).label],
        ] : undefined}
        confirmLabel={confirmReissue?.notify ? '재발송' : '재발급'}
        onConfirm={() => {
          if (!confirmReissue) return;
          const { row, notify } = confirmReissue;
          setConfirmReissue(null);
          reissueLink(row.ticketId, notify);
        }}
        onCancel={() => setConfirmReissue(null)}
      />

      <ConfirmDialog
        open={confirmDelete && session != null}
        title="행사를 삭제할까요?"
        message={`"${session?.name}" 과 여기에 속한 모든 기록이 사라집니다. 되돌릴 수 없습니다.`}
        details={[
          ['발급된 입장권', occupancy?.tickets ?? 0],
          ['등록 완료', occupancy?.bound ?? 0],
          ['입장 이력', occupancy?.everEntered ?? 0],
          ['현재 장내', occupancy?.inside ?? 0],
          ['등록된 단말', gates.length],
        ]}
        requireText={session?.name}
        confirmLabel="영구 삭제"
        onConfirm={() => session && deleteSession(session.id)}
        onCancel={() => setConfirmDelete(false)}
      />

      {session && tab === 'movements' && (
        <div className="tab-panel">
          <h3>실시간 장내</h3>
          {occupancy && (
            <dl className="stat-grid stat-grid-4">
              <div><dt>장내</dt><dd>{occupancy.inside}</dd></div>
              <div><dt>입장 이력</dt><dd>{occupancy.everEntered}</dd></div>
              <div><dt>등록 완료</dt><dd>{occupancy.bound}</dd></div>
              <div><dt>발급</dt><dd>{occupancy.tickets}</dd></div>
            </dl>
          )}
          <h3>장소별 혼잡도</h3>
          {occupancy && occupancy.byZone.length === 0 ? (
            <p className="empty">등록된 게이트가 없어요.</p>
          ) : (
            <ul className="zone-list">
              {(() => {
                const zones = occupancy?.byZone ?? [];
                const now = (row: Record<string, unknown>) =>
                  Math.max(0, count(row.admitted) - count(row.exited));
                const busiest = Math.max(1, ...zones.map(now));
                return zones.map((row, index) => (
                  <li key={index}>
                    <div className="zone-head">
                      <b>{text(row.zone)}</b>
                      <span className="hint-text">게이트 {count(row.gates)}대</span>
                      <strong className="zone-now">{now(row)}명</strong>
                    </div>
                    <div className="zone-bar" aria-hidden="true">
                      <span style={{ width: `${Math.round((now(row) / busiest) * 100)}%` }} />
                    </div>
                    <div className="entity-meta">
                      <span>입장 {count(row.admitted)}</span>
                      <span>퇴장 {count(row.exited)}</span>
                      {count(row.denied) > 0 && <span className="expired">거부 {count(row.denied)}</span>}
                      {text(row.last_at) !== '' && <span>마지막 {shortTime(text(row.last_at))}</span>}
                    </div>
                  </li>
                ));
              })()}
            </ul>
          )}

          <h3>게이트별</h3>
          {occupancy && occupancy.byGate.length === 0 ? (
            <p className="empty">아직 게이트를 지난 기록이 없어요.</p>
          ) : (
            <ul className="entity-list">
              {occupancy?.byGate.map((row, index) => (
                <li key={index}>
                  <div className="entity-main">
                    <span className="entity-title">
                      {text(row.gate_label) || text(row.gate_id)}
                      <small className="cell-note">{text(row.gate_id)}</small>
                    </span>
                    <span className="entity-meta">
                      <span>입장 {count(row.admitted)}</span>
                      <span>퇴장 {count(row.exited)}</span>
                      {count(row.denied) > 0 && <span className="expired">거부 {count(row.denied)}</span>}
                      {count(row.duplicate) > 0 && <span>중복 {count(row.duplicate)}</span>}
                      <span>마지막 {shortTime(text(row.last_at) || null)}</span>
                    </span>
                  </div>
                  <div className="entity-actions">
                    <span className="pill pill-wait">{count(row.total)}건</span>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <h3>최근 기록</h3>
          {occupancy?.recent.length === 0 && <p className="empty">기록이 없어요.</p>}
          <ul className="event-list">
            {occupancy?.recent.map((row, index) => (
              <li key={index} className={text(row.result) === 'DENIED' ? 'denied' : ''}>
                <span>{shortTime(text(row.occurred_at) || null)}</span>
                <span>{OUTCOMES[text(row.result)] ?? text(row.result)}</span>
                <span>{text(row.direction) === 'IN' ? '입' : text(row.direction) === 'OUT' ? '퇴' : '-'}</span>
                <span>{text(row.gate_label) || text(row.gate_id) || '-'}</span>
                <span>{text(row.ticket_ref)}{row.seat ? ` · ${text(row.seat)}` : ''}</span>
                <span>{text(row.reason)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
