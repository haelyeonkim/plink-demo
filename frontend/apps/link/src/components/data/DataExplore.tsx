import { Fragment, useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { mutate } from '@shared/auth';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { DataBar } from './DataLayout';
import { useEvents } from './useEvents';
import {
  copyText, day, filterQuery, filtersOf, fromLocalInput, getJson, num, rangeText, read, sameDay, seconds,
  toLocalInput, type Category, type Filters, type LogPage, type LogRow, type ResultFilter, type SavedView,
} from './dataApi';

const PAGE_SIZE = 50;

type ColumnKey = 'at' | 'category' | 'action' | 'who' | 'email' | 'device' | 'session' | 'gate' | 'booth'
  | 'result' | 'summary' | 'request' | 'requestId' | 'userAgent';

const COLUMNS: Array<{ key: ColumnKey; label: string; on: boolean }> = [
  { key: 'at', label: '시각', on: true },
  { key: 'category', label: '카테고리', on: false },
  { key: 'action', label: '종류', on: true },
  { key: 'who', label: '누가', on: true },
  { key: 'email', label: '이메일', on: true },
  { key: 'device', label: '기기 · IP', on: true },
  { key: 'session', label: '이벤트', on: false },
  { key: 'gate', label: '게이트', on: false },
  { key: 'booth', label: '부스', on: false },
  { key: 'result', label: '결과', on: true },
  { key: 'summary', label: '자세히', on: true },
  { key: 'request', label: '요청', on: false },
  { key: 'requestId', label: '요청 ID', on: false },
  { key: 'userAgent', label: 'User-Agent', on: false },
];
const DEFAULT_COLUMNS = COLUMNS.filter(column => column.on).map(column => column.key);
const COLUMNS_KEY = 'plink.data.logColumns';

const RESULT_LABEL: Record<ResultFilter, string> = { ALL: '모두', OK: '성공만', FAIL: '거절 · 실패만' };

function loadColumns(): ColumnKey[] {
  try {
    const saved = JSON.parse(window.localStorage.getItem(COLUMNS_KEY) || 'null');
    if (Array.isArray(saved)) {
      const known = saved.filter((key): key is ColumnKey => COLUMNS.some(column => column.key === key));
      if (known.length > 0) return known;
    }
  } catch { /* a private window, or nothing saved yet */ }
  return DEFAULT_COLUMNS;
}
function saveColumns(keys: ColumnKey[]) {
  try { window.localStorage.setItem(COLUMNS_KEY, JSON.stringify(keys)); } catch { /* only a convenience */ }
}

/** Which columns the table shows, behind one button. */
function ColumnMenu({ shown, onChange }: { shown: ColumnKey[]; onChange: (keys: ColumnKey[]) => void }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (event: PointerEvent) => { if (!box.current?.contains(event.target as Node)) setOpen(false); };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); button.current?.focus(); } };
    window.addEventListener('pointerdown', onDown);
    window.addEventListener('keydown', onKey);
    box.current?.querySelector<HTMLInputElement>('input')?.focus();
    return () => { window.removeEventListener('pointerdown', onDown); window.removeEventListener('keydown', onKey); };
  }, [open]);
  const toggle = (key: ColumnKey, on: boolean) => {
    const next = COLUMNS.map(column => column.key).filter(k => (k === key ? on : shown.includes(k)));
    if (next.length > 0) onChange(next);
  };
  return (
    <div className="column-menu" ref={box}>
      <button ref={button} type="button" className="data-tool" aria-expanded={open} aria-controls="column-menu-list"
        onClick={() => setOpen(value => !value)}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
        보일 열 {shown.length}/{COLUMNS.length}
      </button>
      {open && (
        <fieldset id="column-menu-list" className="column-menu-list">
          <legend className="visually-hidden">보일 열</legend>
          {COLUMNS.map(column => (
            <label key={column.key}>
              <input type="checkbox" checked={shown.includes(column.key)}
                disabled={shown.length === 1 && shown.includes(column.key)}
                onChange={e => toggle(column.key, e.target.checked)} />
              {column.label}
            </label>
          ))}
          <button type="button" className="btn-tiny" onClick={() => onChange(DEFAULT_COLUMNS)}>처음대로</button>
        </fieldset>
      )}
    </div>
  );
}

/**
 * Every logged event, filtered down to what is being looked for. The filters live in the
 * address, so a view can be bookmarked, sent to a colleague or saved by name.
 */
export default function DataExplore() {
  const [params, setParams] = useSearchParams();
  const filters = filtersOf(params);
  const page = Math.max(0, Number(params.get('page')) || 0);
  const { events, eventsError } = useEvents();
  const [categories, setCategories] = useState<Category[]>([]);
  const [result, setResult] = useState<LogPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [views, setViews] = useState<SavedView[]>([]);
  const [columns, setColumns] = useState<ColumnKey[]>(loadColumns);
  const [open, setOpen] = useState<LogRow['id'] | null>(null);
  const [draft, setDraft] = useState(filters.q ?? '');
  const [naming, setNaming] = useState<string | null>(null);
  const [removing, setRemoving] = useState<SavedView | null>(null);
  const [busy, setBusy] = useState(false);

  const scope = filterQuery({ sessionId: filters.sessionId, from: filters.from, to: filters.to }).toString();
  const query = filterQuery(filters).toString();

  useEffect(() => setDraft(filters.q ?? ''), [filters.q]);

  useEffect(() => {
    const controller = new AbortController();
    getJson<Category[]>(`/api/admin/data/categories?${scope}`, controller.signal)
      .then(setCategories)
      .catch(err => { if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '종류를 불러오지 못했어요.'); });
    return () => controller.abort();
  }, [scope]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setOpen(null);
    const url = `/api/admin/data/logs?${filterQuery(filtersOf(new URLSearchParams(query)), { page: String(page), size: String(PAGE_SIZE) })}`;
    getJson<LogPage>(url, controller.signal)
      .then(setResult)
      .catch(err => { if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '로그를 불러오지 못했어요.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [query, page]);

  const loadViews = useCallback(async () => {
    try { setViews(await getJson<SavedView[]>('/api/admin/data/views')); }
    catch (err) { setError(err instanceof Error ? err.message : '저장한 보기를 불러오지 못했어요.'); }
  }, []);
  useEffect(() => { void loadViews(); }, [loadViews]);

  function apply(next: Filters) {
    const query = filterQuery(next);
    setParams(query);
  }
  const change = (patch: Partial<Filters>) => apply({ ...filters, ...patch });

  function search(event: FormEvent) {
    event.preventDefault();
    change({ q: draft.trim() || undefined });
  }

  function setShown(keys: ColumnKey[]) {
    setColumns(keys);
    saveColumns(keys);
  }

  async function saveView(event: FormEvent) {
    event.preventDefault();
    const name = (naming ?? '').trim();
    if (!name) return;
    setBusy(true); setError('');
    try {
      await read(await mutate('/api/admin/data/views', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, filters: { ...filters, categories: filters.categories ?? [] } }),
      }));
      setNaming(null);
      setNotice(`“${name}” 보기를 저장했어요.`);
      await loadViews();
    } catch (err) { setError(err instanceof Error ? err.message : '보기를 저장하지 못했어요.'); }
    finally { setBusy(false); }
  }

  async function removeView(view: SavedView) {
    setBusy(true); setError('');
    try {
      await read(await mutate(`/api/admin/data/views/${view.id}`, { method: 'DELETE' }));
      setRemoving(null);
      setNotice(`“${view.name}” 보기를 지웠어요.`);
      await loadViews();
    } catch (err) { setError(err instanceof Error ? err.message : '보기를 지우지 못했어요.'); }
    finally { setBusy(false); }
  }

  async function copyRaw(row: LogRow) {
    const ok = await copyText(JSON.stringify(row, null, 2));
    setNotice(ok ? '원본을 복사했어요.' : '복사하지 못했어요. 직접 골라서 복사해 주세요.');
  }

  const chosen = new Set(filters.categories ?? []);
  const toggleCategory = (key: string, on: boolean) => {
    const next = new Set(chosen);
    if (on) next.add(key); else next.delete(key);
    change({ categories: [...next] });
  };
  const labelOf = (key: string) => categories.find(row => row.key === key)?.label ?? key;
  const eventName = (id: number | null | undefined) => events.find(row => row.id === id)?.name ?? (id != null ? `#${id}` : '');
  const currentEvent = events.find(row => String(row.id) === filters.sessionId);

  const rows = result?.rows ?? [];
  const spansDays = (filters.from && filters.to && !sameDay(filters.from, filters.to))
    || rows.some(row => !sameDay(row.at, rows[0].at));
  const shown = COLUMNS.filter(column => columns.includes(column.key));

  const cell = (row: LogRow, key: ColumnKey) => {
    switch (key) {
      case 'at': return <td key={key} className="nowrap">{spansDays ? `${day(row.at)} ${seconds(row.at)}` : seconds(row.at)}</td>;
      case 'category': return <td key={key}>{row.categoryLabel}</td>;
      case 'action': return <td key={key}>{row.actionLabel}</td>;
      case 'who': return <td key={key}>{row.ticketRef || row.actorEmail || row.actorType || '—'}</td>;
      case 'email': return <td key={key}>{row.actorEmail || '—'}</td>;
      case 'device': return <td key={key} className="muted nowrap">{[row.device, row.ip].filter(Boolean).join(' · ') || '—'}</td>;
      case 'session': return <td key={key}>{row.sessionId != null ? eventName(row.sessionId) : '—'}</td>;
      case 'gate': return <td key={key}>{row.gateId ?? '—'}</td>;
      case 'booth': return <td key={key}>{row.boothId ?? '—'}</td>;
      case 'result': return <td key={key} className={row.ok ? 'data-good' : 'data-bad'}>{row.result || (row.ok ? '성공' : '실패')}</td>;
      case 'summary': return <td key={key} className="muted">{row.summary || '—'}</td>;
      case 'request': return <td key={key} className="muted nowrap">{row.httpMethod
        ? `${row.httpMethod} ${row.path ?? ''} ${row.status ?? ''}${row.durationMs != null ? ` · ${row.durationMs}ms` : ''}` : '—'}</td>;
      case 'requestId': return <td key={key} className="muted mono">{row.requestId || '—'}</td>;
      case 'userAgent': return <td key={key} className="muted data-ua">{row.userAgent || '—'}</td>;
    }
  };

  const exportLink = `/admin/data/exports/new?${filterQuery({
    sessionId: filters.sessionId, from: filters.from, to: filters.to, categories: filters.categories,
  })}`;
  const first = result && result.total > 0 ? page * PAGE_SIZE + 1 : 0;
  const last = result ? Math.min(result.total, page * PAGE_SIZE + rows.length) : 0;
  const range = rangeText(filters.from, filters.to);

  return (
    <>
      <DataBar>
        {views.length > 0 && <span className="data-bar-label">저장한 보기</span>}
        {views.map(view => (
          <span key={view.id} className="view-pill">
            <button type="button" onClick={() => apply(view.filters)}>{view.name}</button>
            <button type="button" className="view-pill-remove" aria-label="보기 삭제" title={`${view.name} 보기 삭제`}
              onClick={() => setRemoving(view)}>×</button>
          </span>
        ))}
      </DataBar>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {eventsError && <p className="error-text" role="alert">{eventsError}</p>}

      <div className="explore">
        <aside className="explore-filters data-card" aria-label="필터">
          <div className="data-field">
            <label htmlFor="explore-event">이벤트</label>
            <select id="explore-event" value={filters.sessionId ?? ''} onChange={e => change({ sessionId: e.target.value || undefined })}>
              <option value="">모든 이벤트</option>
              {events.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
            </select>
          </div>
          <div className="data-field">
            <label htmlFor="explore-from">기간</label>
            <div className="data-pair">
              <input id="explore-from" type="datetime-local" aria-label="시작" value={toLocalInput(filters.from)}
                onChange={e => change({ from: fromLocalInput(e.target.value) })} />
              <input id="explore-to" type="datetime-local" aria-label="끝" value={toLocalInput(filters.to)}
                onChange={e => change({ to: fromLocalInput(e.target.value) })} />
            </div>
          </div>
          <fieldset className="explore-categories">
            <legend>무엇을 볼까요</legend>
            {categories.length === 0 && <p className="hint-text">종류를 불러오고 있어요.</p>}
            {categories.map(row => (
              <label key={row.key}>
                <input type="checkbox" checked={chosen.has(row.key)} onChange={e => toggleCategory(row.key, e.target.checked)} />
                {row.label}
                <span className={row.key === 'ERROR' && row.count > 0 ? 'data-bad' : undefined}>{num(row.count)}</span>
              </label>
            ))}
            {chosen.size > 0 && (
              <button type="button" className="btn-tiny" onClick={() => change({ categories: [] })}>모두 보기</button>
            )}
          </fieldset>
          <div className="data-field">
            <label htmlFor="explore-result">결과</label>
            <select id="explore-result" value={filters.result ?? 'ALL'} onChange={e => change({ result: e.target.value as ResultFilter })}>
              {(Object.keys(RESULT_LABEL) as ResultFilter[]).map(key => <option key={key} value={key}>{RESULT_LABEL[key]}</option>)}
            </select>
          </div>
        </aside>

        <section className="explore-main" aria-label="로그">
          <div className="explore-tools">
            <form className="explore-search" role="search" onSubmit={search}>
              <label htmlFor="explore-q" className="visually-hidden">검색</label>
              <input id="explore-q" type="search" value={draft} placeholder="입장권 번호, 이메일, 게이트, 부스로 찾기"
                onChange={e => setDraft(e.target.value)} />
            </form>
            <ColumnMenu shown={columns} onChange={setShown} />
            <button type="button" className="data-tool" onClick={() => setNaming(naming == null ? '' : null)}
              aria-expanded={naming != null}>이 보기 저장</button>
            <Link className="data-tool data-tool-primary" to={exportLink}>이 조건으로 내보내기</Link>
          </div>

          {naming != null && (
            <form className="view-save" onSubmit={saveView}>
              <label htmlFor="view-name">보기 이름</label>
              <input id="view-name" value={naming} autoFocus maxLength={60} placeholder="예: 게이트 거절만"
                onChange={e => setNaming(e.target.value)}
                onKeyDown={e => { if (e.key === 'Escape') setNaming(null); }} />
              <button className="btn-tiny" type="submit" disabled={busy || !naming.trim()}>저장</button>
              <button className="btn-tiny" type="button" onClick={() => setNaming(null)}>취소</button>
            </form>
          )}

          <div className="filter-chips">
            {currentEvent && <span className="chip">{currentEvent.name}</span>}
            {[...chosen].map(key => <span key={key} className="chip">{labelOf(key)}</span>)}
            {range && <span className="chip">{range}</span>}
            {filters.result && filters.result !== 'ALL' && <span className="chip">{RESULT_LABEL[filters.result]}</span>}
            {filters.q && <span className="chip">“{filters.q}”</span>}
            {result && <span className="chip-count">{num(result.total)}건</span>}
          </div>

          {error && <p className="error-text" role="alert">{error}</p>}

          <div className="data-table-wrap" role="region" aria-label="로그 표" tabIndex={0}>
            <table className="data-table log-table" aria-busy={loading}>
              <thead><tr>{shown.map(column => <th key={column.key} scope="col">{column.label}</th>)}</tr></thead>
              <tbody>
                {rows.length === 0 && (
                  <tr><td colSpan={shown.length} className="data-empty">
                    {loading ? '로그를 불러오고 있어요.' : '이 조건에 맞는 기록이 없어요.'}</td></tr>
                )}
                {rows.map(row => {
                  const expanded = open === row.id;
                  return (
                    <Fragment key={row.id}>
                      <tr className={`log-row${row.ok ? '' : ' failed'}${expanded ? ' expanded' : ''}`} tabIndex={0}
                        aria-expanded={expanded} onClick={() => setOpen(expanded ? null : row.id)}
                        onKeyDown={e => {
                          if (e.target !== e.currentTarget) return;
                          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(expanded ? null : row.id); }
                        }}>
                        {shown.map(column => cell(row, column.key))}
                      </tr>
                      {expanded && (
                        <tr className="log-raw">
                          <td colSpan={shown.length}>
                            <div className="log-raw-head">
                              <span>원본 · {row.action} · #{row.id}</span>
                              <button type="button" onClick={() => void copyRaw(row)}>원본 복사</button>
                            </div>
                            <pre>{JSON.stringify(row, null, 2)}</pre>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>

          {result && (
            <nav className="data-pager" aria-label="로그 페이지">
              <span>{num(first)}–{num(last)} / {num(result.total)} · 행을 누르면 원본이 펼쳐져요</span>
              <span className="data-pager-buttons">
                <button type="button" className="data-tool" disabled={page === 0 || loading}
                  onClick={() => { const next = new URLSearchParams(params); next.set('page', String(page - 1)); setParams(next); }}>이전</button>
                <button type="button" className="data-tool" disabled={last >= result.total || loading}
                  onClick={() => { const next = new URLSearchParams(params); next.set('page', String(page + 1)); setParams(next); }}>다음</button>
              </span>
            </nav>
          )}
        </section>
      </div>

      <ConfirmDialog
        open={removing != null}
        title="이 보기를 지울까요?"
        message={`“${removing?.name ?? ''}” 보기가 목록에서 사라져요. 로그는 그대로 남아요.`}
        confirmLabel="지우기"
        busy={busy}
        onConfirm={() => removing && void removeView(removing)}
        onCancel={() => setRemoving(null)}
      />
    </>
  );
}
