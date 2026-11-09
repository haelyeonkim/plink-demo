import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { mutate } from '@shared/auth';

export interface ArtworkRequest {
  id: number; artworkId: number; recipientId: number; linkId: number; recipientEmail: string; artworkTitle: string;
  interested: boolean; holdState: string; holdUntil: string | null; purchaseIntent: boolean;
  purchased: boolean; cancellationRequested: boolean; version: number; updatedAt: string;
}
const labels: Record<string, string> = {
  NONE: '홀딩 없음', PENDING: '홀딩 승인 대기', HELD: '홀딩 확정', REJECTED: '홀딩 거절',
  CANCELLED: '홀딩 취소', EXPIRED: '홀딩 만료', CLOSED: '요청 종료',
  INTEREST: '관심 표시', UNINTEREST: '관심 해제', REQUEST_HOLD: '홀딩 요청', CANCEL_HOLD: '홀딩 취소',
  PURCHASE: '구매 의사 전달', WITHDRAW: '구매 의사 철회', APPROVE: '홀딩 승인', REJECT: '홀딩 거절',
  RELEASE: '홀딩 해제', SELL: '판매 확정', REQUEST_CANCEL: '판매 취소 요청', CANCEL_SALE: '판매 취소 승인', KEEP_SALE: '판매 취소 거절',
};
const date = (value: string) => new Date(value).toLocaleString('ko-KR');
function summary(row: ArtworkRequest) {
  return [row.interested && '관심 있음', labels[row.holdState], row.purchaseIntent && !row.purchased && '구매 의사 있음',
    row.purchased && '판매 완료', row.cancellationRequested && '판매 취소 요청'].filter(Boolean).join(' · ');
}
async function send(url: string, row: ArtworkRequest | undefined, action: string, extra: object = {}) {
  const response = await mutate(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, version: row?.version ?? 0, key: crypto.randomUUID(), ...extra }) });
  if (!response.ok) {
    if (response.status === 403) throw new Error('인증이 만료되었거나 권한이 없습니다. 다시 로그인하거나 패스키로 링크를 열어 주세요.');
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || body.message || (response.status === 409 ? '상태가 변경되었거나 지금 처리할 수 없는 요청입니다. 최신 상태를 확인해 주세요.' : '요청을 처리하지 못했습니다. 입력 내용을 확인해 주세요.'));
  }
  return response.json() as Promise<ArtworkRequest>;
}
const RequestsContext = createContext<{ rows: ArtworkRequest[]; ready: boolean; error: string;
  refresh: () => Promise<void>; update: (row: ArtworkRequest) => void }>({ rows: [], ready: false, error: '', refresh: async () => {}, update: () => {} });
export function ArtworkRequestsProvider({ code, children }: { code: string; children: ReactNode }) {
  const [rows, setRows] = useState<ArtworkRequest[]>([]);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const refresh = useCallback(async (signal?: AbortSignal) => {
    const response = await fetch(`/api/links/s/${encodeURIComponent(code)}/artwork-requests`, { signal });
    if (!response.ok) throw new Error('요청 상태를 확인하지 못했습니다. 패스키로 링크를 다시 열어 주세요.');
    const data: ArtworkRequest[] = await response.json();
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
  const update = (row: ArtworkRequest) => setRows(previous => [...previous.filter(item => item.id !== row.id), row]);
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
  const url = `/api/links/s/${encodeURIComponent(code)}/artwork-requests`;
  async function act(action: string, extra: object = {}) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try {
      update(await send(`${url}/${artworkId}`, row, action, extra));
      setNotice(`${labels[action]} 처리되었습니다.`); setWithdraw(false); setCancel(false); setReason('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '요청에 실패했습니다.');
      await refresh().catch(() => {});
    } finally { setBusy(false); }
  }
  const held = row?.holdState === 'HELD';
  const activeHold = held || row?.holdState === 'PENDING';
  const unavailable = saleStatus === 'sold' || saleStatus === 'hold' && !held;
  return <div className="artwork-request" aria-label="작품 요청" aria-busy={busy}>
    <p className="request-status">{row ? summary(row) : '작품에 대한 의사를 전달해 주세요.'}</p>
    {row?.holdUntil && <p>홀딩 기한: {date(row.holdUntil)}</p>}
    {row?.updatedAt && <small>최근 처리: {date(row.updatedAt)}</small>}
    <div className="request-buttons">
      {!row?.purchased && <>
        <button className="btn-secondary" disabled={!ready || busy} onClick={() => void act(row?.interested ? 'UNINTEREST' : 'INTEREST')}>{row?.interested ? '관심 해제' : '관심 있음'}</button>
        {activeHold ? <button className="btn-secondary" disabled={!ready || busy} onClick={() => {
          if (window.confirm('홀딩을 취소할까요? 확정된 홀딩은 즉시 해제됩니다.')) void act('CANCEL_HOLD');
        }}>홀딩 취소</button> : <button className="btn-secondary" disabled={!ready || busy || unavailable} onClick={() => void act('REQUEST_HOLD')}>홀딩 요청</button>}
        {row?.purchaseIntent ? <button className="btn-secondary" disabled={!ready || busy} onClick={() => { setWithdraw(true); setReleaseHold(false); }}>구매 의사 철회</button>
          : <button className="btn-primary" disabled={!ready || busy || unavailable} onClick={() => void act('PURCHASE')}>구매 의사 전달</button>}
      </>}
      {row?.purchased && !row.cancellationRequested && <button className="btn-secondary" disabled={!ready || busy} onClick={() => setCancel(true)}>판매 취소 요청</button>}
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
    <small>홀딩은 관리자 승인 후 확정됩니다. 구매 의사 전달은 결제나 판매 확정이 아닙니다.</small>
    {notice && <p role="status">{notice}</p>}{(error || loadError) && <p className="error-text" role="alert">{error || loadError}</p>}
  </div>;
}
interface RequestEvent { id: number; action: string; actorType: string; actorId: string; reason: string; occurredAt: string; before: ArtworkRequest; after: ArtworkRequest }
export default function AdminArtworkRequests() {
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState<ArtworkRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<ArtworkRequest>();
  const [history, setHistory] = useState<RequestEvent[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [operation, setOperation] = useState<{ row: ArtworkRequest; action: string }>();
  const [reason, setReason] = useState('');
  const [until, setUntil] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void fetch(`/api/admin/artworks/requests?${new URLSearchParams({ filter, search, page: String(page) })}`, { signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error('요청 목록을 불러오지 못했습니다.');
        const data = await response.json(); if (!controller.signal.aborted) { setRows(data.items); setTotal(data.total); }
      }).catch(err => { if (!controller.signal.aborted) setError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [filter, search, page, revision]);
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController(); setHistory([]); setHistoryLoading(true);
    void fetch(`/api/admin/artworks/requests/${selected.id}/history`, { signal: controller.signal })
      .then(async response => { if (!response.ok) throw new Error('이력을 불러오지 못했습니다.'); return response.json(); })
      .then(data => { if (!controller.signal.aborted) setHistory(data); })
      .catch(err => { if (!controller.signal.aborted) setError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setHistoryLoading(false); });
    return () => controller.abort();
  }, [selected, revision]);
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if (!operation || busy) return;
    setBusy(true); setError('');
    try {
      await send(`/api/admin/artworks/requests/${operation.row.id}`, operation.row, operation.action,
        { reason, ...(operation.action === 'APPROVE' ? { holdUntil: new Date(until).toISOString() } : {}) });
      setOperation(undefined); setRevision(value => value + 1);
    } catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했습니다.'); setOperation(undefined); setRevision(value => value + 1); }
    finally { setBusy(false); }
  }
  function start(row: ArtworkRequest, action: string) { setOperation({ row, action }); setReason(''); setUntil(''); }
  return <section className="page-section page-wide">
    <h2><Link to="/admin/artworks">작품 목록</Link> / 수신자 요청</h2>
    <p className="section-desc">관심, 홀딩, 구매 의사를 확인하고 처리합니다. 홀딩 승인 시 종료 시각을 지정해 주세요.</p>
    <div className="admin-artwork-filters">
      <label>요청 상태 <select value={filter} onChange={event => { setFilter(event.target.value); setPage(0); }}>
        {Object.entries({ all: '전체', pending: '승인 대기', held: '홀딩 확정', purchase: '구매 의사', cancel: '판매 취소 요청', interested: '관심 있음' }).map(([key, text]) => <option key={key} value={key}>{text}</option>)}
      </select></label>
      <label>작품 / 수신자 <input type="search" maxLength={200} value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} /></label>
      <button className="btn-secondary" onClick={() => setRevision(value => value + 1)}>새로고침</button>
    </div>
    {error && <p role="alert" className="error-text">{error}</p>}
    {loading ? <p role="status">요청을 불러오고 있습니다.</p> : <>
      <p>{total}건</p>
      <div className="admin-artwork-table-wrap"><table className="admin-artwork-table">
        <thead><tr><th>작품 / 수신자</th><th>현재 상태</th><th>최근 처리 / 홀딩 기한</th><th>처리</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}>
          <td><strong>{row.artworkTitle}</strong><small>{row.recipientEmail}</small><small>전달 링크 #{row.linkId} · 수신자 #{row.recipientId}</small></td>
          <td>{summary(row)}</td><td>{date(row.updatedAt)}{row.holdUntil && <small>홀딩 종료: {date(row.holdUntil)}</small>}</td>
          <td><div className="request-buttons">
            {row.holdState === 'PENDING' && <><button className="btn-primary" onClick={() => start(row, 'APPROVE')}>승인</button><button className="btn-secondary" onClick={() => start(row, 'REJECT')}>거절</button></>}
            {row.holdState === 'HELD' && <button className="btn-secondary" onClick={() => start(row, 'RELEASE')}>홀딩 해제</button>}
            {row.purchaseIntent && !row.purchased && <button className="btn-primary" onClick={() => start(row, 'SELL')}>판매 확정</button>}
            {row.cancellationRequested && <><button className="btn-secondary" onClick={() => start(row, 'CANCEL_SALE')}>판매 취소 승인</button><button className="btn-secondary" onClick={() => start(row, 'KEEP_SALE')}>판매 취소 거절</button></>}
            <button className="btn-secondary" onClick={() => setSelected(row)}>이력</button>
          </div></td>
        </tr>)}</tbody>
      </table></div>
      {!rows.length && <p className="empty-state">조건에 맞는 요청이 없습니다.</p>}
      <nav className="admin-artwork-pagination" aria-label="요청 페이지"><button className="btn-secondary" disabled={!page} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {Math.max(1, Math.ceil(total / 30))}</span><button className="btn-secondary" disabled={(page + 1) * 30 >= total} onClick={() => setPage(page + 1)}>다음</button></nav>
    </>}
    {operation && <div className="request-panel"><h3>{operation.row.artworkTitle} · {labels[operation.action]}</h3>
      <p>{operation.row.recipientEmail}</p>
      <form onSubmit={event => void submit(event)}>
        {operation.action === 'APPROVE' && <label>홀딩 종료 시각 (현재 기기 시간대)<input type="datetime-local" required value={until} onChange={event => setUntil(event.target.value)} /></label>}
        {operation.action === 'SELL' && <p>이 수신자의 판매를 확정하며 다른 수신자의 대기 요청은 종료됩니다.</p>}
        {operation.action === 'CANCEL_SALE' && <p>판매 상태를 미판매로 변경합니다. 결제 환불은 담당자가 별도로 처리해야 합니다.</p>}
        <label>처리 사유<textarea maxLength={1000} required={['REJECT', 'CANCEL_SALE', 'KEEP_SALE'].includes(operation.action)} value={reason} onChange={event => setReason(event.target.value)} /></label>
        <div className="request-buttons"><button className="btn-primary" disabled={busy}>처리 확인</button><button className="btn-secondary" type="button" disabled={busy} onClick={() => setOperation(undefined)}>닫기</button></div>
      </form>
    </div>}
    {selected && <div className="request-panel"><h3>{selected.artworkTitle} · {selected.recipientEmail} 이력</h3>
      <button className="btn-secondary" onClick={() => setSelected(undefined)}>이력 닫기</button>
      <p className="hint-text">최근 200건 · 취소한 액션도 보존됩니다.</p>
      {historyLoading ? <p role="status">이력을 불러오고 있습니다.</p> : <ol className="request-history">{history.map(item => <li key={item.id}>
        <strong>{labels[item.action] || item.action}</strong> · {date(item.occurredAt)}
        <p>{({ ADMIN: '관리자', RECIPIENT: '수신자', SYSTEM: '시스템' } as Record<string, string>)[item.actorType]} · {item.actorId}</p>
        <p>{summary(item.before)} → {summary(item.after)}</p>
        {item.after.holdUntil && <p>홀딩 종료: {date(item.after.holdUntil)}</p>}{item.reason && <p>사유: {item.reason}</p>}
      </li>)}</ol>}
    </div>}
  </section>;
}
