import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { mutate, useAuth } from '@shared/auth';

export interface ArtworkRequest {
  id: number; artworkId: number; recipientId: number; linkId: number; recipientEmail: string; artworkTitle: string;
  interested: boolean; holdState: string; holdUntil: string | null; purchaseIntent: boolean;
  purchased: boolean; cancellationRequested: boolean; version: number; updatedAt: string;
  cancelRejected: boolean; inquiryPending: boolean;
}
/** Why the request stands where it does, as the recipient is told. */
export interface RequestNotice { kind: 'hold' | 'sale' | 'inquiry'; action: string; reason: string; at: string }
interface RecipientRequest extends ArtworkRequest { notices?: RequestNotice[] }
const labels: Record<string, string> = {
  NONE: '홀딩 없음', PENDING: '홀딩 승인 대기', HELD: '홀딩 확정', REJECTED: '홀딩 거절',
  CANCELLED: '홀딩 취소', EXPIRED: '홀딩 만료', CLOSED: '요청 종료',
  INTEREST: '관심 표시', UNINTEREST: '관심 해제', REQUEST_HOLD: '홀딩 요청', CANCEL_HOLD: '홀딩 취소',
  PURCHASE: '구매 의사 전달', WITHDRAW: '구매 의사 철회', APPROVE: '홀딩 승인', REJECT: '홀딩 거절',
  RELEASE: '홀딩 해제', SELL: '판매 확정', REQUEST_CANCEL: '판매 취소 요청', CANCEL_SALE: '판매 취소 승인', KEEP_SALE: '판매 취소 거절',
  INQUIRE: '재요청 문의', ALLOW_RETRY: '재요청 허용', DECLINE_RETRY: '재요청 문의 답변',
};
/**
 * Reasons an administrator picks from, so the recipient reads the same words for the same
 * decision. "직접 입력" leaves the wording to the administrator; every choice may add detail.
 */
const CUSTOM = '직접 입력';
const reasonPresets: Record<string, string[]> = {
  REJECT: ['다른 고객과 먼저 상담 중이에요', '전시·대여 일정으로 홀딩이 어려워요', '작가 또는 소장처 요청으로 홀딩이 어려워요', '가격·판매 조건을 다시 정하고 있어요', CUSTOM],
  RELEASE: ['고객 요청으로 해제했어요', '판매 조건이 변경되었어요', '연락이 닿지 않아 해제했어요', CUSTOM],
  KEEP_SALE: ['배송·설치가 이미 진행되었어요', '취소 가능 기간이 지났어요', '계약 조건상 취소가 어려워요', CUSTOM],
  CANCEL_SALE: ['고객 요청을 받아 취소했어요', '결제가 완료되지 않았어요', CUSTOM],
  DECLINE_RETRY: ['이전 사유가 아직 해당돼요', '다른 고객과 거래가 진행 중이에요', CUSTOM],
  ALLOW_RETRY: ['다시 요청하실 수 있어요', '조건이 바뀌어 다시 검토할 수 있어요', CUSTOM],
};
const reasonRequired = ['REJECT', 'CANCEL_SALE', 'KEEP_SALE', 'DECLINE_RETRY'];
/** Shown to the recipient, unlike the internal note. */
const reasonVisible = ['REJECT', 'RELEASE', 'KEEP_SALE', 'CANCEL_SALE', 'DECLINE_RETRY', 'ALLOW_RETRY'];
const date = (value: string) => new Date(value).toLocaleString('ko-KR');
function summary(row: ArtworkRequest) {
  return [row.interested && '관심 있음', labels[row.holdState], row.purchaseIntent && !row.purchased && '구매 의사 있음',
    row.purchased && '판매 완료', row.cancellationRequested && '판매 취소 요청', row.cancelRejected && '판매 취소 거절됨',
    row.inquiryPending && '재요청 문의 중'].filter(Boolean).join(' · ');
}
async function send(url: string, row: ArtworkRequest | undefined, action: string, extra: object = {}) {
  const response = await mutate(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, version: row?.version ?? 0, key: crypto.randomUUID(), ...extra }) });
  if (!response.ok) {
    if (response.status === 403) throw new Error('인증이 만료되었거나 권한이 없습니다. 다시 로그인하거나 패스키로 링크를 열어 주세요.');
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || body.message || (response.status === 409 ? '상태가 변경되었거나 지금 처리할 수 없는 요청입니다. 최신 상태를 확인해 주세요.' : '요청을 처리하지 못했습니다. 입력 내용을 확인해 주세요.'));
  }
  return response.json() as Promise<RecipientRequest>;
}
const RequestsContext = createContext<{ rows: RecipientRequest[]; ready: boolean; error: string;
  refresh: () => Promise<void>; update: (row: RecipientRequest) => void }>({ rows: [], ready: false, error: '', refresh: async () => {}, update: () => {} });
export function ArtworkRequestsProvider({ code, children }: { code: string; children: ReactNode }) {
  const [rows, setRows] = useState<RecipientRequest[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const refresh = useCallback(async (signal?: AbortSignal) => {
    const response = await fetch(`/api/links/s/${encodeURIComponent(code)}/artwork-requests`, { signal });
    if (!response.ok) throw new Error('요청 상태를 확인하지 못했습니다. 패스키로 링크를 다시 열어 주세요.');
    const data: RecipientRequest[] = await response.json();
    if (!signal?.aborted) {
      setRows(previous => [...previous.filter(old => !data.some(row => row.id === old.id)), ...data.map(row => { const old = previous.find(item => item.id === row.id); return old && old.version > row.version ? old : row; })]);
      setReady(true); setError('');
    }
  }, [code]);
  useEffect(() => {
    const controller = new AbortController();
    const load = () => { void refresh(controller.signal).catch(err => { if (!controller.signal.aborted) { setError(err.message); setReady(false); } }); };
    load(); const timer = window.setInterval(load, 15000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [refresh]);
  const update = (row: RecipientRequest) => setRows(previous => [...previous.filter(item => item.id !== row.id), row]);
  return <RequestsContext.Provider value={{ rows, ready, error, refresh, update }}>{children}</RequestsContext.Provider>;
}
export function RecipientArtworkActions({ code, artworkId, saleStatus }: { code: string; artworkId: number; saleStatus?: string }) {
  const { rows, ready, error: loadError, refresh, update } = useContext(RequestsContext);
  const row = rows.find(item => item.artworkId === artworkId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [withdraw, setWithdraw] = useState(false);
  const [releaseHold, setReleaseHold] = useState(false);
  const [cancel, setCancel] = useState(false);
  const [reason, setReason] = useState('');
  const [blockedDialog, setBlockedDialog] = useState<'hold' | 'sale'>();
  const [message, setMessage] = useState('');
  const url = `/api/links/s/${encodeURIComponent(code)}/artwork-requests`;
  async function act(action: string, extra: object = {}) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try {
      update(await send(`${url}/${artworkId}`, row, action, extra));
      setNotice(action === 'INQUIRE' ? '담당자에게 문의를 보냈어요. 확인 후 이 화면에 답변이 표시됩니다.' : `${labels[action]} 처리되었습니다.`);
      setWithdraw(false); setCancel(false); setReason(''); setBlockedDialog(undefined); setMessage('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '요청에 실패했습니다.');
      await refresh().catch(() => {});
    } finally { setBusy(false); }
  }
  const held = row?.holdState === 'HELD';
  const activeHold = held || row?.holdState === 'PENDING';
  const unavailable = saleStatus === 'sold' || saleStatus === 'hold' && !held;
  // A refusal is not asked again from the same button: it explains, and offers to ask the administrator.
  const holdRefused = row?.holdState === 'REJECTED' && !row.purchased;
  const cancelRefused = !!row?.purchased && row.cancelRejected;
  const notices = row?.notices ?? [];
  const refusal = notices.find(item => item.kind === (blockedDialog === 'sale' ? 'sale' : 'hold'));
  const inquiryNotice = notices.find(item => item.kind === 'inquiry');
  return <div className="artwork-request" aria-label="작품 요청" aria-busy={busy}>
    <p className="request-status">{row ? summary(row) : '작품에 대한 의사를 전달해 주세요.'}</p>
    {row?.holdUntil && <p>홀딩 기한: {date(row.holdUntil)}</p>}
    {row?.updatedAt && <small>최근 처리: {date(row.updatedAt)}</small>}
    {notices.length > 0 && <ul className="request-notices">{notices.map(item => <li key={`${item.kind}-${item.at}`}>
      <strong>{labels[item.action] || item.action}</strong>{item.reason && <> · {item.kind === 'inquiry' && item.action === 'INQUIRE' ? '보낸 문의: ' : '사유: '}{item.reason}</>}
      <small> ({date(item.at)})</small></li>)}</ul>}
    <div className="request-buttons">
      {!row?.purchased && <>
        <button className="btn-secondary" disabled={!ready || busy} onClick={() => void act(row?.interested ? 'UNINTEREST' : 'INTEREST')}>{row?.interested ? '관심 해제' : '관심 있음'}</button>
        {activeHold ? <button className="btn-secondary" disabled={!ready || busy} onClick={() => {
          if (window.confirm('홀딩을 취소할까요? 확정된 홀딩은 즉시 해제됩니다.')) void act('CANCEL_HOLD');
        }}>홀딩 취소</button> : <button className="btn-secondary" disabled={!ready || busy || unavailable && !holdRefused}
          onClick={() => holdRefused ? setBlockedDialog('hold') : void act('REQUEST_HOLD')}>홀딩 요청</button>}
        {row?.purchaseIntent ? <button className="btn-secondary" disabled={!ready || busy} onClick={() => { setWithdraw(true); setReleaseHold(false); }}>구매 의사 철회</button>
          : <button className="btn-primary" disabled={!ready || busy || unavailable} onClick={() => void act('PURCHASE')}>구매 의사 전달</button>}
      </>}
      {row?.purchased && !row.cancellationRequested && <button className="btn-secondary" disabled={!ready || busy}
        onClick={() => cancelRefused ? setBlockedDialog('sale') : setCancel(true)}>판매 취소 요청</button>}
    </div>
    {withdraw && <div className="request-confirm">
      <p>구매 의사를 철회할까요?</p>
      {activeHold && <label><input type="checkbox" checked={releaseHold} onChange={event => setReleaseHold(event.target.checked)} /> 홀딩도 함께 취소</label>}
      <button className="btn-secondary" disabled={busy} onClick={() => setWithdraw(false)}>돌아가기</button>
      <button className="btn-primary" disabled={busy} onClick={() => void act('WITHDRAW', { releaseHold })}>철회 확인</button>
    </div>}
    {cancel && <form className="request-confirm" onSubmit={event => { event.preventDefault(); void act('REQUEST_CANCEL', { reason }); }}>
      <label>취소 요청 사유<textarea required maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} /></label>
      <button type="button" className="btn-secondary" disabled={busy} onClick={() => setCancel(false)}>돌아가기</button>
      <button className="btn-primary" disabled={busy || !reason.trim()}>취소 요청 전달</button>
    </form>}
    {blockedDialog && row && <div className="modal-backdrop" role="presentation" onClick={busy ? undefined : () => setBlockedDialog(undefined)}>
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby={`refused-${artworkId}`} onClick={event => event.stopPropagation()}>
        <h3 id={`refused-${artworkId}`}>{blockedDialog === 'hold' ? '홀딩 요청이 거절된 상태예요' : '판매 취소 요청이 거절된 상태예요'}</h3>
        <dl className="modal-details">
          <div><dt>현재 상태</dt><dd>{summary(row)}</dd></div>
          {refusal && <div><dt>사유</dt><dd>{refusal.reason || '사유가 기록되지 않았어요'}</dd></div>}
          {refusal && <div><dt>처리 시각</dt><dd>{date(refusal.at)}</dd></div>}
        </dl>
        {row.inquiryPending ? <p className="modal-message">담당자에게 문의를 보냈어요. 확인 후 이 화면에 답변이 표시됩니다.
          {inquiryNotice?.action === 'INQUIRE' && <><br />보낸 문의: {inquiryNotice.reason}</>}</p> : <>
          <p className="modal-message">같은 요청을 바로 다시 보낼 수는 없어요. 다시 요청하려면 담당자에게 문의해 주세요. 담당자가 허용하면 다시 요청할 수 있어요.</p>
          <label>문의 내용<textarea maxLength={1000} value={message} onChange={event => setMessage(event.target.value)}
            placeholder={blockedDialog === 'hold' ? '예: 일정이 바뀌어 다시 홀딩을 요청드리고 싶어요.' : '예: 취소가 필요한 사정을 다시 설명드릴게요.'} /></label>
        </>}
        <div className="modal-actions">
          <button className="btn-secondary" disabled={busy} onClick={() => setBlockedDialog(undefined)}>닫기</button>
          {!row.inquiryPending && <button className="btn-primary" disabled={busy || !message.trim()} onClick={() => void act('INQUIRE', { reason: message.trim() })}>
            {busy ? '보내는 중…' : '담당자에게 문의'}</button>}
        </div>
        {error && <p className="error-text" role="alert">{error}</p>}
      </div>
    </div>}
    <small>홀딩은 관리자 승인 후 확정됩니다. 구매 의사 전달은 결제나 판매 확정이 아닙니다.</small>
    {notice && <p role="status">{notice}</p>}{(error || loadError) && <p className="error-text" role="alert">{error || loadError}</p>}
  </div>;
}
interface InboxRequest extends ArtworkRequest {
  firstRequestedAt: string; queueAt: string; processingState: string; assignee: string; internalNote: string;
  linkTitle: string; ownerSub: string; withdrawalCount: number; currentState: string; inquiryMessage: string | null;
}
const states: Record<string, string> = { all: '전체 기록', active: '전체 반응', inquiry: '재요청 문의', interested: '관심', pending: '홀딩 요청', held: '홀딩 확정', purchase: '구매의사', withdrawn: '현재 반응 없음', sold: '판매 완료', cancel: '판매 취소 요청' };
const workStates: Record<string, string> = { UNREAD: '미확인', REVIEWED: '확인', CONTACTING: '상담 중', DONE: '처리 완료' };
interface WorkEvent { id: number; actor: string; occurredAt: string; before: { processing_state: string; assignee: string; internal_note: string }; after: { processing_state: string; assignee: string; internal_note: string } }
interface RequestEvent { id: number; artworkTitle: string; linkId: number; action: string; actorType: string; actorId: string; reason: string; occurredAt: string; before: ArtworkRequest; after: ArtworkRequest }
export default function AdminArtworkRequests() {
  const { session } = useAuth();
  const [params, setParams] = useSearchParams();
  const filter = params.get('filter') || 'all';
  const setFilter = (value: string) => changeFilter('filter', value);
  function changeFilter(key: string, value: string) {
    setParams(previous => { const next = new URLSearchParams(previous); if (value) next.set(key, value); else next.delete(key); return next; });
    setPage(0); setError('');
  }
  const [options, setOptions] = useState<Record<string, { value: string; label: string }[]>>({ links: [], artworks: [], owners: [] });
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<InboxRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<InboxRequest>();
  const [history, setHistory] = useState<RequestEvent[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [operation, setOperation] = useState<{ row: InboxRequest; action: string }>();
  const [reason, setReason] = useState('');
  const [preset, setPreset] = useState('');
  const [until, setUntil] = useState('');
  const [processingState, setProcessingState] = useState('UNREAD');
  const [assignee, setAssignee] = useState('');
  const [note, setNote] = useState('');
  const [historyPage, setHistoryPage] = useState(0);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [recipientStats, setRecipientStats] = useState({ purchases: 0, withdrawals: 0 });
  const [workEvents, setWorkEvents] = useState<WorkEvent[]>([]);
  const [workPage, setWorkPage] = useState(0);
  const [workTotal, setWorkTotal] = useState(0);
  const query = params.toString();
  const operationHeading = useRef<HTMLHeadingElement>(null);
  const historyHeading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { operationHeading.current?.focus(); }, [operation]);
  useEffect(() => { historyHeading.current?.focus(); }, [selected]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void fetch(`/api/links/artwork-requests?${new URLSearchParams({ ...Object.fromEntries(new URLSearchParams(query)), filter, search, page: String(page) })}`, { signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error('요청 목록을 불러오지 못했습니다.');
        const data = await response.json(); if (!controller.signal.aborted) { setRows(data.items); setTotal(data.total); setCounts(data.counts); setOptions({ links: data.links, artworks: data.artworks, owners: data.owners }); }
      }).catch(err => { if (!controller.signal.aborted) { setRows([]); setCounts({}); setTotal(0); setError(err.message); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [filter, search, page, revision, query]);
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController(); setHistory([]); setHistoryLoading(true);
    void fetch(`/api/links/artwork-requests/${selected.id}/recipient-history?page=${historyPage}`, { signal: controller.signal })
      .then(async response => { if (!response.ok) throw new Error('이력을 불러오지 못했습니다.'); return response.json(); })
      .then(data => { if (!controller.signal.aborted)  { setHistory(data.items); setHistoryTotal(data.total); setRecipientStats({ purchases: data.purchases, withdrawals: data.withdrawals }); } })
      .catch(err => { if (!controller.signal.aborted) setError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setHistoryLoading(false); });
    return () => controller.abort();
  }, [selected, revision, historyPage]);
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController(); setWorkEvents([]);
    void fetch(`/api/links/artwork-requests/${selected.id}/work-history?page=${workPage}`, { signal: controller.signal })
      .then(async response => { if (!response.ok) throw new Error('관리자 처리 이력을 불러오지 못했습니다.'); return response.json(); })
      .then(data => { if (!controller.signal.aborted) { setWorkEvents(data.items); setWorkTotal(data.total); } })
      .catch(err => { if (!controller.signal.aborted) setError(err.message); });
    return () => controller.abort();
  }, [selected, revision, workPage]);
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if (!operation || busy) return;
    setBusy(true); setError('');
    try {
      if (operation.action === 'WORK') {
        const response = await mutate(`/api/links/artwork-requests/${operation.row.id}/work`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ version: operation.row.version, processingState, assignee, internalNote: note }),
        });
        if (!response.ok) throw new Error(response.status === 409 ? '요청이 변경되었습니다. 최신 내용을 확인한 뒤 다시 저장해 주세요.' : '처리 기록을 저장하지 못했습니다.');
      } else await send(`/api/links/artwork-requests/${operation.row.id}`, operation.row, operation.action,
        { reason: composedReason, ...(operation.action === 'APPROVE' ? { holdUntil: new Date(until).toISOString() } : {}) });
      setOperation(undefined); setRevision(value => value + 1);
    } catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했습니다.'); setOperation(undefined); setRevision(value => value + 1); }
    finally { setBusy(false); }
  }
  const presets = operation ? reasonPresets[operation.action] : undefined;
  const composedReason = presets && preset && preset !== CUSTOM
    ? (reason.trim() ? `${preset} — ${reason.trim()}` : preset) : reason.trim();
  function start(row: InboxRequest, action: string) { setOperation({ row, action }); setReason(''); setPreset(''); setUntil(''); setProcessingState(row.processingState); setAssignee(row.assignee); setNote(row.internalNote); }
  return <section className="page-section page-wide">
    <h2>작품 요청</h2>
    <p className="section-desc">전달한 링크에서 받은 반응을 접수순으로 확인합니다. 요청 순서가 홀딩이나 구매 우선권을 자동으로 확정하지 않습니다.</p>
    <div className="request-counts" aria-label="요청 상태 요약">
      {['active', 'interested', 'pending', 'held', 'purchase', 'withdrawn', 'sold', 'cancel', 'inquiry'].map(key => <button key={key}
        className={filter === key ? 'request-count selected' : 'request-count'} aria-pressed={filter === key} onClick={() => setFilter(key)}>
        <span>{states[key]}</span><strong>{counts[key] ?? 0}<small>건</small></strong>
        {['interested', 'pending', 'held', 'purchase'].includes(key) && <small>{counts.active ? ((counts[key] || 0) / counts.active * 100).toFixed(1) : '0'}%</small>}
      </button>)}
    </div>
    <p className="hint-text">링크별 수신자 × 작품 기준 · 중복 상태는 구매의사 → 홀딩 → 관심 순으로 집계합니다. 전체 반응은 판매 완료·취소 요청·현재 반응 없음을 제외합니다.</p>
    <div className="admin-artwork-filters">
      <label>요청 상태 <select value={filter} onChange={event => { setFilter(event.target.value); setPage(0); }}>
        {Object.entries(states).map(([key, text]) => <option key={key} value={key}>{text}</option>)}
      </select></label>
      <label>작품 / 수신자 <input type="search" maxLength={200} value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} /></label>
      <label>처리 상태 <select value={params.get('processing') || ''} onChange={event => changeFilter('processing', event.target.value)}>
        <option value="">전체</option>{Object.entries(workStates).map(([key, text]) => <option key={key} value={key}>{text}</option>)}</select></label>
      {(['linkId', 'artworkId', ...(session?.user?.canAccounts ? ['owner'] : [])]).map(key => {
        const group = key === 'linkId' ? 'links' : key === 'artworkId' ? 'artworks' : 'owners';
        return <label key={key}>{key === 'linkId' ? '전달 링크' : key === 'artworkId' ? '작품' : '발신 계정'} <select value={params.get(key) || ''} onChange={event => changeFilter(key, event.target.value)}>
          <option value="">전체</option>{params.get(key) && !options[group].some(item => item.value === params.get(key)) && <option value={params.get(key)!}>선택한 항목</option>}
          {options[group].map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>;
      })}
      <label>접수 시작일 (UTC) <input type="date" value={params.get('from') || ''} onChange={event => changeFilter('from', event.target.value)} /></label>
      <label>접수 종료일 (UTC) <input type="date" value={params.get('to') || ''} onChange={event => changeFilter('to', event.target.value)} /></label>
      <label>정렬 <select value={params.get('sort') || 'oldest'} onChange={event => changeFilter('sort', event.target.value)}>
        <option value="oldest">접수 빠른 순</option><option value="withdrawals">구매의사 철회 많은 순</option></select></label>
      <label><input type="checkbox" checked={params.get('withdrawals') === 'true'} onChange={event => changeFilter('withdrawals', event.target.checked ? 'true' : '')} /> 구매의사 철회 이력 있음</label>
      <button className="btn-secondary" onClick={() => { setParams({}); setSearch(''); setPage(0); }}>필터 초기화</button>
      <button className="btn-secondary" onClick={() => { setError(''); setRevision(value => value + 1); }}>새로고침</button>
    </div>
    {error && <p role="alert" className="error-text">{error}</p>}
    {loading ? <p role="status">요청을 불러오고 있습니다.</p> : <>
      <p>{total}건</p>
      <div className="admin-artwork-table-wrap"><table className="admin-artwork-table">
        <thead><tr><th>작품 / 수신자</th><th>현재 상태</th><th>접수 / 처리 상태</th><th>처리</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}>
          <td><strong>{row.artworkTitle}</strong><small>{row.recipientEmail}</small><small><Link to={`/links/${row.linkId}`}>{row.linkTitle || '전달 링크'} · #{row.linkId}</Link></small>
            <button className="btn-secondary" onClick={() => changeFilter('artworkId', String(row.artworkId))}>이 작품 요청 모아보기</button>
            <button className="btn-secondary" onClick={() => changeFilter('linkId', String(row.linkId))}>이 링크 반응</button>
            {session?.user?.canAccounts && <small>발신 계정: {options.owners.find(item => item.value === row.ownerSub)?.label || '이전 기록 · 알 수 없음'}</small>}</td>
          <td>{summary(row)}{row.inquiryPending && row.inquiryMessage && <small className="request-note">문의: {row.inquiryMessage}</small>}{row.withdrawalCount > 0 && <small className="request-withdrawals">수신자 구매의사 철회 {row.withdrawalCount}회</small>}</td><td>{date(row.queueAt)}
            <small>최초 반응: {date(row.firstRequestedAt)}</small><strong>{workStates[row.processingState]}</strong><small>담당자: {row.assignee || '미지정'}</small>{row.holdUntil && <small>홀딩 종료: {date(row.holdUntil)}</small>}</td>
          <td><div className="request-buttons">
            {row.holdState === 'PENDING' && <><button className="btn-primary" onClick={() => start(row, 'APPROVE')}>승인</button><button className="btn-secondary" onClick={() => start(row, 'REJECT')}>거절</button></>}
            {row.holdState === 'HELD' && <button className="btn-secondary" onClick={() => start(row, 'RELEASE')}>홀딩 해제</button>}
            {row.purchaseIntent && !row.purchased && <button className="btn-primary" onClick={() => start(row, 'SELL')}>판매 확정</button>}
            {row.cancellationRequested && <><button className="btn-secondary" onClick={() => start(row, 'CANCEL_SALE')}>판매 취소 승인</button><button className="btn-secondary" onClick={() => start(row, 'KEEP_SALE')}>판매 취소 거절</button></>}
            {(row.purchased ? row.cancelRejected : row.holdState === 'REJECTED') && <button className={row.inquiryPending ? 'btn-primary' : 'btn-secondary'} onClick={() => start(row, 'ALLOW_RETRY')}>재요청 허용</button>}
            {row.inquiryPending && <button className="btn-secondary" onClick={() => start(row, 'DECLINE_RETRY')}>문의 거절</button>}
            <button className="btn-secondary" onClick={() => start(row, 'WORK')}>담당 / 메모</button>
            <button className="btn-secondary" onClick={() => { setSelected(row); setHistoryPage(0); setWorkPage(0); }}>이력</button>
          </div></td>
        </tr>)}</tbody>
      </table></div>
      {!rows.length && <p className="empty-state">조건에 맞는 요청이 없습니다.</p>}
      <nav className="admin-artwork-pagination" aria-label="요청 페이지"><button className="btn-secondary" disabled={!page} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {Math.max(1, Math.ceil(total / 30))}</span><button className="btn-secondary" disabled={(page + 1) * 30 >= total} onClick={() => setPage(page + 1)}>다음</button></nav>
    </>}
    {operation && <div className="request-panel"><h3 ref={operationHeading} tabIndex={-1}>{operation.row.artworkTitle} · {operation.action === 'WORK' ? '담당 / 메모' : labels[operation.action]}</h3>
      <p>{operation.row.recipientEmail}</p>
      <form onSubmit={event => void submit(event)}>
        {operation.action === 'APPROVE' && <label>홀딩 종료 시각 (현재 기기 시간대)<input type="datetime-local" required value={until} onChange={event => setUntil(event.target.value)} /></label>}
        {operation.action === 'SELL' && <p>이 수신자의 판매를 확정하며 다른 수신자의 대기 요청은 종료됩니다.</p>}
        {operation.action === 'CANCEL_SALE' && <p>판매 상태를 미판매로 변경합니다. 결제 환불은 담당자가 별도로 처리해야 합니다.</p>}
        {operation.action === 'WORK' ? <>
          <label>처리 상태<select value={processingState} onChange={event => setProcessingState(event.target.value)}>{Object.entries(workStates).map(([key, text]) => <option key={key} value={key}>{text}</option>)}</select></label>
          <label>담당자<input maxLength={200} value={assignee} onChange={event => setAssignee(event.target.value)} placeholder="담당자 이름" /></label>
          <label>내부 메모<textarea maxLength={2000} value={note} onChange={event => setNote(event.target.value)} /></label>
          <p className="hint-text">담당자와 메모는 수신자에게 공개되지 않습니다. 새 반응이 오면 미확인으로 돌아갑니다.</p>
        </> : <>
          {operation.row.inquiryPending && operation.row.inquiryMessage && <p className="request-note">수신자 문의: {operation.row.inquiryMessage}</p>}
          {presets && <label>사유<select required={reasonRequired.includes(operation.action)} value={preset} onChange={event => setPreset(event.target.value)}>
            <option value="">{reasonRequired.includes(operation.action) ? '사유를 선택해 주세요' : '선택 안 함'}</option>
            {presets.map(item => <option key={item} value={item}>{item}</option>)}</select></label>}
          <label>{presets ? (preset === CUSTOM ? '사유 직접 입력' : '추가 설명 (선택)') : '처리 사유'}<textarea maxLength={900}
            required={(!presets && reasonRequired.includes(operation.action)) || preset === CUSTOM} value={reason} onChange={event => setReason(event.target.value)} /></label>
          {reasonVisible.includes(operation.action) && <p className="hint-text">사유는 수신자 화면에 그대로 표시됩니다. 내부 내용은 담당 / 메모에 적어 주세요.</p>}
          {operation.action === 'ALLOW_RETRY' && <p>수신자가 같은 요청을 다시 보낼 수 있게 됩니다.</p>}
        </>}
        <div className="request-buttons"><button className="btn-primary" disabled={busy}>처리 확인</button><button className="btn-secondary" type="button" disabled={busy} onClick={() => setOperation(undefined)}>닫기</button></div>
      </form>
    </div>}
    {selected && <div className="request-panel"><h3 ref={historyHeading} tabIndex={-1}>{selected.artworkTitle} · {selected.recipientEmail} 이력</h3>
      <button className="btn-secondary" onClick={() => setSelected(undefined)}>이력 닫기</button>
      <p>수신자 구매의사 표시 {recipientStats.purchases}회 · 철회 {recipientStats.withdrawals}회</p>
      <p className="hint-text">접근 가능한 링크의 전체 이력입니다. 패스키로 확인된 동일 수신자만 연결하며, 관리자·시스템에 의한 종료는 철회 횟수에서 제외합니다.</p>
      {historyLoading ? <p role="status">이력을 불러오고 있습니다.</p> : <ol className="request-history">{history.map(item => <li key={item.id}>
        <strong>{item.artworkTitle} · {labels[item.action] || item.action}</strong> · 링크 #{item.linkId} · {date(item.occurredAt)}
        <p>{({ ADMIN: '관리자', RECIPIENT: '수신자', SYSTEM: '시스템' } as Record<string, string>)[item.actorType]} · {item.actorId}</p>
        <p>{summary(item.before)} → {summary(item.after)}</p>
        {item.after.holdUntil && <p>홀딩 종료: {date(item.after.holdUntil)}</p>}{item.reason && <p>사유: {item.reason}</p>}
      </li>)}</ol>}
      <nav className="admin-artwork-pagination" aria-label="수신자 이력 페이지"><button className="btn-secondary" disabled={!historyPage} onClick={() => setHistoryPage(historyPage - 1)}>이전 이력</button>
        <span>{historyPage + 1} / {Math.max(1, Math.ceil(historyTotal / 50))}</span><button className="btn-secondary" disabled={(historyPage + 1) * 50 >= historyTotal} onClick={() => setHistoryPage(historyPage + 1)}>다음 이력</button></nav>
      <h3>이 요청의 관리자 처리 기록</h3>
      {!workEvents.length && <p>저장된 처리 기록이 없습니다.</p>}
      <ol className="request-history">{workEvents.map(item => <li key={item.id}><strong>{item.actor}</strong> · {date(item.occurredAt)}
        <p>{workStates[item.before.processing_state]} → {workStates[item.after.processing_state]}</p>
        <p>담당자: {item.before.assignee || '미지정'} → {item.after.assignee || '미지정'}</p>
        <p className="request-note">이전 메모: {item.before.internal_note || '없음'}</p><p className="request-note">저장한 메모: {item.after.internal_note || '없음'}</p>
      </li>)}</ol>
      <nav className="admin-artwork-pagination" aria-label="관리자 처리 기록 페이지"><button className="btn-secondary" disabled={!workPage} onClick={() => setWorkPage(workPage - 1)}>이전 기록</button>
        <span>{workPage + 1} / {Math.max(1, Math.ceil(workTotal / 50))}</span><button className="btn-secondary" disabled={(workPage + 1) * 50 >= workTotal} onClick={() => setWorkPage(workPage + 1)}>다음 기록</button></nav>
    </div>}
  </section>;
}
