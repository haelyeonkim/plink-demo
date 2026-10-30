import Icon from '@shared/components/Icon';
import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { mutate } from '@shared/auth';
import IssuedLink from './IssuedLink';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import CatalogEditor from './CatalogEditor';
import BulkImport from '@shared/components/BulkImport';
import EventTable from './EventTable';
import EventNav, { type ConsoleTab } from './EventNav';
import EventOverview, { crowdLevel, outcomeTone } from './EventOverview';
import { openLive, type LiveState } from '../ticket/live';
import { EMPTY_PAGE, ListFilter, Pager, useDebounced, type Paged } from './ListTools';
import RowMenu from './RowMenu';
import GateSetupQr, { type SetupTarget } from './GateSetupQr';
import ConsoleChat from './ConsoleChat';
import ConsoleAds from './ConsoleAds';
import ConsoleCoupons from './ConsoleCoupons';
import Toast from '@shared/components/Toast';
import {
  BoothRow, DIRECTION_LABEL, GateRow, IssuedTicket, MAX_PER_TICKET, OUTCOMES, Occupancy, PAGE_SIZE, SessionRow, TicketFieldRow, TicketFilter, TicketRow, TicketHistory, TicketSummary, UNMATCHED_LABEL, admitsPeople, clock, count, dayTime, DELIVERY, eventWhen, hourLabel, localInput, percent, presenceState, read, shortTime, text, ticketState, validity,
} from '../console/model';

type Tab = ConsoleTab;
/** Each screen's title, which is also its entry in the event's menu. */
const SETTINGS_NAMES: Record<string, string> = { details: '이벤트 정보', policy: '재입장·퇴장', crowd: '혼잡도' };

const TAB_TITLES: Record<Tab, string> = {
  overview: '개요', tickets: '발급 현황', issue: '입장권 발급', movements: '실시간 입·퇴장',
  gates: '게이트', coupons: '쿠폰', chat: '장내 채팅', ads: '알림판', settings: '이벤트 설정',
};
/** Settings is one page of sections; these are its sections, in order. */
type SettingsTab = 'event' | 'policy' | 'crowd' | 'fields';
/** The settings forms that wait for the save bar, in the order they are saved. */
type SettingsKey = 'details' | 'policy' | 'crowd';
const SETTINGS_ORDER: SettingsKey[] = ['details', 'policy', 'crowd'];
const SETTINGS_TABS: Array<[SettingsTab, string]> = [
  ['event', '이벤트 정보'], ['policy', '재입장 · 퇴장'], ['crowd', '혼잡도'], ['fields', '발급 항목'],
];


export default function TicketAdmin() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [sessions, setSessions] = useState<SessionRow[]>([]);
  const [sessionsLoaded, setSessionsLoaded] = useState(false);
  // The event is the address: a console someone opened can be handed to a colleague.
  const selected = Number(sessionId) || null;
  // 발급 현황 is read a page at a time; the counts and taken values come separately so
  // the rest of the console never needs every row.
  const [ticketPage, setTicketPage] = useState<Paged<TicketRow>>(EMPTY_PAGE);
  const [ticketQuery, setTicketQuery] = useState('');
  const [ticketFilter, setTicketFilter] = useState<TicketFilter>('ALL');
  // The page number belongs to one search: it is kept with the search it was chosen in,
  // so a new search reads its first page at once instead of the old page number first.
  const [ticketPaging, setTicketPaging] = useState({ key: '', page: 0 });
  const [summary, setSummary] = useState<TicketSummary | null>(null);
  // Tickets ticked for a bulk action, and the one whose detail is open beside the list.
  const [checked, setChecked] = useState<Set<number>>(new Set());
  const [openTicket, setOpenTicket] = useState<TicketRow | null>(null);
  const [history, setHistory] = useState<TicketHistory | null>(null);
  // Bumped after anything that changes a list, so the page on screen is read again.
  const [revision, setRevision] = useState(0);
  const [occupancy, setOccupancy] = useState<Occupancy | null>(null);
  // Whether the numbers on screen are live, and when they last changed.
  const [liveState, setLiveState] = useState<LiveState>('connecting');
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  // Bumped whenever the room's chat or board changes, so their screens read again.
  const [roomTick, setRoomTick] = useState(0);
  const [shownCodes, setShownCodes] = useState<Set<string>>(new Set());
  // The settings page saves once, from a bar that shows while anything is changed.
  const settingsForms = {
    details: useRef<HTMLFormElement>(null), policy: useRef<HTMLFormElement>(null), crowd: useRef<HTMLFormElement>(null),
  };
  const [settingsDirty, setSettingsDirty] = useState<Set<SettingsKey>>(new Set());
  const [savingSettings, setSavingSettings] = useState(false);
  // Counts edits per form, so a change typed while its save is on the way is not
  // marked saved along with it.
  const settingsEdits = useRef<Record<SettingsKey, number>>({ details: 0, policy: 0, crowd: 0 });
  const markSettingsDirty = (key: SettingsKey) => {
    settingsEdits.current[key] += 1;
    setSettingsDirty(current => (current.has(key) ? current : new Set(current).add(key)));
  };
  const revertSettings = () => {
    settingsDirty.forEach(key => settingsForms[key].current?.reset());
    setSettingsDirty(new Set());
  };
  const [gateSetup, setGateSetup] = useState<{ gateId: string; setupUrl: string; setupCode: string; expiresAt?: string } | null>(null);
  // A setup link shown as a QR code for the tablet's camera.
  const [setupQr, setSetupQr] = useState<SetupTarget | null>(null);
  const [issued, setIssued] = useState<IssuedTicket | null>(null);
  const [gates, setGates] = useState<GateRow[]>([]);
  const [booths, setBooths] = useState<BoothRow[]>([]);
  const [gateRole, setGateRole] = useState('ADMISSION');
  // The hand-out sheet: open with a ticket to give to that one person, or with null to
  // choose the recipients inside. `pickTickets` is that choice - everybody, or a few.
  const [couponSheet, setCouponSheet] =
    useState<{ only: TicketRow | null; boothId?: number; ids?: number[] } | null>(null);
  const [pickTickets, setPickTickets] = useState(false);
  // "Everybody whose 등급 is VIP": a field and one of its values, found on the server.
  const [matchMode, setMatchMode] = useState(false);
  const [matchField, setMatchField] = useState('');
  const [matchValue, setMatchValue] = useState('');
  // Chosen recipients survive a new search: the list below the box changes, the choice
  // does not.
  const [chosen, setChosen] = useState<Map<number, TicketRow>>(new Map());
  const [pickQuery, setPickQuery] = useState('');
  const [pickPage, setPickPage] = useState<Paged<TicketRow>>(EMPTY_PAGE);
  const [sheetBooth, setSheetBooth] = useState<number | null>(null);
  const [sheetOffer, setSheetOffer] = useState<number | null>(null);
  // How many of the offer each recipient should end up holding.
  const [sheetQuantity, setSheetQuantity] = useState(1);
  // The sheet covers the page, so its own complaint has to be on the sheet.
  const [sheetError, setSheetError] = useState('');
  // The terminal whose coupon list is being edited, and the draft: offer -> gives on entry.
  const [grantGate, setGrantGate] = useState<GateRow | null>(null);
  const [grantDraft, setGrantDraft] = useState<Map<number, boolean>>(new Map());
  // Which tab is open is part of the address, so the browser's back button walks back
  // through the tabs the way it walks back through the pages.
  const [params, setParams] = useSearchParams();
  const requestedTab = params.get('tab');
  const tab: Tab = requestedTab !== null && requestedTab in TAB_TITLES ? (requestedTab as Tab) : 'overview';
  // An older link may still name a settings section; the page scrolls to it.
  const requestedSection = params.get('sec');

  function setTab(next: Tab) {
    setParams({ tab: next });
  }
  // Leaving settings unmounts its forms, which takes the unsaved values with them; the
  // bar must not come back claiming changes that are no longer on screen.
  useEffect(() => { if (tab !== 'settings') setSettingsDirty(new Set()); }, [tab]);
  useEffect(() => {
    if (tab !== 'settings' || !requestedSection) return;
    document.getElementById(`settings-${requestedSection}`)?.scrollIntoView({ block: 'start' });
  }, [tab, requestedSection, sessions.length]);
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
  const [confirmBulkOff, setConfirmBulkOff] = useState<number[] | null>(null);
  const [confirmBooth, setConfirmBooth] = useState<BoothRow | null>(null);
  // Both of these cut a working tablet off at the door, so the gate menu asks first.
  const [confirmGate, setConfirmGate] = useState<{ gate: GateRow; action: 'rotate' | 'release' } | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadSessions = useCallback(async () => {
    try { setSessions(await read(await fetch('/api/admin/sessions'))); }
    catch (err) { setError(err instanceof Error ? err.message : '이벤트를 불러오지 못했어요.'); }
    finally { setSessionsLoaded(true); }
  }, []);

  // Only the newest read may land: an older one still on its way - for the event that
  // was open before, or overtaken by a newer read - is cancelled and its answer dropped.
  const sessionLoad = useRef<AbortController | null>(null);
  const loadSession = useCallback(async (id: number) => {
    sessionLoad.current?.abort();
    const controller = new AbortController();
    sessionLoad.current = controller;
    const get = (path: string) =>
      fetch(`/api/admin/sessions/${id}/${path}`, { signal: controller.signal }).then(read);
    try {
      const [nextSummary, nextOccupancy, nextGates, nextBooths] =
        await Promise.all([get('tickets/summary'), get('occupancy'), get('gates'), get('booths')]);
      if (controller.signal.aborted) return;
      setSummary(nextSummary);
      setOccupancy(nextOccupancy);
      setGates(nextGates);
      setBooths(nextBooths);
      setRevision(value => value + 1);
      setUpdatedAt(new Date());
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(err instanceof Error ? err.message : '이벤트 정보를 불러오지 못했어요.');
    }
  }, []);

  // A new search or filter starts from the first page; a new event starts clean.
  const ticketSearch = useDebounced(ticketQuery.trim());
  const pagingKey = `${selected}|${ticketFilter}|${ticketSearch}`;
  const ticketPageNo = ticketPaging.key === pagingKey ? ticketPaging.page : 0;
  const setTicketPageNo = (page: number) => setTicketPaging({ key: pagingKey, page });
  // A choice is about the rows on screen: a new search, filter or page starts it over.
  useEffect(() => { setChecked(new Set()); }, [ticketSearch, ticketFilter, ticketPageNo, selected]);
  // Nothing of the event that was open before may stay on screen under the new one's name.
  useEffect(() => {
    setOpenTicket(null);
    setSettingsDirty(new Set());
    setSummary(null);
    setOccupancy(null);
    setGates([]);
    setBooths([]);
    setTicketPage(EMPTY_PAGE);
    return () => { sessionLoad.current?.abort(); };
  }, [selected]);
  // The open ticket follows the list: after an action its row is read again.
  useEffect(() => {
    setOpenTicket(current => current
      ? ticketPage.items.find(row => row.ticketId === current.ticketId) ?? current : null);
  }, [ticketPage]);
  useEffect(() => {
    if (!openTicket) { setHistory(null); return; }
    let current = true;
    fetch(`/api/admin/tickets/${openTicket.ticketId}/history`).then(read)
      .then(data => { if (current) setHistory(data); })
      .catch(() => { if (current) setHistory(null); });
    return () => { current = false; };
  }, [openTicket?.ticketId, revision]);

  useEffect(() => {
    if (selected === null) return;
    const query = new URLSearchParams({
      page: String(ticketPageNo), size: String(PAGE_SIZE), filter: ticketFilter });
    if (ticketSearch) query.set('q', ticketSearch);
    let current = true;
    fetch(`/api/admin/sessions/${selected}/tickets?${query}`).then(read)
      .then(page => { if (current) setTicketPage(page); })
      .catch(err => { if (current) setError(err instanceof Error ? err.message : '입장권을 불러오지 못했어요.'); });
    return () => { current = false; };
  }, [selected, ticketPageNo, ticketFilter, ticketSearch, revision]);


  useEffect(() => { void loadSessions(); }, [loadSessions]);
  useEffect(() => { if (selected !== null) void loadSession(selected); }, [selected, loadSession]);
  useEffect(() => {
    if (selected === null) return;
    // Scans arrive as they happen; what follows is the fallback for a network that will
    // not carry the socket, which is why it can afford to be slow.
    // A busy door sends a movement for every scan; each one used to read the whole event
    // again. They are gathered into at most one read a second.
    let queuedReload: number | null = null;
    let lastReload = 0;
    const reloadSoon = () => {
      if (queuedReload !== null) return;
      queuedReload = window.setTimeout(() => {
        queuedReload = null;
        lastReload = Date.now();
        void loadSession(selected);
      }, Math.max(0, lastReload + 1000 - Date.now()));
    };
    const live = openLive(`/ws/admin/sessions/${selected}`, message => {
      if (message.type === 'OCCUPANCY') { setOccupancy(message as unknown as Occupancy); setUpdatedAt(new Date()); }
      if (message.type === 'MOVEMENT') reloadSoon();
      if (['CHAT', 'CHAT_REPORT', 'CHAT_HIDE', 'CHAT_LIKE', 'ADS'].includes(message.type)) setRoomTick(value => value + 1);
    }, setLiveState);
    const timer = window.setInterval(() => { void loadSession(selected); }, 30000);
    return () => {
      live.close();
      window.clearInterval(timer);
      if (queuedReload !== null) window.clearTimeout(queuedReload);
    };
  }, [selected, loadSession]);

  // How many actions are on their way. The buttons that change something wait for them,
  // so a second click does not send the same thing twice.
  const [pending, setPending] = useState(0);
  const busy = pending > 0;

  /** Runs one console action; says whether it went through, having shown why if not. */
  async function act(action: () => Promise<void>): Promise<boolean> {
    setError(''); setNotice('');
    setPending(value => value + 1);
    try { await action(); return true; }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); return false; }
    finally { setPending(value => value - 1); }
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

  /** Sends the chosen tickets their existing links again; nothing is rotated. */
  const resendLinks = (ticketIds: number[]) => act(async () => {
    const result = await read(await mutate(`/api/admin/sessions/${selected}/tickets/resend`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ticketIds }),
    }));
    const skipped = result.skipped as Array<{ ticketRef: string | null; reason: string }>;
    setNotice(`${result.sent}장에 입장권 링크를 다시 보냈어요.`
      + (skipped.length > 0 ? ` ${skipped.length}장은 건너뜀: ${skipped[0].reason}` : ''));
    setChecked(new Set());
    await loadSession(selected!);
  });

  /** Which addresses in a list already hold a ticket here, before the list is sent. */
  const checkTaken = useCallback(async (emails: string[]) => {
    const result = await read(await mutate(`/api/admin/sessions/${selected}/tickets/check`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ emails }),
    }));
    return new Set<string>(result.issued as string[]);
  }, [selected]);

  const setManyRevoked = (ticketIds: number[], revoked: boolean) => act(async () => {
    const result = await read(await mutate(`/api/admin/sessions/${selected}/tickets/status`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ticketIds, revoked }),
    }));
    setNotice(revoked
      ? `${result.changed}장을 비활성화했어요. 링크가 열리지 않고 게이트도 거부합니다.`
      : `${result.changed}장을 다시 사용할 수 있어요.`);
    setChecked(new Set());
    setConfirmBulkOff(null);
    await loadSession(selected!);
  });

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
    setNotice('이벤트를 삭제했어요.');
    await loadSessions();
    navigate('/tickets');
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

  const openGrantSheet = (gate: GateRow) => {
    setGrantDraft(new Map((gate.grants ?? []).map(grant => [grant.offerId, grant.auto])));
    setGrantGate(gate);
  };

  const saveGrants = () => {
    const gate = grantGate;
    if (!gate) return;
    // A terminal that admits nobody cannot give on entry, whatever it was set to before.
    const items = [...grantDraft.entries()]
      .map(([offerId, auto]) => ({ offerId, auto: auto && admitsPeople(gate) }));
    void act(async () => {
      await read(await mutate(`/api/admin/gates/${gate.gateId}/grants`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items }),
      }));
      // Closed only once it has saved: a refusal leaves the choices where they were.
      setGrantGate(null);
      setNotice(items.length === 0
        ? `${gate.label || gate.gateId} 단말은 이제 쿠폰을 부여하지 않아요.`
        : `${gate.label || gate.gateId} 단말이 쿠폰 ${items.length}종을 부여할 수 있어요.`);
      await loadSession(selected!);
    });
  };

  useEffect(() => {
    if (!grantGate) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setGrantGate(null); };
    window.addEventListener('keydown', onKey);
    const kept = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = kept;
    };
  }, [grantGate]);

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

  const removeBooth = (booth: BoothRow) => act(async () => {
    await read(await mutate(`/api/admin/booths/${booth.boothId}`, { method: 'DELETE' }));
    setNotice(`${booth.name} 부스와 그 쿠폰을 지웠어요.`);
    await loadSession(selected!);
  });

  /** Gives one of a booth's offers to everybody, or to the people the operator picked. */
  const giveCoupons = (offerId: number, ticketIds: number[], quantity: number,
      match?: { field: string; value: string }) =>
    act(async () => {
      const result = await read(await mutate(`/api/admin/sessions/${selected}/coupons`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ offerId, ticketIds, quantity, ...(match ? { match } : {}) }),
      }));
      const each = quantity > 1 ? ` (1인당 ${quantity}장까지)` : '';
      setNotice(result.given > 0
        ? `${result.title} 쿠폰을 입장권 ${result.tickets}장에 모두 ${result.given}장 발급했어요${each}`
          + (result.already > 0 ? ` · 이미 다 가진 ${result.already}명은 그대로예요.` : '.')
        : `모두 이미 ${result.title} 쿠폰을 ${quantity}장 이상 가지고 있어 새로 발급하지 않았어요.`);
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

  /** Only booths with something switched on can be picked in the sheet. */
  const givingBooths = booths.filter(booth => booth.offers.some(offer => offer.active));

  /** Opens the sheet, for one named person or for a choice made inside it. */
  const openCouponSheet = (only: TicketRow | null, boothId?: number, ids?: number[]) => {
    const booth = givingBooths.find(row => row.boothId === boothId) ?? givingBooths[0] ?? null;
    setPickTickets(false);
    setMatchMode(false);
    setMatchField('');
    setMatchValue('');
    setChosen(new Map());
    setPickQuery('');
    setSheetError('');
    setSheetBooth(booth?.boothId ?? null);
    setSheetOffer(booth?.offers.find(offer => offer.active)?.offerId ?? null);
    setSheetQuantity(1);
    setCouponSheet({ only, boothId, ids });
  };

  const issueCoupons = () => {
    const only = couponSheet?.only;
    if (sheetOffer === null) {
      setSheetError('발급할 쿠폰을 골라 주세요.');
      return;
    }
    const given = couponSheet?.ids;
    const ids = only ? [only.ticketId] : given ? given : pickTickets ? [...chosen.keys()] : [];
    if (!only && !given && pickTickets && ids.length === 0) {
      setSheetError('쿠폰을 받을 입장권을 선택해 주세요.');
      return;
    }
    const match = !only && !given && matchMode ? { field: matchField, value: matchValue } : undefined;
    if (match && (!match.field || !match.value)) {
      setSheetError('조건으로 쓸 항목과 값을 골라 주세요.');
      return;
    }
    if (!Number.isInteger(sheetQuantity) || sheetQuantity < 1 || sheetQuantity > MAX_PER_TICKET) {
      setSheetError(`1인당 장수는 1장부터 ${MAX_PER_TICKET}장까지예요.`);
      return;
    }
    setCouponSheet(null);
    void giveCoupons(sheetOffer, ids, sheetQuantity, match);
  };

  // The recipient list in the sheet is a search, not the whole event: it reads the
  // first matches for what was typed, and only live tickets.
  const pickSearch = useDebounced(pickQuery.trim());
  useEffect(() => {
    if (!couponSheet || couponSheet.only || !pickTickets || selected === null) return;
    const query = new URLSearchParams({ page: '0', size: '30', filter: 'LIVE' });
    if (pickSearch) query.set('q', pickSearch);
    let current = true;
    fetch(`/api/admin/sessions/${selected}/tickets?${query}`).then(read)
      .then(page => { if (current) setPickPage(page); })
      .catch(() => { /* the box stays as it was; the operator can type again */ });
    return () => { current = false; };
  }, [couponSheet, pickTickets, pickSearch, selected]);

  const togglePick = (row: TicketRow) => {
    setSheetError('');
    setChosen(current => {
      const next = new Map(current);
      if (next.has(row.ticketId)) next.delete(row.ticketId); else next.set(row.ticketId, row);
      return next;
    });
  };

  const releaseGate = (gateId: string) => act(async () => {
    await read(await mutate(`/api/admin/gates/${gateId}/release`, { method: 'POST' }));
    setNotice(`${gateId}의 단말 연결을 해제했어요.`);
    await loadSession(selected!);
  });

  /** Copies a setup link, and says whether it worked: an insecure page has no clipboard. */
  const copyLink = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      setError('');
      setNotice('설정 링크를 복사했어요.');
    } catch {
      setError('링크를 복사하지 못했어요. 링크를 직접 선택해 복사해 주세요.');
    }
  };

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
  const saveCrowding = (data: FormData) => act(async () => {
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
    // Two requests, one after the other: when only the first lands, the operator is told
    // which half stuck, and the form stays changed so saving again sends both.
    try {
      await read(await mutate(`/api/admin/sessions/${selected}/zones`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ zones }),
      }));
    } catch (err) {
      void loadSessions();
      throw new Error('혼잡 기준(%)은 저장했지만 장소별 정원은 저장하지 못했어요. '
        + (err instanceof Error ? err.message : ''));
    }
    setNotice('혼잡도 기준을 저장했어요.');
    await loadSessions();
  });

  // Turning an event into a test shows every recipient's address in full, so it asks;
  // the save waits on the answer, and a cancel leaves the form unsaved.
  const [confirmTest, setConfirmTest] = useState<{ data: FormData; answer: (saved: boolean) => void } | null>(null);
  const submitDetails = (data: FormData): Promise<boolean> => {
    const turningOn = data.get('testEvent') === 'on' && !session?.testEvent;
    if (!turningOn) return saveDetails(data);
    return new Promise(answer => setConfirmTest({ data, answer }));
  };

  const saveDetails = (data: FormData) => act(async () => {
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
        testEvent: data.get('testEvent') === 'on' ? 'true' : 'false',
      }),
    }));
    setNotice('이벤트 정보를 저장했어요. 발급된 입장권에도 바로 반영됩니다.');
    await loadSessions();
  });

  const savePolicy = (data: FormData) => act(async () => {
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
        dayCloseExit: data.get('dayCloseExit') === 'on',
        dayCloseHour: Number(data.get('dayCloseHour') ?? 0),
      }),
    }));
    setNotice('정책을 저장했어요. 다음 스캔부터 적용됩니다.');
    await loadSessions();
  });

  /**
   * The save bar: every changed form, checked first and then saved one at a time. A form
   * is marked saved only once the server has taken it, so a refusal leaves both the bar
   * and the operator's values where they were, and the first refusal stops the rest.
   */
  const settingsSavers: Record<SettingsKey, (data: FormData) => Promise<boolean>> = {
    details: submitDetails, policy: savePolicy, crowd: saveCrowding,
  };
  const saveSettings = async () => {
    if (savingSettings) return;
    const keys = SETTINGS_ORDER.filter(key => settingsDirty.has(key));
    for (const key of keys) {
      if (settingsForms[key].current?.reportValidity() === false) return;
    }
    setSavingSettings(true);
    try {
      for (const key of keys) {
        const form = settingsForms[key].current;
        if (!form) continue;
        const edits = settingsEdits.current[key];
        if (!await settingsSavers[key](new FormData(form))) break;
        if (settingsEdits.current[key] !== edits) continue;
        setSettingsDirty(current => {
          const next = new Set(current);
          next.delete(key);
          return next;
        });
      }
    } finally { setSavingSettings(false); }
  };

  const session = sessions.find(s => s.id === selected) ?? null;
  // A seat is spoken for as soon as a ticket carries it; the database enforces the same.
  const takenSeats = new Set(summary?.seats ?? []);
  const usedTiers = new Set(summary?.tiers ?? []);
  const counts = summary?.counts ?? { all: 0, unclaimed: 0, bound: 0, inside: 0, revoked: 0, live: 0 };
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
    return new Set(summary?.attributes?.[field.label] ?? []);
  }

  const KINDS: Record<string, string> = { SEAT: '좌석 · 중복 불가', TIER: '등급', CUSTOM: '일반' };
  // Places come from the gates. A capacity left over from a gate that has since been
  // renamed still shows, so the operator can see it and clear it.
  const zoneNames = [...new Set([
    ...gates.map(gate => gate.zone).filter((zone): zone is string => !!zone),
    ...Object.keys(session?.zoneCapacity ?? {}),
  ])].sort((a, b) => a.localeCompare(b, 'ko'));
  return (
    <section className="page-section page-wide console">
      {!session && (<>
        <div className="page-head">
          <h2><span aria-current="page">이벤트</span><span className="count">{sessions.length}</span></h2>
          <span className="page-actions">
            <Link className="btn-primary" to="/tickets/sessions/new">이벤트 추가</Link>
          </span>
        </div>
        <EventTable sessions={sessions} loading={!sessionsLoaded} />
      </>)}

      {selected !== null && !session && sessionsLoaded && (
        <p className="console-none">이 이벤트를 찾을 수 없어요. <Link to="/tickets">이벤트 목록</Link>으로 돌아가세요.</p>
      )}

      {session && (
      <div className="console-layout">
        <EventNav session={session} tab={tab} issued={counts.all}
          inside={occupancy?.inside ?? counts.inside}
          gatesUp={gates.filter(gate => gate.boundDevice).length} gatesAll={gates.length} />
        <div className="console-main">
          <div className="console-head">
            <div>
              <h2>{TAB_TITLES[tab]}</h2>
              <p>{dayTime(session.startsAt)}{session.gateOpensAt ? ` · 입장 ${clock(session.gateOpensAt)}부터` : ''}
                {session.venue ? ` · ${session.venue}` : ''}{session.testEvent ? ' · 테스트 이벤트' : ''}</p>
            </div>
            <div className="console-actions">
              {(tab === 'movements' || (tab === 'overview' && eventWhen(session.startsAt) === 'today')) && (
                <span className={`live-state live-${liveState}`} role="status">
                  {liveState === 'open' ? '실시간 연결됨' : liveState === 'retrying' ? '연결이 끊겨 다시 연결하는 중' : '연결 중'}
                  {updatedAt && ` · ${[updatedAt.getHours(), updatedAt.getMinutes(), updatedAt.getSeconds()].map(n => String(n).padStart(2, '0')).join(':')} 갱신`}
                </span>
              )}
              {tab === 'overview' && eventWhen(session.startsAt) === 'today' && (
                <Link className="btn-primary" to="?tab=movements">실시간 입·퇴장 보기</Link>
              )}
              {tab === 'overview' && eventWhen(session.startsAt) === 'upcoming' && (
                <Link className="btn-primary" to="?tab=issue">입장권 발급</Link>
              )}
              {tab === 'tickets' && <Link className="btn-primary" to="?tab=issue">입장권 발급</Link>}
              {tab === 'coupons' && (
                <button className="btn-primary" type="button" disabled={givingBooths.length === 0}
                  onClick={() => openCouponSheet(null, boothTab ?? undefined)}>쿠폰 발급</button>
              )}
            </div>
          </div>


          {tab === 'overview' && (
            <EventOverview session={session} summary={summary} occupancy={occupancy} gates={gates} />
          )}
          {tab === 'chat' && (
            <ConsoleChat sessionId={session.id} tick={roomTick} onNotice={setNotice} onError={setError} />
          )}
          {tab === 'ads' && (
            <ConsoleAds sessionId={session.id} booths={booths} tick={roomTick} onNotice={setNotice} onError={setError} />
          )}

      {session && tab === 'issue' && (
        <div className="console-split">
          {/* The list is the usual way in; the form beside it is for the one late addition. */}
          <section className="console-card issue-bulk" aria-label="명단으로 여러 장 발급">
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
              onSubmit={issueBulk} checkTaken={checkTaken} />
          </section>
          <aside className="console-aside">
            <form className="console-card" onSubmit={e => { e.preventDefault(); issueTicket(e.currentTarget); }}>
              <h3>한 장 발급</h3>
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
                <p className="hint-text form-note">
                  발급 항목이 없습니다. 설정 <Icon name="arrow-right" /> 발급 항목에서 좌석·등급이든 원하는 이름이든 추가하면
                  여기에서 고를 수 있어요.
                </p>
              )}
              <div className="field">
                <label htmlFor="ticket-phone">휴대폰 (선택 · 문자 발송)</label>
                <input id="ticket-phone" name="phone" inputMode="tel" placeholder="01012345678" />
              </div>
              <button className="btn-secondary" type="submit" disabled={busy}>한 장 발급하기</button>
              {issued?.from === 'issue' && (
                <IssuedLink url={issued.url}
                  note={`${issued.ticketRef} · 이 링크는 지금만 보여집니다. 다시 보려면 새로 발급해야 해요.`} />
              )}
            </form>
            <div className="console-card">
              <h3>이 이벤트</h3>
              <dl className="ticket-meta">
              <dt>발급</dt><dd>{counts.all.toLocaleString()}장</dd>
              <dt>등록 완료</dt><dd>{counts.bound.toLocaleString()}장</dd>
              <dt>남은 좌석</dt>
              <dd>{session.seats.length > 0 ? `${session.seats.length - takenSeats.size}석` : '제한 없음'}</dd>
              <dt>발급 항목</dt>
              <dd>{fieldList.length === 0 ? '없음' : fieldList.map(f => f.label).join(' · ')}</dd>
              </dl>
            </div>
          </aside>
        </div>
      )}

      {session && tab === 'tickets' && (() => {
        const rows = ticketPage.items;
        const allOn = rows.length > 0 && rows.every(row => checked.has(row.ticketId));
        const toggle = (id: number) => setChecked(current => {
          const next = new Set(current);
          if (next.has(id)) next.delete(id); else next.add(id);
          return next;
        });
        const picked = rows.filter(row => checked.has(row.ticketId));
        const ids = picked.map(row => row.ticketId);
        const anyOff = picked.some(row => row.status === 'REVOKED');
        const anyOn = picked.some(row => row.status !== 'REVOKED');
        const columns = 7 + fieldList.length;
        const open = openTicket;
        return (
        <div className="console-stack">
          {/* The chips are the labels on the rows: choosing one shows exactly the rows
              wearing it, and each says how many there are before it is chosen. */}
          <ListFilter<TicketFilter>
            query={ticketQuery} onQuery={setTicketQuery}
            placeholder="참조·좌석·등급·이메일·휴대폰으로 찾기"
            filter={ticketFilter} onFilter={setTicketFilter}
            filters={[
              ['ALL', '전체', counts.all],
              ['UNCLAIMED', '미등록', counts.unclaimed],
              ['BOUND', '등록 완료', counts.bound],
              ['INSIDE', '장내', counts.inside],
              ['REVOKED', '비활성', counts.revoked],
            ]} />

          {checked.size > 0 && (
            <div className="bulk-bar" role="region" aria-label="선택한 입장권 작업">
              <b>{checked.size.toLocaleString()}장 선택됨</b>
              <button type="button" disabled={!anyOn} onClick={() => resendLinks(ids.filter(id =>
                picked.find(row => row.ticketId === id)?.status !== 'REVOKED'))}>링크 다시 보내기</button>
              <button type="button" disabled={!anyOn || givingBooths.length === 0}
                onClick={() => openCouponSheet(null, undefined, picked.filter(row => row.status !== 'REVOKED').map(row => row.ticketId))}>
                쿠폰 주기</button>
              {anyOff && (
                <button type="button" onClick={() => setManyRevoked(picked.filter(row => row.status === 'REVOKED').map(row => row.ticketId), false)}>
                  다시 사용하기</button>
              )}
              <span className="bulk-gap" />
              <button type="button" className="danger" disabled={!anyOn}
                onClick={() => setConfirmBulkOff(picked.filter(row => row.status !== 'REVOKED').map(row => row.ticketId))}>
                비활성화…</button>
              <button type="button" className="quiet" onClick={() => setChecked(new Set())}>선택 해제</button>
            </div>
          )}

          <div className={`tickets-layout${open ? ' with-detail' : ''}`}>
            <div className="console-table-box">
              <table className="console-table ticket-list">
                <thead>
                  <tr>
                    <th className="check">
                      <input type="checkbox" aria-label="이 페이지 전체 선택" checked={allOn}
                        onChange={() => setChecked(allOn ? new Set() : new Set(rows.map(row => row.ticketId)))} />
                    </th>
                    <th>입장권</th>
                    {fieldList.map(field => <th key={field.id}>{field.label}</th>)}
                    <th>상태</th><th>입장 상태</th><th>받는 사람</th><th>전달</th><th className="link-col" aria-label="링크" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map(row => {
                    const state = ticketState(row);
                    const presence = presenceState(row);
                    const isOpen = open?.ticketId === row.ticketId;
                    return (
                      <tr key={row.ticketId} className={`${row.status === 'REVOKED' ? 'is-off' : ''}${isOpen ? ' is-open' : ''}${checked.has(row.ticketId) ? ' is-checked' : ''}`}>
                        <td className="check">
                          <input type="checkbox" aria-label={`${row.ticketRef} 선택`} checked={checked.has(row.ticketId)}
                            onChange={() => toggle(row.ticketId)} />
                        </td>
                        <td className="ref-col">
                          <button type="button" className="ticket-ref" aria-expanded={isOpen}
                            onClick={() => setOpenTicket(isOpen ? null : row)}>{row.ticketRef}</button>
                        </td>
                        {fieldList.map(field => <td key={field.id} className="field-col">{valueOf(row, field) ?? '–'}</td>)}
                        <td className="state-col"><span className={`pill pill-${state.tone}`}>{state.label}</span></td>
                        {/* One line: where the holder is and since when, so a row stays one row high. */}
                        <td className="presence-col">
                          <span className={`pill pill-${presence.tone}`}>
                            {presence.label}{presence.since ? ` · ${clock(presence.since)}` : ''}</span>
                        </td>
                        <td className="muted email-col">{row.holderEmail ?? row.issuedToEmail}</td>
                        <td className="muted delivery-col">{DELIVERY[row.deliveredVia ?? ''] ?? row.deliveredVia ?? '–'}</td>
                        <td className="row-actions link-col">
                          <button type="button" className="btn-tiny" onClick={() => { setOpenTicket(row); void showLink(row.ticketId); }}>
                            링크 보기</button>
                        </td>
                      </tr>
                    );
                  })}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={columns} className="console-none">
                        {ticketSearch || ticketFilter !== 'ALL' ? '조건에 맞는 입장권이 없어요.'
                          : <>아직 발급한 입장권이 없어요. <Link to="?tab=issue">입장권 발급</Link>에서 시작하세요.</>}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
              <Pager page={ticketPage} unit="장" onPage={setTicketPageNo} />
            </div>

            {open && <div className="ticket-detail-scrim" aria-hidden="true" onClick={() => setOpenTicket(null)} />}
            {open && (
              <aside className="ticket-detail" aria-label={`${open.ticketRef} 상세`}>
                <div className="ticket-detail-head">
                  <div>
                    <small>{open.ticketRef}</small>
                    <h3>{fieldList.map(field => valueOf(open, field)).filter(Boolean).join(' · ') || '입장권'}</h3>
                  </div>
                  <button type="button" className="sheet-close" aria-label="상세 닫기" onClick={() => setOpenTicket(null)}>✕</button>
                </div>
                <div className="ticket-detail-tags">
                  <span className={`pill pill-${ticketState(open).tone}`}>{ticketState(open).label}</span>
                  <span className={`pill pill-${presenceState(open).tone}`}>
                    {presenceState(open).label}{presenceState(open).since ? ` · ${clock(presenceState(open).since)}부터` : ''}</span>
                </div>
                <dl className="ticket-detail-facts">
                  <div><dt>받는 사람</dt><dd>{open.holderEmail ?? open.issuedToEmail}</dd></div>
                  <div><dt>등록</dt><dd>{history?.boundAt ? shortTime(history.boundAt) : open.holderEmail ? '등록됨' : '아직 안 함'}</dd></div>
                  <div><dt>전달</dt><dd>{DELIVERY[open.deliveredVia ?? ''] ?? open.deliveredVia ?? '–'}
                    {open.reissueCount > 0 ? ` · 재발급 ${open.reissueCount}회` : ''}</dd></div>
                  {open.phone && <div><dt>휴대폰</dt><dd>{open.phone}</dd></div>}
                  <div><dt>입장</dt><dd>{open.entryCount}회{open.reentryCount > 0 ? ` (재입장 ${open.reentryCount})` : ''}</dd></div>
                </dl>

                {issued?.from === 'row' && issued.ticketId === open.ticketId && (
                  <IssuedLink url={issued.url} note="이 입장권의 링크입니다. 보는 것만으로는 아무것도 바뀌지 않아요." />
                )}

                <section className="ticket-detail-part">
                  <h4>입·퇴장 기록</h4>
                  {(history?.movements ?? []).length === 0 ? <p className="console-none">기록이 없어요.</p> : (
                    <ol className="timeline">
                      {history!.movements.slice(0, 8).map((move, index) => (
                        <li key={index}>
                          <span className="muted">{clock(move.occurredAt)}</span>
                          <span className={`pill pill-${outcomeTone(move.result)}`}>{OUTCOMES[move.result] ?? move.result}</span>
                          <span>{move.gateLabel || move.gateId || '–'}</span>
                          {move.reason && <small className="cell-note">{move.reason}</small>}
                        </li>
                      ))}
                    </ol>
                  )}
                </section>

                <section className="ticket-detail-part">
                  <h4>쿠폰</h4>
                  {(history?.coupons ?? []).length === 0 ? <p className="console-none">받은 쿠폰이 없어요.</p> : (
                    <ul className="timeline">
                      {history!.coupons.map(coupon => (
                        <li key={coupon.couponId}>
                          <span>{coupon.title}</span>
                          <span className={`pill pill-${coupon.status === 'REDEEMED' ? 'ok' : coupon.status === 'VOID' ? 'off' : 'wait'}`}>
                            {coupon.status === 'REDEEMED' ? '사용함' : coupon.status === 'VOID' ? '무효' : '미사용'}</span>
                          {coupon.booth && <small className="cell-note">{coupon.booth}</small>}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>

                <div className="ticket-detail-actions">
                  <button type="button" className="btn-secondary" onClick={() => showLink(open.ticketId)}>링크 보기</button>
                  <button type="button" className="btn-secondary" disabled={open.status === 'REVOKED'}
                    onClick={() => resendLinks([open.ticketId])}>링크 다시 보내기</button>
                  {givingBooths.length > 0 && open.status !== 'REVOKED' && (
                    <button type="button" className="btn-secondary" onClick={() => openCouponSheet(open)}>쿠폰 주기</button>
                  )}
                </div>
                <div className="ticket-detail-danger">
                  <h4>되돌리기 어려운 작업</h4>
                  <button type="button" onClick={() => setConfirmReissue({ row: open, notify: true })}>
                    새 링크로 재발급 <small>이전 링크와 등록한 기기가 끊겨요</small></button>
                  {open.status === 'REVOKED' ? (
                    <button type="button" onClick={() => setRevoked(open.ticketId, false)}>
                      다시 사용하기 <small>링크와 게이트가 다시 받아들여요</small></button>
                  ) : (
                    <button type="button" onClick={() => setRevoked(open.ticketId, true)}>
                      비활성화 <small>링크가 열리지 않고 게이트가 거부해요</small></button>
                  )}
                  <button type="button" className="danger" onClick={() => setConfirmTicket(open)}>
                    삭제 <small>같은 이메일로 다시 발급할 수 있게 돼요</small></button>
                </div>
              </aside>
            )}
          </div>
          <p className="hint-text console-help">
            <b>링크 다시 보내기</b>는 처음 보낸 링크를 그대로 다시 보냅니다. <b>새 링크로 재발급</b>은 새 링크를 만들고 그 순간
            이전 링크를 닫아요. 비활성화해도 이미 장내에 있는 사람을 내보내지는 않아요.
          </p>
        </div>
        );
      })()}

      {session && tab === 'gates' && (() => {
        // A gate that needs someone goes first: no tablet, then a date about to run out.
        const rank = (gate: GateRow) => !gate.boundDevice ? 0 : ['warn', 'off'].includes(validity(gate).tone) ? 1 : 2;
        const sorted = [...gates].sort((a, b) => rank(a) - rank(b));
        const offline = gates.filter(gate => !gate.boundDevice).length;
        const expiring = gates.filter(gate => ['warn', 'off'].includes(validity(gate).tone)).length;
        return (
          <div className="console-stack">
            {gates.length > 0 && (
              <p className="console-summary">
                연결됨 {gates.length - offline} · 연결 안 됨 {offline} · 유효기간 임박 {expiring}
              </p>
            )}
            <div className="gate-grid">
              {sorted.map(gate => {
                const valid = validity(gate);
                const problem = rank(gate);
                return (
                  <article key={gate.gateId} className={`gate-card${problem === 0 ? ' is-offline' : problem === 1 ? ' is-expiring' : ''}`}>
                    <div className="gate-card-head">
                      <div>
                        <h3>{gate.label || gate.gateId}</h3>
                        <div className="gate-card-tags">
                          <span className={`pill pill-${gate.role === 'BOOTH' ? 'warn' : gate.direction === 'OUT' ? 'out' : 'ok'}`}>
                            {gate.role === 'BOOTH' ? '부스 단말' : DIRECTION_LABEL[gate.direction] ?? gate.direction}</span>
                          <span className="muted">
                            {gate.role === 'BOOTH'
                              ? booths.find(booth => booth.boothId === gate.boothId)?.name ?? '부스 없음'
                              : gate.zone || '장소 없음'}
                          </span>
                        </div>
                      </div>
                      <RowMenu label={`${gate.label || gate.gateId} 관리`} items={[
                        { label: '쿠폰 부여 설정', onSelect: () => openGrantSheet(gate) },
                        'separator',
                        { label: '설정 링크 재발급', onSelect: () => reopenSetup(gate.gateId) },
                        { label: '유효기간 연장', onSelect: () => renewGate(gate.gateId) },
                        ...(gate.tokenExpiresAt !== null
                          ? [{ label: '유효기간 없애기', onSelect: () => renewGate(gate.gateId, 0) }] : []),
                        { label: '단말 연결 해제', onSelect: () => setConfirmGate({ gate, action: 'release' }), disabled: !gate.boundDevice },
                        'separator',
                        { label: '토큰 폐기', onSelect: () => setConfirmGate({ gate, action: 'rotate' }), danger: true },
                      ]} />
                    </div>
                    <div className={`gate-status${gate.boundDevice ? ' on' : ''}`}>
                      <span className="gate-dot" aria-hidden="true" />
                      <b>{gate.boundDevice ? '연결됨' : '연결 안 됨'}</b>
                      <span className="muted">
                        {gate.boundDevice
                          ? `단말 ${gate.boundDevice}…${gate.lastSeenAt ? ` · 최근 ${clock(gate.lastSeenAt)}` : ''}`
                          : '태블릿에서 설정 링크를 열어 주세요'}
                      </span>
                    </div>
                    <dl className="gate-facts">
                      <div><dt>유효기간</dt><dd className={`tone-${valid.tone}`}>
                        {gate.tokenExpiresAt ? `${new Date(gate.tokenExpiresAt).toLocaleDateString('ko-KR')}까지` : '기한 없음'}
                        <small>{valid.label.replace('유효기간 ', '')}</small></dd></div>
                      <div><dt>쿠폰</dt><dd>{(gate.grants ?? []).length === 0 ? '없음'
                        : `${gate.grants.length}종${gate.grants.some(grant => grant.auto) ? ` · 입장 시 자동 ${gate.grants.filter(grant => grant.auto).length}` : ''}`}</dd></div>
                      {!gate.boundDevice && gate.setupCode && (
                        <div><dt>인증번호</dt><dd className="mono setup-code">
                          {/* Hidden until asked for: with the link, this number makes a tablet a gate,
                              and a shared screen or a screenshot should not hand that out. */}
                          {shownCodes.has(gate.gateId) ? gate.setupCode : '••• •••'}
                          <button type="button" className="btn-tiny" aria-pressed={shownCodes.has(gate.gateId)}
                            onClick={() => setShownCodes(current => {
                              const next = new Set(current);
                              if (next.has(gate.gateId)) next.delete(gate.gateId); else next.add(gate.gateId);
                              return next;
                            })}>{shownCodes.has(gate.gateId) ? '가리기' : '보기'}</button>
                        </dd></div>
                      )}
                    </dl>
                    <div className="gate-card-actions">
                      {!gate.boundDevice ? (<>
                        <button className="btn-primary" type="button" disabled={!gate.setupUrl}
                          onClick={() => setSetupQr({ name: gate.label || gate.gateId, url: gate.setupUrl!,
                            code: gate.setupCode, expiresAt: gate.setupExpiresAt })}>QR로 연결</button>
                        <button className="btn-secondary" type="button" disabled={!gate.setupUrl}
                          onClick={() => void copyLink(gate.setupUrl ?? '')}>링크 복사</button>
                      </>) : problem === 1 ? (
                        <button className="btn-secondary" type="button" onClick={() => renewGate(gate.gateId)}>유효기간 연장</button>
                      ) : (
                        <button className="btn-secondary" type="button" onClick={() => openGrantSheet(gate)}>쿠폰 부여 설정</button>
                      )}
                      {!gate.boundDevice && !gate.setupUrl && (
                        <button className="btn-secondary" type="button" onClick={() => reopenSetup(gate.gateId)}>설정 링크 재발급</button>
                      )}
                    </div>
                  </article>
                );
              })}
            </div>
            {gates.length === 0 && (
              <p className="console-none">아직 게이트가 없어요. 아래에서 첫 게이트를 추가하세요.</p>
            )}
            <details className="console-card add-card" open={gates.length === 0}>
              <summary>게이트 추가</summary>
              <p className="hint-text">이름, 장소, 방향만 정하면 설정 링크와 인증번호가 만들어져요. 부스 단말이면 쿠폰을 고를 수 있어요.</p>
              <form onSubmit={e => { e.preventDefault(); registerGate(e.currentTarget); }}>
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
                  {/* A door has a way in and a way out; a booth has neither. */}
                  {gateRole !== 'BOOTH' && (
                    <div className="field">
                      <label htmlFor="gate-new-direction">방향</label>
                      <select id="gate-new-direction" name="direction" defaultValue="BIDIRECTIONAL">
                        <option value="BIDIRECTIONAL">입장·퇴장 겸용</option>
                        <option value="IN">입장 전용</option>
                        <option value="OUT">퇴장 전용</option>
                      </select>
                    </div>
                  )}
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
                <details className="more-fields">
                  <summary>게이트 ID 직접 정하기</summary>
                  <div className="field">
                    <label htmlFor="gate-new-id">게이트 ID <span className="label-note">비워 두면 자동으로 만들어요</span></label>
                    <input id="gate-new-id" name="gateId" pattern="[A-Za-z0-9_\-]{1,32}"
                      placeholder="예: main-door-1" />
                  </div>
                </details>
                <button className="btn-secondary" type="submit">게이트 추가</button>
                {gateSetup && (
                  <div className="issued-link">
                    <p className="hint-text">
                      <b>{gateSetup.gateId}</b> · 이 링크를 태블릿에서 열고 아래 인증번호를 입력하면 연결됩니다.
                    </p>
                    <code title={gateSetup.setupUrl}>{gateSetup.setupUrl}</code>
                    <p className="code-timer">인증번호 {gateSetup.setupCode}</p>
                    <div className="share-row">
                      <button type="button" onClick={() => setSetupQr({ name: gateSetup.gateId, url: gateSetup.setupUrl,
                        code: gateSetup.setupCode, expiresAt: gateSetup.expiresAt ?? null })}>QR로 연결</button>
                      <button type="button" onClick={() => void copyLink(gateSetup.setupUrl)}>
                        링크 복사
                      </button>
                    </div>
                  </div>
                )}
              </form>
            </details>
            <p className="hint-text console-help">
              태블릿에서 설정 링크를 열고 인증번호를 입력하면 연결됩니다 - 긴 토큰을 직접 입력할 일은 없어요.
              게이트 하나는 단말 한 대에만 연결됩니다. 두 대가 같은 ID를 쓰면 각자 절반의 기록만 보게 되어
              장내 상태가 어긋나기 때문이에요. 단말을 교체하려면 연결을 해제해 주세요.
              단말 토큰에는 유효기간이 있고, <b>유효기간 갱신</b>은 토큰과 연결을 그대로 둔 채 날짜만 미룹니다 -
              현장에서 쓰는 태블릿은 기간이 얼마 남지 않으면 스스로 갱신합니다. 건물에 붙박이로 설치한
              단말이라면 <b>무제한</b>으로 두고, 필요할 때 다시 기간을 줄 수 있어요.
            </p>
          </div>
        );
      })()}

      {session && tab === 'settings' && (() => {
        return (
          <div className="settings-page">
            <nav className="settings-quick" aria-label="설정 항목">
              {SETTINGS_TABS.map(([key, label]) => (
                <a key={key} href={`#settings-${key}`}>{label}</a>
              ))}
              <a className="danger" href="#settings-delete">이벤트 삭제</a>
            </nav>
            <section id="settings-event" className="settings-section" aria-labelledby="settings-event-title">
              <div className="settings-side">
                <h3 id="settings-event-title">이벤트 정보</h3>
                <p>관람객 입장권과 안내 메일에 그대로 보이는 정보입니다.</p>
              </div>
              <div className="settings-body">
            <form key={`details-${session.id}`} className="settings-form" ref={settingsForms.details}
              onChange={() => markSettingsDirty('details')}
              onSubmit={e => { e.preventDefault(); void saveSettings(); }}>
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
                  <label htmlFor="event-starts">이벤트 시각</label>
                  <input id="event-starts" name="startsAt" type="datetime-local" required
                    defaultValue={localInput(session.startsAt)} />
                </div>
                <div className="field">
                  <label htmlFor="event-gate">입장 시각 (선택)</label>
                  <input id="event-gate" name="gateOpensAt" type="datetime-local"
                    defaultValue={localInput(session.gateOpensAt)} />
                </div>
              </div>
              <label className="field-inline">
                <input name="testEvent" type="checkbox" defaultChecked={session.testEvent} />
                테스트 이벤트
              </label>
              <p className="hint-text">
                테스트 이벤트는 발급 현황과 쿠폰 목록에서 이메일을 가리지 않고 전부 보여줍니다. 실제 관람객
                명단이 들어간 이벤트에는 켜지 마세요.
              </p>
              <p className="hint-text">
                입장 시각을 지정하면 그 전에는 게이트가 입장을 거부합니다. 비워 두면 언제든 입장할 수
                있어요. 시각을 바꿔도 이미 발급한 입장권은 그대로 쓰입니다.
              </p>


            </form>
              </div>
            </section>
            <section id="settings-policy" className="settings-section" aria-labelledby="settings-policy-title">
              <div className="settings-side">
                <h3 id="settings-policy-title">재입장 · 퇴장</h3>
                <p>입장한 사람이 다시 들어올 수 있는지, 퇴장을 찍지 않은 사람을 언제 내보낼지 정합니다. 저장하면 다음 스캔부터 바로 적용돼요.</p>
              </div>
              <div className="settings-body">
            <form key={`policy-${session.id}`} className="settings-form" ref={settingsForms.policy}
              onChange={() => markSettingsDirty('policy')}
              onSubmit={e => { e.preventDefault(); void saveSettings(); }}>
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
                  {Object.entries(UNMATCHED_LABEL).map(([value, label]) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </div>
              <label className="field-inline">
                <input name="exitScanRequired" type="checkbox" defaultChecked={session.exitScanRequired} />
                퇴장 스캔 필수
              </label>
              <div className="form-row day-close-row">
                <label className="field-inline">
                  <input name="dayCloseExit" type="checkbox" defaultChecked={session.dayCloseExit} />
                  날이 바뀌면 자동 퇴장
                </label>
                <div className="field">
                  <label htmlFor="day-close-hour">날짜 기준 시각</label>
                  <select id="day-close-hour" name="dayCloseHour" defaultValue={session.dayCloseHour}>
                    {Array.from({ length: 24 }, (_, hour) => (
                      <option key={hour} value={hour}>{hourLabel(hour)}</option>
                    ))}
                  </select>
                </div>
              </div>
              <p className="hint-text day-close-hint">
                켜 두면 퇴장을 찍지 않은 사람을 날짜 기준 시각(한국 시간)이 지난 뒤 몇 분 안에 퇴장
                처리합니다. 자정을 넘겨 계속되는 이벤트라면 기준 시각을 새벽으로 늦추세요.
              </p>
              <label className="field-inline">
                <input name="claimRequiresOtp" type="checkbox" defaultChecked={session.claimRequiresOtp} />
                등록 시 이메일 인증 필요
              </label>
              <p className="hint-text">
                이메일 인증을 끄면 <b>입장권을 받은 주소를 입력한 사람</b>이 보유자가 됩니다. 메일로
                뿌리는 이벤트라면 켜 두세요. 기기 재발급과 양도 취소는 정책과 무관하게 항상 이메일
                인증을 요구합니다.
              </p>
              <p className="hint-text">
                장내 상태에서의 재입장은 {session.autoExitAfterMinutes}분이 지나야 보정됩니다. 그 전에는
                어떤 정책이든 거부되어 QR 공유가 통하지 않습니다.
              </p>

            </form>
              </div>
            </section>
            <section id="settings-crowd" className="settings-section" aria-labelledby="settings-crowd-title">
              <div className="settings-side">
                <h3 id="settings-crowd-title">혼잡도</h3>
                <p>장소별 수용 인원과 혼잡 기준입니다. 관람객 입장권 화면에도 같은 단계가 보여요.</p>
              </div>
              <div className="settings-body">
            <form key={`crowd-${session.id}`} className="settings-form" ref={settingsForms.crowd}
              onChange={() => markSettingsDirty('crowd')}
              onSubmit={e => { e.preventDefault(); void saveSettings(); }}>
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

            </form>
              </div>
            </section>
            <section id="settings-fields" className="settings-section" aria-labelledby="settings-fields-title">
              <div className="settings-side">
                <h3 id="settings-fields-title">발급 항목</h3>
                <p>입장권마다 붙는 항목입니다. 좌석은 같은 값을 두 장에 줄 수 없어요.</p>
              </div>
              <div className="settings-body">
            <div className="settings-form">
              <p className="hint-text">
                입장권이 무엇을 담을지는 이벤트마다 다릅니다. 좌석과 등급도 여기서 만드는 항목 중
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
                  '좌석' 유형은 같은 값을 두 장에 발급할 수 없습니다. 이벤트마다 좌석과 등급은 하나씩만
                  둘 수 있어요.
                </p>
                <button className="btn-primary" type="submit">항목 추가</button>
              </form>
            </div>
              </div>
            </section>
            <section id="settings-delete" className="settings-section danger" aria-labelledby="settings-delete-title">
              <div className="settings-side">
                <h3 id="settings-delete-title">이벤트 삭제</h3>
                <p>입장권, 입·퇴장 기록, 등록된 단말이 함께 사라지고 되돌릴 수 없어요.</p>
              </div>
              <div className="settings-body">
                {eventWhen(session.startsAt) === 'today' && (
                  <p className="error-text">오늘 진행 중인 이벤트입니다. 이벤트가 끝난 뒤에 지우는 것을 권해요.</p>
                )}
                <button className="btn-danger" type="button" onClick={() => setConfirmDelete(true)}>이 이벤트 삭제…</button>
              </div>
            </section>
            {settingsDirty.size > 0 && (
              <div className="save-bar" role="region" aria-label="저장하지 않은 변경">
                <span>저장하지 않은 변경 · {[...settingsDirty].map(key => SETTINGS_NAMES[key]).join(', ')}</span>
                <button type="button" className="btn-secondary" disabled={savingSettings}
                  onClick={revertSettings}>되돌리기</button>
                <button type="button" className="btn-primary" disabled={savingSettings}
                  onClick={() => void saveSettings()}>{savingSettings ? '저장 중…' : '변경 사항 저장'}</button>
              </div>
            )}
          </div>
        );
      })()}

      {grantGate && (
        <div className="modal-backdrop sheet-backdrop" role="presentation" onClick={() => setGrantGate(null)}>
          <div className="modal modal-sheet" role="dialog" aria-modal="true" aria-labelledby="grant-sheet"
            onClick={event => event.stopPropagation()}>
            <form onSubmit={e => { e.preventDefault(); saveGrants(); }}>
              <header className="sheet-head">
                <div className="sheet-title">
                  <h3 id="grant-sheet">쿠폰 부여 설정</h3>
                  <p>
                    {grantGate.label || grantGate.gateId} ·{' '}
                    {grantGate.role === 'BOOTH' ? '부스 단말' : DIRECTION_LABEL[grantGate.direction] ?? grantGate.direction}
                  </p>
                </div>
                <button type="button" className="sheet-close" aria-label="닫기"
                  onClick={() => setGrantGate(null)}>✕</button>
              </header>
              <div className="sheet-body">
                <div className="sheet-inner">
                  <p className="hint-text">
                    고른 쿠폰은 단말의 <b>쿠폰 부여</b> 모드에서 QR을 찍어 종류와 장수를 정해 줄 수 있어요.
                    {admitsPeople(grantGate)
                      ? <> <b>입장 시 자동</b>을 켜면 이 단말로 입장할 때마다 1장이 되도록 채워 줍니다. 다시 들어와도 더 주지 않아요.</>
                      : ' 입장 시 자동 지급은 입장을 처리하는 단말에서만 쓸 수 있어요.'}
                  </p>
                  {booths.every(booth => booth.offers.length === 0) ? (
                    <div className="sheet-empty">
                      <b>정해 둔 쿠폰이 없어요.</b>
                      <p className="hint-text">쿠폰 탭에서 부스를 열고 <b>쿠폰 종류</b>를 먼저 추가하세요.</p>
                    </div>
                  ) : booths.filter(booth => booth.offers.length > 0).map(booth => (
                    <fieldset className="grant-group" key={booth.boothId}>
                      <legend>{booth.name}</legend>
                      {booth.offers.map(offer => {
                        const on = grantDraft.has(offer.offerId);
                        const auto = grantDraft.get(offer.offerId) === true;
                        const toggle = (next: boolean, nextAuto: boolean) => setGrantDraft(current => {
                          const copy = new Map(current);
                          if (next) copy.set(offer.offerId, nextAuto); else copy.delete(offer.offerId);
                          return copy;
                        });
                        return (
                          <div className={`grant-offer${offer.active ? '' : ' offer-off'}`} key={offer.offerId}>
                            <label className="check-line">
                              <input type="checkbox" checked={on} onChange={e => toggle(e.target.checked, false)} />
                              <span>
                                <b>{offer.title}</b>
                                {!offer.active && <span className="pill pill-off">발급 중지</span>}
                                {offer.detail && <small>{offer.detail}</small>}
                              </span>
                            </label>
                            {admitsPeople(grantGate) && (
                              <label className="check-line grant-auto">
                                <input type="checkbox" checked={auto} disabled={!on}
                                  onChange={e => toggle(true, e.target.checked)} />
                                <span>입장 시 자동</span>
                              </label>
                            )}
                          </div>
                        );
                      })}
                    </fieldset>
                  ))}
                  <p className="hint-text">발급을 중지한 쿠폰은 골라 두어도 단말에 보이지 않아요.</p>
                </div>
              </div>
              <footer className="sheet-foot">
                <button type="button" className="btn-secondary" onClick={() => setGrantGate(null)}>취소</button>
                <button type="submit" className="btn-primary">저장</button>
              </footer>
            </form>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmTest != null}
        title="테스트 이벤트로 바꿀까요?"
        message={`발급 현황과 쿠폰 목록에 받는 사람의 이메일이 가리지 않고 전부 보입니다. `
          + `실제 관람객 명단이 들어 있는 이벤트라면 취소하세요.`
          + (session?.ticketCount ? ` 지금 입장권 ${session.ticketCount.toLocaleString()}장이 발급되어 있어요.` : '')}
        confirmLabel="테스트 이벤트로 저장"
        onConfirm={() => {
          if (!confirmTest) return;
          const { data, answer } = confirmTest;
          setConfirmTest(null);
          void saveDetails(data).then(answer);
        }}
        onCancel={() => { confirmTest?.answer(false); setConfirmTest(null); }}
      />

      <ConfirmDialog
        open={confirmField != null}
        title={`'${confirmField?.label}' 항목을 지울까요?`}
        message={'발급 화면과 목록에서 이 항목이 사라집니다. 이미 발급된 입장권은 받은 값을 그대로 '
          + '유지하고, 항목을 다시 만들면 값 목록부터 새로 정하게 됩니다.'}
        confirmLabel="항목 삭제"
        busy={busy}
        onConfirm={() => confirmField && removeField(confirmField)}
        onCancel={() => setConfirmField(null)}
      />

      <ConfirmDialog
        open={confirmReissue != null}
        title={confirmReissue?.notify ? '새 링크로 재발급하고 보낼까요?' : '새 링크를 발급할까요?'}
        message={'이미 보낸 링크는 이 순간부터 열리지 않고, 등록된 기기도 함께 풀립니다. '
          + '보유자는 새 링크로 다시 등록해야 해요. 같은 링크를 한 번 더 보내려면 \'링크 다시 보내기\'를 쓰세요.'}
        details={confirmReissue ? [
          ['입장권', confirmReissue.row.ticketRef],
          ['받는 사람', confirmReissue.row.holderEmail ?? confirmReissue.row.issuedToEmail ?? '-'],
          ['상태', ticketState(confirmReissue.row).label],
        ] : undefined}
        confirmLabel={confirmReissue?.notify ? '재발급하고 보내기' : '재발급'}
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
            <form onSubmit={e => { e.preventDefault(); issueCoupons(); }}>
              <header className="sheet-head">
                <div className="sheet-title">
                  <h3 id="coupon-sheet">쿠폰 발급</h3>
                  <p>
                    {couponSheet.only
                      ? `${couponSheet.only.ticketRef}`
                        + `${couponSheet.only.seat ? ` · ${couponSheet.only.seat}` : ''} · `
                        + `${couponSheet.only.holderEmail ?? couponSheet.only.issuedToEmail}`
                      : couponSheet.ids ? `선택한 입장권 ${couponSheet.ids.length.toLocaleString()}장`
                      : '부스에 정해 둔 쿠폰 중 발급 중인 것만 줄 수 있어요.'}
                  </p>
                </div>
                <button type="button" className="sheet-close" aria-label="닫기"
                  onClick={() => setCouponSheet(null)}>✕</button>
              </header>

              <div className="sheet-body">
                <div className="sheet-inner">
                  {givingBooths.length === 0 ? (
                    <div className="sheet-empty">
                      <b>발급할 수 있는 쿠폰이 없어요.</b>
                      <p className="hint-text">
                        쿠폰 탭에서 부스를 열고 <b>쿠폰 종류</b>를 먼저 추가하세요. 중지한 쿠폰은 발급할 수 없어요.
                      </p>
                    </div>
                  ) : (() => {
                    const booth = givingBooths.find(row => row.boothId === sheetBooth) ?? givingBooths[0];
                    const choices = booth.offers.filter(offer => offer.active);
                    const offer = choices.find(row => row.offerId === sheetOffer) ?? null;
                    return (<>
                      <div className="form-row">
                        <div className="field">
                          <label htmlFor="give-booth">부스</label>
                          <select id="give-booth" value={booth.boothId}
                            onChange={e => {
                              const next = givingBooths.find(row => row.boothId === Number(e.target.value));
                              setSheetBooth(next?.boothId ?? null);
                              setSheetOffer(next?.offers.find(row => row.active)?.offerId ?? null);
                            }}>
                            {givingBooths.map(row => (
                              <option key={row.boothId} value={row.boothId}>{row.name}</option>
                            ))}
                          </select>
                        </div>
                        <div className="field">
                          <label htmlFor="give-offer">쿠폰</label>
                          {/* Only what this booth has, and only what is switched on: a
                              name typed here could not be handed over anywhere. */}
                          <select id="give-offer" value={offer?.offerId ?? ''}
                            onChange={e => { setSheetOffer(Number(e.target.value)); setSheetError(''); }}>
                            {choices.map(row => (
                              <option key={row.offerId} value={row.offerId}>
                                {row.title} · 발급 {row.issued.toLocaleString()}
                              </option>
                            ))}
                          </select>
                        </div>
                      </div>
                      {offer && (
                        <div className="offer-preview">
                          <span>받는 사람에게 보이는 안내</span>
                          <p>{offer.detail || '상세 안내 없음'}</p>
                        </div>
                      )}
                      <div className="field quantity-field">
                        <label htmlFor="give-quantity">1인당 장수</label>
                        <input id="give-quantity" type="number" inputMode="numeric" min={1} max={MAX_PER_TICKET}
                          value={sheetQuantity}
                          onChange={e => { setSheetQuantity(Number(e.target.value)); setSheetError(''); }} />
                        {/* A total, not an increment: sending the same batch again for
                            latecomers must not give everybody else a second helping. */}
                        <p className="hint-text">
                          입장권마다 이 쿠폰이 {Number.isFinite(sheetQuantity) && sheetQuantity > 0 ? sheetQuantity : 1}장이
                          되도록 채워요. 이미 받은 쿠폰(사용한 것 포함)도 장수에 들어가요.
                        </p>
                      </div>
                    </>);
                  })()}

                  {!couponSheet.only && !couponSheet.ids && givingBooths.length > 0 && (<>
                    <div className="field">
                      <label htmlFor="coupon-scope">받는 사람</label>
                      <select id="coupon-scope" value={pickTickets ? 'some' : matchMode ? 'match' : 'all'}
                        onChange={e => {
                          setPickTickets(e.target.value === 'some');
                          setMatchMode(e.target.value === 'match');
                          setSheetError('');
                        }}>
                        <option value="all">사용 중인 입장권 전체 ({counts.live.toLocaleString()}장)</option>
                        <option value="match" disabled={fieldList.length === 0}>
                          {fieldList.length === 0 ? '조건에 맞는 입장권 (발급 항목이 없어요)' : '조건에 맞는 입장권'}
                        </option>
                        <option value="some">골라서 주기</option>
                      </select>
                    </div>
                    {matchMode && (() => {
                      const field = fieldList.find(row => row.label === matchField) ?? null;
                      const values = field
                        ? (field.values.length > 0 ? field.values : [...valuesInUse(field)].sort())
                        : [];
                      return (
                        <div className="form-row">
                          <div className="field">
                            <label htmlFor="match-field">항목</label>
                            <select id="match-field" value={matchField}
                              onChange={e => { setMatchField(e.target.value); setMatchValue(''); setSheetError(''); }}>
                              <option value="">항목 고르기</option>
                              {fieldList.map(row => <option key={row.id} value={row.label}>{row.label}</option>)}
                            </select>
                          </div>
                          <div className="field">
                            <label htmlFor="match-value">값</label>
                            <select id="match-value" value={matchValue} disabled={!field}
                              onChange={e => { setMatchValue(e.target.value); setSheetError(''); }}>
                              <option value="">값 고르기</option>
                              {values.map(value => <option key={value} value={value}>{value}</option>)}
                            </select>
                          </div>
                        </div>
                      );
                    })()}
                    {pickTickets && (<>
                      <div className="pick-search">
                        <input type="search" value={pickQuery} placeholder="참조·좌석·이메일로 찾기"
                          aria-label="받을 입장권 찾기" onChange={e => setPickQuery(e.target.value)} />
                        <span className="pick-count">{chosen.size.toLocaleString()}장 선택</span>
                      </div>
                      {chosen.size > 0 && (
                        <div className="pick-chosen">
                          {[...chosen.values()].map(row => (
                            <button key={row.ticketId} type="button" className="pick-chip"
                              onClick={() => togglePick(row)} aria-label={`${row.ticketRef} 선택 해제`}>
                              {row.seat ?? row.ticketRef} ✕
                            </button>
                          ))}
                        </div>
                      )}
                      <div className="pick-list">
                        {pickPage.items.length === 0 && (
                          <p className="hint-text">{pickSearch ? '찾는 입장권이 없어요.' : '사용 중인 입장권이 없어요.'}</p>
                        )}
                        {pickPage.items.map(row => (
                          <label key={row.ticketId} className="pick-row">
                            <input type="checkbox" checked={chosen.has(row.ticketId)}
                              onChange={() => togglePick(row)} />
                            <b>{row.ticketRef}</b>
                            {row.seat && <span className="pick-seat">{row.seat}</span>}
                            <small>{row.holderEmail ?? row.issuedToEmail}</small>
                          </label>
                        ))}
                        {pickPage.total > pickPage.items.length && (
                          <p className="hint-text pick-more">
                            {pickPage.total.toLocaleString()}장 중 {pickPage.items.length}장만 보여요. 더 찾으려면 검색하세요.
                          </p>
                        )}
                      </div>
                    </>)}
                  </>)}

                  <p className="hint-text">
                    관람객은 쿠폰마다 <b>쿠폰 QR</b>을 열어 그 부스 단말에 비추면 한 장씩 사용됩니다. 같은 사람에게 같은 쿠폰을
                    두 번 주면 한 장만 남습니다.
                  </p>
                </div>
              </div>

              <footer className="sheet-foot">
                {sheetError && <p className="sheet-error" role="alert">{sheetError}</p>}
                <button type="button" className="btn-secondary"
                  onClick={() => setCouponSheet(null)}>취소</button>
                <button type="submit" className="btn-primary"
                  disabled={givingBooths.length === 0}>쿠폰 발급</button>
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

      {setupQr && <GateSetupQr target={setupQr} onClose={() => setSetupQr(null)} />}

      <ConfirmDialog
        open={confirmGate != null}
        title={confirmGate?.action === 'rotate'
          ? `'${confirmGate.gate.label || confirmGate.gate.gateId}' 토큰을 폐기할까요?`
          : `'${confirmGate?.gate.label || confirmGate?.gate.gateId}' 단말 연결을 해제할까요?`}
        message={confirmGate?.action === 'rotate'
          ? '지금 연결된 단말은 이 순간부터 스캔하지 못합니다. 다시 쓰려면 새 설정 링크로 연결해야 해요.'
          : '연결된 태블릿이 이 게이트에서 풀려 더 이상 스캔하지 못합니다. 같은 태블릿이나 다른 단말을 설정 링크로 다시 연결할 수 있어요.'}
        details={confirmGate ? [
          ['게이트', confirmGate.gate.label || confirmGate.gate.gateId],
          ['게이트 ID', confirmGate.gate.gateId],
          ['단말', confirmGate.gate.boundDevice ? `${confirmGate.gate.boundDevice}…` : '연결 안 됨'],
        ] : undefined}
        confirmLabel={confirmGate?.action === 'rotate' ? '토큰 폐기' : '연결 해제'}
        busy={busy}
        onConfirm={() => {
          if (!confirmGate) return;
          const { gate, action } = confirmGate;
          void (action === 'rotate' ? rotateGateToken(gate.gateId) : releaseGate(gate.gateId))
            .then(done => { if (done) setConfirmGate(null); });
        }}
        onCancel={() => setConfirmGate(null)}
      />

      <ConfirmDialog
        open={confirmBulkOff != null}
        title={`입장권 ${confirmBulkOff?.length ?? 0}장을 비활성화할까요?`}
        message={'링크가 열리지 않고 게이트도 입장을 거부합니다. 이미 장내에 있는 사람을 내보내지는 않아요. '
          + '선택한 입장권에서 다시 사용하기로 되돌릴 수 있어요.'}
        confirmLabel="비활성화"
        busy={busy}
        onConfirm={() => { if (confirmBulkOff) setManyRevoked(confirmBulkOff, true); }}
        onCancel={() => setConfirmBulkOff(null)}
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
        busy={busy}
        onConfirm={() => {
          if (!confirmTicket) return;
          const row = confirmTicket;
          void deleteTicket(row).then(done => { if (done) setConfirmTicket(null); });
        }}
        onCancel={() => setConfirmTicket(null)}
      />

      <ConfirmDialog
        open={confirmDelete && session != null}
        title="이벤트를 삭제할까요?"
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
        busy={busy}
        onConfirm={() => session && deleteSession(session.id)}
        onCancel={() => setConfirmDelete(false)}
      />

      {session && tab === 'coupons' && (
        <ConsoleCoupons key={session.id} sessionId={session.id} booths={booths} boothId={boothTab} onBooth={setBoothTab}
          onChanged={() => loadSession(session.id)} onDeleteBooth={setConfirmBooth}
          onNotice={setNotice} onError={setError} />
      )}

      {session && tab === 'movements' && (() => {
        const zones = (occupancy?.byZone ?? []).map(row => {
          const now = Math.max(0, count(row.admitted) - count(row.exited));
          const zone = text(row.zone);
          const cap = session.zoneCapacity?.[zone];
          return { row, zone, now, cap, level: crowdLevel(session, now, cap) };
        });
        const busiest = Math.max(1, ...zones.map(z => z.now));
        const unplaced = Math.max(0, (occupancy?.inside ?? 0) - zones.reduce((sum, z) => sum + z.now, 0));
        const capacity = Object.values(session.zoneCapacity ?? {}).reduce((sum, n) => sum + n, 0);
        const denied = (occupancy?.byGate ?? []).reduce((sum, row) => sum + count(row.denied), 0);
        return (
          <div className="console-stack">
            {occupancy && (
              <div className="kpi-grid">
                <div className="kpi kpi-accent">
                  <span className="kpi-label">지금 장내</span>
                  <strong className="kpi-num">{occupancy.inside.toLocaleString()}</strong>
                  <span className="kpi-note">{capacity > 0 ? `수용 ${capacity.toLocaleString()} 중 ${percent(occupancy.inside, capacity)}%` : '장소별 수용 인원 미설정'}</span>
                </div>
                <div className="kpi">
                  <span className="kpi-label">입장한 사람</span>
                  <strong className="kpi-num">{occupancy.everEntered.toLocaleString()}</strong>
                  <span className="kpi-note">등록 완료 {occupancy.bound.toLocaleString()}명 중</span>
                </div>
                <div className="kpi">
                  <span className="kpi-label">발급</span>
                  <strong className="kpi-num">{occupancy.tickets.toLocaleString()}</strong>
                  <span className="kpi-note">미입장 {Math.max(0, occupancy.tickets - occupancy.everEntered).toLocaleString()}</span>
                </div>
                <div className="kpi">
                  <span className="kpi-label">입장 거부</span>
                  <strong className={`kpi-num${denied > 0 ? ' tone-off' : ''}`}>{denied.toLocaleString()}</strong>
                  <span className="kpi-note">게이트별 표에서 확인</span>
                </div>
              </div>
            )}

            <div className="console-two">
              <section className="console-card" aria-labelledby="live-zones">
                <h3 id="live-zones">장소별 혼잡도</h3>
                {zones.length === 0 && unplaced === 0 ? (
                  <p className="console-none">등록된 게이트가 없어요.</p>
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
                    <div className="zone-meter-foot">
                      입장 {count(z.row.admitted)} · 퇴장 {count(z.row.exited)}
                      {count(z.row.denied) > 0 && ` · 거부 ${count(z.row.denied)}`}
                      {text(z.row.last_at) !== '' && ` · 마지막 ${clock(text(z.row.last_at))}`}
                    </div>
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
                <p className="console-foot">관람객 입장권 화면에도 같은 단계가 보입니다. 수용 인원은 <Link to="?tab=settings&sec=crowd">이벤트 설정</Link>에서 바꿔요.</p>
              </section>

              <section className="console-card" aria-labelledby="live-gates">
                <h3 id="live-gates">게이트별</h3>
                {(occupancy?.byGate ?? []).length === 0 ? (
                  <p className="console-none">아직 게이트를 지난 기록이 없어요.</p>
                ) : (
                  <div className="console-table-box flat">
                    <table className="console-table">
                      <thead><tr><th>게이트</th><th className="num">입장</th><th className="num">퇴장</th><th className="num">거부</th><th className="num">마지막</th></tr></thead>
                      <tbody>
                        {occupancy?.byGate.map((row, index) => (
                          <tr key={index}>
                            <td className="strong">{text(row.gate_label) || text(row.gate_id)}</td>
                            <td className="num">{count(row.admitted)}</td>
                            <td className="num">{count(row.exited)}</td>
                            <td className={`num${count(row.denied) > 0 ? ' tone-off' : ''}`}>{count(row.denied)}</td>
                            <td className="num muted">{clock(text(row.last_at) || null) || '–'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </div>

            <section className="console-card" aria-labelledby="live-log">
              <h3 id="live-log">최근 기록</h3>
              {(occupancy?.recent ?? []).length === 0 ? (
                <p className="console-none">기록이 없어요.</p>
              ) : (
                <div className="console-table-box flat">
                  <table className="console-table">
                    <thead><tr><th>시각</th><th>구분</th><th>입장권</th><th>게이트</th><th>비고</th></tr></thead>
                    <tbody>
                      {occupancy?.recent.map((row, index) => (
                        <tr key={index}>
                          <td className="muted">{shortTime(text(row.occurred_at) || null)}</td>
                          <td><span className={`pill pill-${outcomeTone(text(row.result))}`}>
                            {OUTCOMES[text(row.result)] ?? text(row.result)}</span>
                            {text(row.direction) !== '' && <small className="cell-note">{text(row.direction) === 'IN' ? '입장 방향' : '퇴장 방향'}</small>}</td>
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
      })()}
        </div>
      </div>
      )}
      <Toast notice={notice} error={error} onNotice={setNotice} onError={setError} />
    </section>
  );
}
