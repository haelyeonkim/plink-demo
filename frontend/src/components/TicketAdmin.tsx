import { Fragment, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { mutate } from '../auth';
import IssuedLink from './IssuedLink';
import ConfirmDialog from './ConfirmDialog';
import CatalogEditor from './CatalogEditor';
import BulkImport from './BulkImport';
import EntityPicker from './EntityPicker';
import { openLive } from '../ticket/live';

interface SessionRow {
  id: number; name: string; venue: string | null; startsAt: string; gateOpensAt: string | null;
  reentryMode: string; reentryMax: number; reentryGraceMinutes: number; reentryCooldownSeconds: number;
  exitScanRequired: boolean; unmatchedExit: string; autoExitAfterMinutes: number;
  claimRequiresOtp: boolean;
  seats: string[]; tiers: string[];
  crowdBusyPercent: number; crowdSteadyPercent: number;
  /** What a ticket in this event carries, as the organiser defined it. */
  fields: TicketFieldRow[];
  /** Place to capacity, for the places the organiser has measured. */
  zoneCapacity: Record<string, number>;
}
interface TicketFieldRow { id: number; label: string; kind: 'SEAT' | 'TIER' | 'CUSTOM'; values: string[] }

interface TicketRow {
  ticketId: number; ticketRef: string; seat: string | null; status: string;
  issuedToEmail: string; holderEmail: string | null; reissueCount: number;
  tier: string | null;
  inside: boolean; entryCount: number; reentryCount: number;
  lastEventAt: string | null; lastExitAt: string | null; insideSince: string | null;
  phone: string | null; deliveredVia: string | null;
  attributes: Record<string, string>;
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
  role: string; boothId: number | null;
  boundDevice: string | null; lastSeenAt: string | null;
  setupUrl: string | null; setupCode: string | null; setupExpiresAt: string | null;
  /** Null for a terminal registered before tokens had an end date. */
  tokenExpiresAt: string | null; tokenExpired: boolean;
}

/** How a terminal's remaining time reads: the number of days, and how alarming it is. */
function validity(gate: GateRow): { label: string; tone: string } {
  if (gate.tokenExpiresAt === null) return { label: '유효기간 없음', tone: 'wait' };
  const days = Math.ceil((new Date(gate.tokenExpiresAt).getTime() - Date.now()) / 86400000);
  if (gate.tokenExpired || days <= 0) return { label: '유효기간 지남', tone: 'off' };
  return { label: `유효기간 ${days}일 남음`, tone: days <= 3 ? 'warn' : 'ok' };
}

interface BoothRow {
  boothId: number; name: string; note: string | null;
  offers: Array<{ title: string; issued: number; redeemed: number; voided: number }>;
}

interface CouponRow {
  couponId: number; boothId: number | null; booth: string | null; title: string; detail: string | null;
  status: string; ticketId: number; ticketRef: string | null; seat: string | null;
  issuedToEmail: string | null; redeemedAt: string | null;
}

type Tab = 'issue' | 'tickets' | 'gates' | 'coupons' | 'movements' | 'settings';
/** The settings tab is itself a stack of unrelated forms, so it carries its own tabs. */
type SettingsTab = 'event' | 'policy' | 'crowd' | 'fields';
const TABS: Array<[Tab, string]> = [
  ['issue', '발급'],
  ['tickets', '발급 현황'],
  ['gates', '게이트'],
  ['coupons', '쿠폰'],
  ['movements', '입·퇴장'],
  ['settings', '설정'],
];
const SETTINGS_TABS: Array<[SettingsTab, string]> = [
  ['event', '행사 정보'], ['policy', '재입장 · 퇴장'], ['crowd', '혼잡도'], ['fields', '발급 항목'],
];
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
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [sessions, setSessions] = useState<SessionRow[]>([]);
  // The event is the address: a console someone opened can be handed to a colleague.
  const selected = Number(sessionId) || null;
  const [tickets, setTickets] = useState<TicketRow[]>([]);
  const [occupancy, setOccupancy] = useState<Occupancy | null>(null);
  const [gateSetup, setGateSetup] = useState<{ gateId: string; setupUrl: string; setupCode: string } | null>(null);
  const [issued, setIssued] = useState<IssuedTicket | null>(null);
  const [gates, setGates] = useState<GateRow[]>([]);
  const [booths, setBooths] = useState<BoothRow[]>([]);
  const [gateRole, setGateRole] = useState('ADMISSION');
  // The hand-out sheet: open with a ticket to give to that one person, or with null to
  // choose the recipients inside. `pickTickets` is that choice — everybody, or a few.
  const [couponSheet, setCouponSheet] =
    useState<{ only: TicketRow | null; boothId?: number } | null>(null);
  const [pickTickets, setPickTickets] = useState(false);
  // The sheet covers the page, so its own complaint has to be on the sheet.
  const [sheetError, setSheetError] = useState('');
  const [coupons, setCoupons] = useState<CouponRow[]>([]);
  // Which tab is open is part of the address, so the browser's back button walks back
  // through the tabs the way it walks back through the pages.
  const [params, setParams] = useSearchParams();
  const requestedTab = params.get('tab');
  const tab: Tab = TABS.some(([key]) => key === requestedTab) ? (requestedTab as Tab) : 'issue';
  const requestedSection = params.get('sec');
  const settingsTab: SettingsTab = SETTINGS_TABS.some(([key]) => key === requestedSection)
    ? (requestedSection as SettingsTab) : 'event';

  function setTab(next: Tab) {
    setParams(next === 'settings' ? { tab: next, sec: settingsTab } : { tab: next });
  }
  function setSettingsTab(next: SettingsTab) {
    setParams({ tab: 'settings', sec: next });
  }
  // Which booth the 쿠폰 tab is looking at. It lives in the address like the other
  // tabs do, so the back button walks back through the stands.
  const boothTab = params.get('booth') === null ? null : Number(params.get('booth'));
  function setBoothTab(next: number | null) {
    setParams(next === null ? { tab: 'coupons' } : { tab: 'coupons', booth: String(next) });
  }
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmField, setConfirmField] = useState<TicketFieldRow | null>(null);
  // Minting a link puts the old one out of use, so the row's button asks first rather
  // than doing it under a name that sounds like reading.
  const [confirmReissue, setConfirmReissue] = useState<{ row: TicketRow; notify: boolean } | null>(null);
  const [confirmTicket, setConfirmTicket] = useState<TicketRow | null>(null);
  const [confirmBooth, setConfirmBooth] = useState<BoothRow | null>(null);
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
      setBooths(await read(await fetch(`/api/admin/sessions/${id}/booths`)));
      setCoupons(await read(await fetch(`/api/admin/sessions/${id}/coupons`)));
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
    // Every field the organiser defined arrives under its own label.
    const values: Record<string, string> = {};
    for (const [key, value] of data.entries()) {
      if (key.startsWith('field:') && String(value).trim()) values[key.slice(6)] = String(value).trim();
    }
    const result = await read(await mutate(`/api/admin/sessions/${selected}/tickets`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: data.get('email'), phone: data.get('phone'), values }),
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

  /**
   * Removes a ticket outright. A ticket that has been through a gate takes its ledger
   * rows with it, so that one is only sent with the operator's explicit say-so.
   */
  const deleteTicket = (row: TicketRow) => act(async () => {
    const force = row.entryCount > 0;
    const result = await read(await mutate(
      `/api/admin/tickets/${row.ticketId}?force=${force}`, { method: 'DELETE' }));
    setNotice(`${result.ticketRef} 입장권을 삭제했어요.`
      + (result.movements > 0 ? ` 게이트 기록 ${result.movements}건도 함께 지웠습니다.` : '')
      + ` 이제 ${row.issuedToEmail} 주소로 다시 발급할 수 있어요.`);
    await loadSession(selected!);
  });

  const saveField = (fieldId: number, values: string[]) => act(async () => {
    await read(await mutate(`/api/admin/fields/${fieldId}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values }),
    }));
    await loadSessions();
  });

  const addField = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    await read(await mutate(`/api/admin/sessions/${selected}/fields`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ label: data.get('label'), kind: data.get('kind') }),
    }));
    form.reset();
    setNotice('항목을 추가했어요. 발급 화면에서 바로 쓸 수 있습니다.');
    await loadSessions();
  });

  const removeField = (field: TicketFieldRow) => act(async () => {
    await read(await mutate(`/api/admin/fields/${field.id}`, { method: 'DELETE' }));
    setConfirmField(null);
    setNotice(`'${field.label}' 항목을 지웠어요. 이미 발급된 입장권은 그대로입니다.`);
    await loadSessions();
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
    setIssued(null);
    setNotice('행사를 삭제했어요.');
    await loadSessions();
    navigate('/tickets/admin');
  });

  const registerGate = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    const gate = await read(await mutate(`/api/admin/sessions/${selected}/gates`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        gateId: data.get('gateId'), label: data.get('label'),
        zone: data.get('zone'), direction: data.get('direction'),
        role: data.get('role'), boothId: data.get('boothId'),
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

  /**
   * Moves a terminal's end date without touching its token, so the tablet carries on.
   * Zero days means no end date at all, for a terminal wired into a venue.
   */
  const renewGate = (gateId: string, days?: number) => act(async () => {
    const result = await read(await mutate(
      `/api/admin/gates/${gateId}/renew${days === undefined ? '' : `?days=${days}`}`,
      { method: 'POST' }));
    setNotice(result.tokenExpiresAt === null
      ? `${gateId}의 유효기간을 없앴어요. 관리 화면에서 다시 기간을 줄 수 있습니다.`
      : `${gateId}의 유효기간을 ${new Date(result.tokenExpiresAt).toLocaleDateString('ko-KR')}까지 `
        + '연장했어요. 단말은 그대로 쓰면 됩니다.');
    await loadSession(selected!);
  });

  const addBooth = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    await read(await mutate(`/api/admin/sessions/${selected}/booths`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: data.get('name'), note: data.get('note') }),
    }));
    form.reset();
    setNotice(`${data.get('name')} 부스를 만들었어요.`);
    await loadSession(selected!);
  });

  const removeBooth = (booth: BoothRow) => act(async () => {
    await read(await mutate(`/api/admin/booths/${booth.boothId}`, { method: 'DELETE' }));
    setNotice(`${booth.name} 부스와 그 쿠폰을 지웠어요.`);
    await loadSession(selected!);
  });

  /** Gives an offer to everybody, or to one person the operator picked. */
  const giveCoupons = (boothId: number, title: string, detail: string, ticketIds: number[]) =>
    act(async () => {
      const result = await read(await mutate(`/api/admin/sessions/${selected}/coupons`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ boothId, title, detail, ticketIds }),
      }));
      setNotice(`${result.title} 쿠폰을 ${result.given}장 발급했어요`
        + (result.already > 0 ? ` · 이미 가지고 있던 ${result.already}장은 그대로예요.` : '.'));
      await loadSession(selected!);
    });

  // A dialog that covers the screen has to answer Escape, and the page behind it should
  // not scroll while it is open.
  useEffect(() => {
    if (!couponSheet) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setCouponSheet(null); };
    window.addEventListener('keydown', onKey);
    const kept = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = kept;
    };
  }, [couponSheet]);

  /** Opens the sheet, for one named person or for a choice made inside it. */
  const openCouponSheet = (only: TicketRow | null, boothId?: number) => {
    setPickTickets(false);
    setSheetError('');
    setCouponSheet({ only, boothId });
  };

  const issueCoupons = (form: HTMLFormElement) => {
    const only = couponSheet?.only;
    const data = new FormData(form);
    const chosen = only ? [only.ticketId] : data.getAll('ticketIds').map(Number);
    if (!only && pickTickets && chosen.length === 0) {
      setSheetError('쿠폰을 받을 입장권을 선택해 주세요.');
      return;
    }
    setCouponSheet(null);
    void giveCoupons(Number(data.get('boothId')), String(data.get('title')),
      String(data.get('detail') ?? ''), only || pickTickets ? chosen : []);
  };

  const setCouponStatus = (coupon: CouponRow, status: string) => act(async () => {
    await read(await mutate(`/api/admin/coupons/${coupon.couponId}/status`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }));
    setNotice(status === 'VOID' ? `${coupon.title} 쿠폰을 무효로 했어요.`
      : `${coupon.title} 쿠폰을 다시 사용할 수 있게 했어요.`);
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
  /** Tickets a coupon can be given to: a revoked one is not going to use it. */
  const liveTickets = tickets.filter(row => row.status !== 'REVOKED');
  const usedTiers = new Set(tickets.map(row => row.tier).filter((tier): tier is string => !!tier));
  const fieldList = session?.fields ?? [];

  /** What a ticket says for one field, wherever that field is stored. */
  function valueOf(row: TicketRow, field: TicketFieldRow): string | null {
    if (field.kind === 'SEAT') return row.seat;
    if (field.kind === 'TIER') return row.tier;
    return row.attributes?.[field.label] ?? null;
  }

  function valuesInUse(field: TicketFieldRow): Set<string> {
    if (field.kind === 'SEAT') return takenSeats;
    if (field.kind === 'TIER') return usedTiers;
    return new Set(tickets.map(row => row.attributes?.[field.label]).filter((v): v is string => !!v));
  }

  const KINDS: Record<string, string> = { SEAT: '좌석 · 중복 불가', TIER: '등급', CUSTOM: '일반' };
  // Places come from the gates. A capacity left over from a gate that has since been
  // renamed still shows, so the operator can see it and clear it.
  const zoneNames = [...new Set([
    ...gates.map(gate => gate.zone).filter((zone): zone is string => !!zone),
    ...Object.keys(session?.zoneCapacity ?? {}),
  ])].sort((a, b) => a.localeCompare(b, 'ko'));
  return (
    <section className="page-section page-wide">
      <h2>
        입장권 관리
        {session
          ? <><span className="crumb-sep">/</span><span className="crumb">{session.name}</span></>
          : <span className="count">{sessions.length}</span>}
      </h2>

      <div className="picked-bar">
        {session && (
          <span className="picked-meta">
            {shortTime(session.startsAt)}{session.venue ? ` · ${session.venue}` : ''}
            {` · 발급 ${tickets.length}장`}
          </span>
        )}
        {!session && (
          <span className="picked-actions">
            <Link className="btn-secondary" to="/tickets/sessions/new">행사 추가</Link>
          </span>
        )}
      </div>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      {/* Every tab is about one event, so there is nothing for them to show until one
          is picked. */}
      {session && (
        <div className="tabs" role="tablist">
          {TABS.map(([key, label]) => (
            <button key={key} role="tab" aria-selected={tab === key}
              className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>{label}</button>
          ))}
        </div>
      )}

      {!session && (
        <EntityPicker
          items={sessions.map(row => ({
            id: row.id,
            title: row.name,
            meta: <>
              <span>{shortTime(row.startsAt)}</span>
              {row.venue && <span>{row.venue}</span>}
              {row.gateOpensAt && <span>입장 {shortTime(row.gateOpensAt)}</span>}
            </>,
          }))}
          onOpen={id => navigate(`/tickets/admin/${id}?tab=issue`)}
          empty="등록된 행사가 없어요. 행사 추가로 먼저 만들어 주세요." />
      )}

      {session && tab === 'issue' && (
        <div className="tab-panel tab-split">
          <form onSubmit={e => { e.preventDefault(); issueTicket(e.currentTarget); }}>
            <h3>입장권 발급</h3>
            <div className="field">
              <label htmlFor="ticket-to">수신 이메일</label>
              <input id="ticket-to" name="email" type="email" required />
            </div>
            {/* One input per field the organiser defined. A field with a list is a
                choice; one without takes whatever is typed. */}
            {fieldList.length > 0 && (
              <div className="form-row">
                {fieldList.map(field => (
                  <div className="field" key={field.id}>
                    <label htmlFor={`field-${field.id}`}>{field.label}</label>
                    {field.values.length > 0 ? (
                      <select id={`field-${field.id}`} name={`field:${field.label}`} defaultValue="">
                        <option value="">선택 안 함</option>
                        {field.values.map(value => {
                          const taken = field.kind === 'SEAT' && takenSeats.has(value);
                          return (
                            <option key={value} value={value} disabled={taken}>
                              {value}{taken ? ' · 발급됨' : ''}
                            </option>
                          );
                        })}
                      </select>
                    ) : (
                      <input id={`field-${field.id}`} name={`field:${field.label}`} placeholder="직접 입력" />
                    )}
                  </div>
                ))}
              </div>
            )}
            {fieldList.length === 0 && (
              <p className="hint-text">
                발급 항목이 없습니다. 설정 → 발급 항목에서 좌석·등급이든 원하는 이름이든 추가하면
                여기에서 고를 수 있어요.
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
              <dt>발급 항목</dt>
              <dd>{fieldList.length === 0 ? '없음' : fieldList.map(f => f.label).join(' · ')}</dd>
            </dl>
            <p className="hint-text">
              발급하면 수신 이메일로 개인 링크가 갑니다. 휴대폰을 넣으면 문자로도 보냅니다.
              한 이메일에는 한 장만 발급됩니다 — 다시 보내려면 <b>재발송</b>을, 다른 사람에게 주려면
              기존 입장권을 삭제하세요. 발급된 목록은 <b>발급 현황</b> 탭에 있어요.
            </p>

            <BulkImport
              fields={[
                { key: 'email', label: '이메일', match: ['email', '이메일', '메일'], required: true },
                ...fieldList.map(field => ({
                  key: field.label,
                  label: field.label,
                  match: [field.label, ...(field.kind === 'SEAT' ? ['seat', '좌석']
                    : field.kind === 'TIER' ? ['tier', '등급', '권종'] : [])],
                })),
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
                  <th>참조</th>
                  {fieldList.map(field => <th key={field.id}>{field.label}</th>)}
                  <th>상태</th><th>입·퇴장</th><th>보유자</th><th>전달</th><th />
                </tr>
              </thead>
              <tbody>
                {tickets.map(row => (
                  <Fragment key={row.ticketId}>
                  {/* data-label feeds the stacked layout a phone falls back to, where
                      each cell carries its own heading instead of a column. */}
                  <tr className={row.status === 'REVOKED' ? 'row-revoked' : ''}>
                    <td data-label="참조">{row.ticketRef}</td>
                    {fieldList.map(field => (
                      <td key={field.id} data-label={field.label}>{valueOf(row, field) ?? '-'}</td>
                    ))}
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
                      {booths.length > 0 && (
                        <button className="btn-tiny" onClick={() => openCouponSheet(row)}>쿠폰 주기</button>
                      )}
                      <button className="btn-tiny btn-tiny-danger"
                        onClick={() => setConfirmTicket(row)}>삭제</button>
                    </td>
                  </tr>
                  {issued?.from === 'row' && issued.ticketId === row.ticketId && (
                    <tr>
                      <td colSpan={6 + fieldList.length}>
                        <IssuedLink url={issued.url}
                          note="이 입장권의 링크입니다. 보는 것만으로는 아무것도 바뀌지 않아요." />
                      </td>
                    </tr>
                  )}
                  </Fragment>
                ))}
                {tickets.length === 0 && (
                  <tr>
                    <td colSpan={6 + fieldList.length} className="hint-text">발급된 입장권이 없어요.</td>
                  </tr>
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
            {/* A booth terminal reads the same code, but to hand something over. */}
            <div className="form-row">
              <div className="field">
                <label htmlFor="gate-new-role">역할</label>
                <select id="gate-new-role" name="role" value={gateRole}
                  onChange={e => setGateRole(e.target.value)}>
                  <option value="ADMISSION">입장 게이트</option>
                  <option value="BOOTH" disabled={booths.length === 0}>
                    {booths.length === 0 ? '부스 단말 (부스를 먼저 만드세요)' : '부스 단말 · 쿠폰'}
                  </option>
                </select>
              </div>
              {gateRole === 'BOOTH' && (
                <div className="field">
                  <label htmlFor="gate-new-booth">부스</label>
                  <select id="gate-new-booth" name="boothId" required>
                    {booths.map(booth => (
                      <option key={booth.boothId} value={booth.boothId}>{booth.name}</option>
                    ))}
                  </select>
                </div>
              )}
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
                  <b>{gate.label || gate.gateId}</b> · {gate.role === 'BOOTH' ? '부스 단말' : gate.direction}
                  {gate.role === 'BOOTH'
                    ? ` · ${booths.find(booth => booth.boothId === gate.boothId)?.name ?? '부스 없음'}`
                    : gate.zone && ` · ${gate.zone}`}
                  <br />
                  <code title={gate.setupUrl ?? ''}>{gate.setupUrl ?? '(설정 링크 없음 — 재발급이 필요합니다)'}</code>
                  {gate.setupCode && <span className="gate-code">인증번호 {gate.setupCode}</span>}
                  <div className="gate-meta">
                    <span className={`pill pill-${validity(gate).tone}`}>{validity(gate).label}</span>
                    {gate.tokenExpiresAt && (
                      <span> {new Date(gate.tokenExpiresAt).toLocaleDateString('ko-KR')}까지</span>
                    )}
                    {' · '}
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
                  <button className="btn-tiny" onClick={() => renewGate(gate.gateId)}>유효기간 갱신</button>
                  {gate.tokenExpiresAt !== null && (
                    <button className="btn-tiny" onClick={() => renewGate(gate.gateId, 0)}>무제한</button>
                  )}
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
              단말 토큰에는 유효기간이 있고, <b>유효기간 갱신</b>은 토큰과 연결을 그대로 둔 채 날짜만 미룹니다 —
              현장에서 쓰는 태블릿은 기간이 얼마 남지 않으면 스스로 갱신합니다. 건물에 붙박이로 설치한
              단말이라면 <b>무제한</b>으로 두고, 필요할 때 다시 기간을 줄 수 있어요.
            </p>
          </div>
        </div>
      )}

      {session && tab === 'settings' && (
        <div className="tab-panel settings-panel">
          {/* What is actually in force, before any of the forms that change it. */}
          <div className="settings-now">
            <h3>지금 적용 중</h3>
            <div className="table-scroll">
              <table className="summary-table">
                <tbody>
                  {([
                    ['행사 시각', shortTime(session.startsAt) || '-'],
                    ['입장 시각', shortTime(session.gateOpensAt) || '제한 없음'],
                    ['재입장', session.reentryMode === 'DISABLED' ? '불가'
                      : session.reentryMode === 'LIMITED' ? `최대 ${session.reentryMax}회` : '무제한'],
                    ['퇴장 후 유효', `${session.reentryGraceMinutes}분`],
                    ['중복 스캔 무시', `${session.reentryCooldownSeconds}초`],
                    ['퇴장', `${session.exitScanRequired ? '스캔 필수' : '스캔 선택'} · ${session.unmatchedExit}`],
                    ['자동 보정', `${session.autoExitAfterMinutes}분 경과 후`],
                    ['등록 인증', session.claimRequiresOtp ? '이메일 인증' : '주소 입력만'],
                    ['혼잡도 기준', `혼잡 ${session.crowdBusyPercent}% · 보통 ${session.crowdSteadyPercent}%`],
                    ['정원 지정', zoneNames.length === 0 ? '장소 없음'
                      : `${zoneNames.length}곳 중 ${Object.keys(session.zoneCapacity ?? {}).length}곳`],
                    ['발급 항목', fieldList.length === 0 ? '없음'
                      : fieldList.map(f => `${f.label}(${f.values.length || '자유'})`).join(' · ')],
                  ] as Array<[string, string]>).map(([label, value]) => (
                    <tr key={label}><th scope="row">{label}</th><td>{value}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="tabs tabs-inner" role="tablist">
            {SETTINGS_TABS.map(([key, label]) => (
              <button key={key} role="tab" aria-selected={settingsTab === key}
                className={settingsTab === key ? 'active' : ''}
                onClick={() => setSettingsTab(key)}>{label}</button>
            ))}
          </div>

          {settingsTab === 'event' && (
            <form key={`details-${session.id}`} className="settings-form"
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

              <div className="danger-zone">
                <h3>행사 삭제</h3>
                <p className="hint-text">
                  발급된 입장권, 입·퇴장 기록, 등록된 단말이 함께 사라집니다. 되돌릴 수 없어요.
                </p>
                <button className="btn-danger" type="button"
                  onClick={() => setConfirmDelete(true)}>이 행사 삭제</button>
              </div>
            </form>
          )}

          {settingsTab === 'policy' && (
            <form key={`policy-${session.id}`} className="settings-form"
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
          )}

          {settingsTab === 'crowd' && (
            <form key={`crowd-${session.id}`} className="settings-form"
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
          )}

          {settingsTab === 'fields' && (
            <div className="settings-form">
              <p className="hint-text">
                입장권이 무엇을 담을지는 행사마다 다릅니다. 좌석과 등급도 여기서 만드는 항목 중
                하나일 뿐이고, 구역·트랙·식사처럼 필요한 이름을 직접 붙일 수 있어요. 값 목록을 비워
                두면 발급할 때 자유롭게 입력합니다.
              </p>

              {fieldList.map(field => (
                <div className="field-card" key={field.id}>
                  <div className="panel-head">
                    <span className="pill pill-wait">{KINDS[field.kind] ?? field.kind}</span>
                    <button className="btn-tiny" onClick={() => setConfirmField(field)}>항목 삭제</button>
                  </div>
                  <CatalogEditor label={field.label} placeholder="값을 입력하고 추가"
                    items={field.values} inUse={valuesInUse(field)} busy={false}
                    onChange={next => saveField(field.id, next)} />
                </div>
              ))}

              <form key={`add-field-${session.id}`} className="field-card"
                onSubmit={e => { e.preventDefault(); addField(e.currentTarget); }}>
                <h3>항목 추가</h3>
                <div className="form-row">
                  <div className="field">
                    <label htmlFor="field-label">이름</label>
                    <input id="field-label" name="label" required maxLength={40}
                      placeholder="좌석, 등급, 구역, 트랙…" />
                  </div>
                  <div className="field">
                    <label htmlFor="field-kind">유형</label>
                    <select id="field-kind" name="kind" defaultValue="CUSTOM">
                      <option value="CUSTOM">일반</option>
                      <option value="SEAT">좌석 · 한 값은 한 장에만</option>
                      <option value="TIER">등급</option>
                    </select>
                  </div>
                </div>
                <p className="hint-text">
                  '좌석' 유형은 같은 값을 두 장에 발급할 수 없습니다. 행사마다 좌석과 등급은 하나씩만
                  둘 수 있어요.
                </p>
                <button className="btn-primary" type="submit">항목 추가</button>
              </form>
            </div>
          )}
        </div>
      )}

      <ConfirmDialog
        open={confirmField != null}
        title={`'${confirmField?.label}' 항목을 지울까요?`}
        message={'발급 화면과 목록에서 이 항목이 사라집니다. 이미 발급된 입장권은 받은 값을 그대로 '
          + '유지하고, 항목을 다시 만들면 값 목록부터 새로 정하게 됩니다.'}
        confirmLabel="항목 삭제"
        onConfirm={() => confirmField && removeField(confirmField)}
        onCancel={() => setConfirmField(null)}
      />

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

      {/* Handing out an offer is its own screen: the recipients can be a list as long as
          the event, and a panel-sized dialog would make choosing them a scroll in a box. */}
      {couponSheet && (
        <div className="modal-backdrop sheet-backdrop" role="presentation"
          onClick={() => setCouponSheet(null)}>
          <div className="modal modal-sheet" role="dialog" aria-modal="true" aria-labelledby="coupon-sheet"
            onClick={event => event.stopPropagation()}>
            <form onSubmit={e => { e.preventDefault(); issueCoupons(e.currentTarget); }}>
              <header className="sheet-head">
                <div className="sheet-title">
                  <h3 id="coupon-sheet">쿠폰 발급</h3>
                  <p>
                    {couponSheet.only
                      ? `${couponSheet.only.ticketRef}`
                        + `${couponSheet.only.seat ? ` · ${couponSheet.only.seat}` : ''} · `
                        + `${couponSheet.only.holderEmail ?? couponSheet.only.issuedToEmail}`
                      : '부스의 쿠폰을 입장권에 얹어 줍니다. 받는 사람은 새로 받을 것도 없어요.'}
                  </p>
                </div>
                <button type="button" className="sheet-close" aria-label="닫기"
                  onClick={() => setCouponSheet(null)}>✕</button>
              </header>

              <div className="sheet-body">
                <div className="sheet-inner">
                  <div className="form-row">
                    <div className="field">
                      <label htmlFor="give-booth">부스</label>
                      <select id="give-booth" name="boothId" required
                        defaultValue={couponSheet.boothId ?? boothTab ?? undefined}>
                        {booths.map(booth => (
                          <option key={booth.boothId} value={booth.boothId}>{booth.name}</option>
                        ))}
                      </select>
                    </div>
                    <div className="field">
                      <label htmlFor="give-title">쿠폰 이름</label>
                      {/* Existing offers are suggested, because a second name for the same
                          thing is how a tally stops adding up. */}
                      <input id="give-title" name="title" required list="coupon-offers"
                        placeholder="예: 웰컴 드링크" />
                      <datalist id="coupon-offers">
                        {[...new Set(booths.flatMap(booth => booth.offers.map(offer => offer.title)))]
                          .map(title => <option key={title} value={title} />)}
                      </datalist>
                    </div>
                  </div>
                  <div className="field">
                    <label htmlFor="give-detail">상세 안내</label>
                    <textarea id="give-detail" name="detail" rows={3}
                      placeholder="사용 조건, 시간, 수량 등 받는 사람에게 그대로 보여 줄 내용" />
                  </div>

                  {!couponSheet.only && (<>
                    <div className="field">
                      <label htmlFor="coupon-scope">받는 사람</label>
                      <select id="coupon-scope" value={pickTickets ? 'some' : 'all'}
                        onChange={e => setPickTickets(e.target.value === 'some')}>
                        <option value="all">전체 입장권 ({liveTickets.length}장)</option>
                        <option value="some">선택한 입장권</option>
                      </select>
                    </div>
                    {pickTickets && (
                      <div className="pick-list" onChange={() => setSheetError('')}>
                        {liveTickets.length === 0 && <p className="hint-text">발급된 입장권이 없어요.</p>}
                        {liveTickets.map(row => (
                          <label key={row.ticketId} className="pick-row">
                            <input type="checkbox" name="ticketIds" value={row.ticketId} />
                            <b>{row.ticketRef}</b>
                            {row.seat && <span className="pick-seat">{row.seat}</span>}
                            <small>{row.holderEmail ?? row.issuedToEmail}</small>
                          </label>
                        ))}
                      </div>
                    )}
                  </>)}

                  <p className="hint-text">
                    쿠폰은 부스 단말에 <b>입장 QR</b>을 비추면 사용됩니다. 같은 사람에게 같은 쿠폰을
                    두 번 주면 한 장만 남습니다.
                  </p>
                </div>
              </div>

              <footer className="sheet-foot">
                {sheetError && <p className="sheet-error" role="alert">{sheetError}</p>}
                <button type="button" className="btn-secondary"
                  onClick={() => setCouponSheet(null)}>취소</button>
                <button type="submit" className="btn-primary">쿠폰 발급</button>
              </footer>
            </form>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmBooth != null}
        title={`'${confirmBooth?.name}' 부스를 지울까요?`}
        message={'이 부스의 쿠폰이 모두 함께 사라집니다. 이미 사용한 기록도 같이 지워지니, '
          + '기록을 남겨야 한다면 쿠폰을 무효화하는 쪽을 쓰세요.'}
        details={confirmBooth ? [
          ['부스', confirmBooth.name],
          ['쿠폰 종류', confirmBooth.offers.length],
          ['발급', confirmBooth.offers.reduce((sum, offer) => sum + offer.issued, 0)],
          ['사용', confirmBooth.offers.reduce((sum, offer) => sum + offer.redeemed, 0)],
        ] : undefined}
        confirmLabel="부스 삭제"
        onConfirm={() => {
          if (!confirmBooth) return;
          const booth = confirmBooth;
          setConfirmBooth(null);
          removeBooth(booth);
        }}
        onCancel={() => setConfirmBooth(null)}
      />

      <ConfirmDialog
        open={confirmTicket != null}
        title="입장권을 삭제할까요?"
        message={confirmTicket && confirmTicket.entryCount > 0
          ? '이 입장권과 게이트를 지난 기록까지 함께 사라집니다. 되돌릴 수 없어요. 기록을 남겨야 한다면 '
            + '삭제 대신 비활성화를 쓰세요.'
          : '이 입장권이 사라지고 링크는 더 이상 열리지 않습니다. 같은 이메일로 다시 발급할 수 있어요.'}
        details={confirmTicket ? [
          ['입장권', confirmTicket.ticketRef],
          ['받는 사람', confirmTicket.holderEmail ?? confirmTicket.issuedToEmail],
          ['상태', ticketState(confirmTicket).label],
          ['입장 이력', `${confirmTicket.entryCount}회`],
        ] : undefined}
        /* Gate history is the one thing here that cannot be reissued, so removing it
           asks for the reference to be typed. */
        requireText={confirmTicket && confirmTicket.entryCount > 0 ? confirmTicket.ticketRef : undefined}
        confirmLabel="입장권 삭제"
        onConfirm={() => {
          if (!confirmTicket) return;
          const row = confirmTicket;
          setConfirmTicket(null);
          deleteTicket(row);
        }}
        onCancel={() => setConfirmTicket(null)}
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

      {session && tab === 'coupons' && (
        <div className="tab-panel tab-split">
          <div>
            <form onSubmit={e => { e.preventDefault(); addBooth(e.currentTarget); }}>
              <h3>부스</h3>
              <div className="form-row">
                <div className="field">
                  <label htmlFor="booth-name">이름</label>
                  <input id="booth-name" name="name" required placeholder="예: 커피 스탠드" />
                </div>
                <div className="field">
                  <label htmlFor="booth-note">위치 안내</label>
                  <input id="booth-note" name="note" placeholder="예: 로비 왼쪽" />
                </div>
              </div>
              <button className="btn-primary" type="submit">부스 추가</button>
            </form>

            <p className="hint-text">
              부스를 만들고 나면 <b>쿠폰 발급</b>으로 그 부스의 쿠폰을 입장권에 얹을 수 있어요.
              쿠폰은 부스 단말에 입장 QR을 비추면 사용됩니다 — 받는 사람은 새로 받을 것도,
              설치할 것도 없어요.
            </p>
          </div>

          <div>
            {/* One stand at a time: a room full of booths is a room full of separate
                counters, and the console reads better the same way. */}
            <div className="inner-tabs" role="tablist">
              <button role="tab" aria-selected={boothTab === null}
                className={boothTab === null ? 'active' : ''}
                onClick={() => setBoothTab(null)}>전체 {coupons.length}</button>
              {booths.map(booth => {
                const mine = coupons.filter(row => row.boothId === booth.boothId);
                return (
                  <button key={booth.boothId} role="tab" aria-selected={boothTab === booth.boothId}
                    className={boothTab === booth.boothId ? 'active' : ''}
                    onClick={() => setBoothTab(booth.boothId)}>
                    {booth.name} <b>{mine.length}</b>
                  </button>
                );
              })}
            </div>

            {(() => {
              const booth = booths.find(row => row.boothId === boothTab) ?? null;
              const shown = booth ? coupons.filter(row => row.boothId === booth.boothId) : coupons;
              const redeemed = shown.filter(row => row.status === 'REDEEMED').length;
              return (<>
                <div className="panel-head">
                  <h3>
                    {booth ? booth.name : '부스 전체'}
                    <small className="head-note">
                      {booth?.note ? `${booth.note} · ` : ''}
                      쿠폰 {shown.length}장 · 사용 {redeemed}장
                    </small>
                  </h3>
                  <div className="gate-actions">
                    <button className="btn-tiny" disabled={booths.length === 0}
                      onClick={() => openCouponSheet(null, booth?.boothId)}>쿠폰 발급</button>
                    {booth && (
                      <button className="btn-tiny btn-tiny-danger"
                        onClick={() => setConfirmBooth(booth)}>부스 삭제</button>
                    )}
                  </div>
                </div>

                {/* What this stand gives, with how much of it has gone. */}
                {(booth ? [booth] : booths).map(row => (
                  <div className="gate-row" key={row.boothId}>
                    <div>
                      {!booth && <b>{row.name}</b>}
                      {!booth && row.note && <span className="cell-note">{row.note}</span>}
                      <div className="gate-meta">
                        {row.offers.length === 0 ? '쿠폰 없음' : row.offers.map(offer => (
                          <span key={offer.title} className="pill pill-wait">
                            {offer.title} {offer.redeemed}/{offer.issued}
                          </span>
                        ))}
                      </div>
                    </div>
                    {!booth && (
                      <div className="gate-actions">
                        <button className="btn-tiny" onClick={() => setBoothTab(row.boothId)}>열기</button>
                        <button className="btn-tiny btn-tiny-danger"
                          onClick={() => setConfirmBooth(row)}>삭제</button>
                      </div>
                    )}
                  </div>
                ))}
                {booths.length === 0 && <p className="hint-text">등록된 부스가 없어요.</p>}

                <div className="table-scroll">
                  <table className="ticket-table">
                    <thead>
                      <tr>
                        {!booth && <th>부스</th>}
                        <th>쿠폰</th><th>입장권</th><th>상태</th><th />
                      </tr>
                    </thead>
                    <tbody>
                      {shown.map(row => (
                        <tr key={row.couponId} className={row.status === 'VOID' ? 'row-revoked' : ''}>
                          {!booth && <td data-label="부스">{row.booth ?? '-'}</td>}
                          <td data-label="쿠폰">{row.title}</td>
                          <td data-label="입장권">
                            {row.ticketRef}{row.seat ? ` · ${row.seat}` : ''}
                            <small className="cell-note">{row.issuedToEmail}</small>
                          </td>
                          <td data-label="상태">
                            <span className={`pill pill-${row.status === 'REDEEMED' ? 'ok'
                              : row.status === 'VOID' ? 'off' : 'wait'}`}>
                              {row.status === 'REDEEMED' ? '사용함' : row.status === 'VOID' ? '무효' : '미사용'}
                            </span>
                            {row.redeemedAt && <small className="cell-note">{shortTime(row.redeemedAt)}</small>}
                          </td>
                          <td className="cell-buttons">
                            {row.status === 'VOID' ? (
                              <button className="btn-tiny" onClick={() => setCouponStatus(row, 'ISSUED')}>
                                되살리기
                              </button>
                            ) : (
                              <button className="btn-tiny btn-tiny-danger"
                                onClick={() => setCouponStatus(row, 'VOID')}>무효화</button>
                            )}
                          </td>
                        </tr>
                      ))}
                      {shown.length === 0 && (
                        <tr><td colSpan={booth ? 4 : 5}>
                          <p className="hint-text">
                            {booth ? `${booth.name}에서 발급한 쿠폰이 없어요.` : '발급된 쿠폰이 없어요.'}
                          </p>
                        </td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </>);
            })()}
          </div>
        </div>
      )}

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
