import { useEffect, useState } from 'react';

/** A page of rows as the console's list endpoints return it. */
export interface Paged<T> { items: T[]; total: number; page: number; size: number }

export const EMPTY_PAGE = { items: [], total: 0, page: 0, size: 50 };

/** The value, once it has stopped changing for a moment: a search per word, not per key. */
export function useDebounced<T>(value: T, delay = 280): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return settled;
}

/**
 * Where this page sits in the whole list, and the way to the next one.
 *
 * <p>Says the range in words ("1,248장 중 51–100") because that is what an operator
 * reads out to a colleague, and hides itself when everything fits on one page.
 */
export function Pager({ page, unit = '건', onPage }: {
  page: Paged<unknown>; unit?: string; onPage: (next: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(page.total / page.size));
  if (page.total <= page.size && page.page === 0) {
    return page.total > 0 ? <p className="pager-count">{page.total.toLocaleString()}{unit}</p> : null;
  }
  const from = page.page * page.size + 1;
  const to = Math.min(page.total, from + page.items.length - 1);
  return (
    <nav className="pager" aria-label="쪽 이동">
      <span className="pager-count">
        {page.total.toLocaleString()}{unit} 중 <b>{from.toLocaleString()}–{to.toLocaleString()}</b>
      </span>
      <span className="pager-steps">
        <button type="button" className="btn-tiny" disabled={page.page === 0}
          onClick={() => onPage(0)} aria-label="첫 쪽">«</button>
        <button type="button" className="btn-tiny" disabled={page.page === 0}
          onClick={() => onPage(page.page - 1)}>이전</button>
        <span className="pager-where">{page.page + 1} / {pages}</span>
        <button type="button" className="btn-tiny" disabled={page.page + 1 >= pages}
          onClick={() => onPage(page.page + 1)}>다음</button>
        <button type="button" className="btn-tiny" disabled={page.page + 1 >= pages}
          onClick={() => onPage(pages - 1)} aria-label="마지막 쪽">»</button>
      </span>
    </nav>
  );
}

/** A search box with the list's own filter chips beside it. */
export function ListFilter<K extends string>({ query, onQuery, placeholder, filters, filter, onFilter }: {
  query: string; onQuery: (next: string) => void; placeholder: string;
  filters: Array<[K, string, number | undefined]>; filter: K; onFilter: (next: K) => void;
}) {
  return (
    <div className="list-filter">
      <input type="search" value={query} placeholder={placeholder} aria-label={placeholder}
        onChange={event => onQuery(event.target.value)} />
      <div className="inner-tabs" role="tablist">
        {filters.map(([key, label, count]) => (
          <button key={key} type="button" role="tab" aria-selected={filter === key}
            className={filter === key ? 'active' : ''} onClick={() => onFilter(key)}>
            {label}{count !== undefined && <b>{count.toLocaleString()}</b>}
          </button>
        ))}
      </div>
    </div>
  );
}
