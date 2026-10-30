import { useCallback, useEffect, useRef, useState } from 'react';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import {
  fetchCoupons, setCouponStatus, type BoothCoupon, type CouponCounts, type CouponStatus,
} from '../../booth/api';
import { shortTime } from '../../console/model';
import { EMPTY_PAGE, Pager, useDebounced, type Paged } from '../ListTools';
import { COUPON_STATE, type BoothTabProps } from './shared';

type Filter = CouponStatus | 'ALL';

/** A redeemed coupon's place of use, as the list says it. */
function usedWhere(row: BoothCoupon): string {
  if (!row.redeemedBy) return '';
  return row.redeemedBy.startsWith('mgr:') ? '부스 관리자' : row.redeemedBy;
}

/**
 * Every coupon this booth has handed out, to find one and take it back or give it back.
 * Voiding keeps the row: it says the coupon no longer counts, not that it never existed.
 */
export default function BoothCoupons({ client, refresh, fail, notify }: BoothTabProps) {
  const [filter, setFilter] = useState<Filter>('ALL');
  const [query, setQuery] = useState('');
  const search = useDebounced(query.trim());
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Paged<BoothCoupon>>(EMPTY_PAGE);
  const [counts, setCounts] = useState<CouponCounts | null>(null);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [confirm, setConfirm] = useState<'VOID' | 'ISSUED' | null>(null);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => { setPageNo(0); }, [filter, search]);
  // A choice is about the rows on screen: another search or page starts it over.
  useEffect(() => { setPicked(new Set()); }, [filter, search, pageNo]);

  const latest = useRef(0);
  const load = useCallback(async () => {
    const mine = ++latest.current;
    try {
      const next = await fetchCoupons(client, { status: filter, q: search, page: pageNo });
      if (mine !== latest.current) return;
      setPage(next);
      if (next.counts) setCounts(next.counts);
    } catch (err) { if (mine === latest.current) fail(err); }
  }, [client, filter, search, pageNo, fail]);
  useEffect(() => { void load(); }, [load, revision]);

  const rows = page.items.filter(row => picked.has(row.couponId));
  const voidable = rows.filter(row => row.status === 'ISSUED');
  const restorable = rows.filter(row => row.status === 'VOID');

  async function apply(status: 'VOID' | 'ISSUED') {
    const ids = (status === 'VOID' ? voidable : restorable).map(row => row.couponId);
    if (ids.length === 0) return;
    setBusy(true);
    try {
      await setCouponStatus(client, ids, status);
      notify(status === 'VOID'
        ? `쿠폰 ${ids.length}장을 무효화했어요. 관람객 화면에서 바로 사라져요.`
        : `쿠폰 ${ids.length}장을 되돌렸어요. 다시 쓸 수 있어요.`);
      setPicked(new Set());
      setRevision(value => value + 1);
      void refresh();
    } catch (err) { fail(err); }
    finally { setBusy(false); setConfirm(null); }
  }

  const chips: Array<[Filter, string, number | undefined]> = [
    ['ALL', '전체', counts?.all], ['ISSUED', '미사용', counts?.issued],
    ['REDEEMED', '사용함', counts?.redeemed], ['VOID', '무효', counts?.void],
  ];
  const pageAllPicked = page.items.length > 0 && page.items.every(row => picked.has(row.couponId));

  return (
    <div className="bm-stack">
      <div className="chip-row" role="tablist" aria-label="쿠폰 상태">
        {chips.map(([key, label, count]) => (
          <button key={key} type="button" role="tab" aria-selected={filter === key}
            className={filter === key ? 'on' : ''} onClick={() => setFilter(key)}>
            {label}{count !== undefined && ` ${count.toLocaleString()}`}
          </button>
        ))}
      </div>
      <input type="search" className="bm-search" value={query} placeholder="입장권 번호, 이메일, 쿠폰 이름으로 찾기"
        aria-label="입장권 번호, 이메일, 쿠폰 이름으로 찾기" onChange={e => setQuery(e.target.value)} />

      <div className="bm-pick-tools">
        <button type="button" className="btn-tiny" disabled={page.items.length === 0}
          onClick={() => setPicked(pageAllPicked ? new Set() : new Set(page.items.map(row => row.couponId)))}>
          {pageAllPicked ? '선택 해제' : '이 쪽 모두 선택'}</button>
        <button type="button" className="btn-tiny btn-tiny-danger" disabled={busy || voidable.length === 0}
          onClick={() => setConfirm('VOID')}>무효화{voidable.length > 0 ? ` ${voidable.length}` : ''}</button>
        <button type="button" className="btn-tiny" disabled={busy || restorable.length === 0}
          onClick={() => setConfirm('ISSUED')}>되돌리기{restorable.length > 0 ? ` ${restorable.length}` : ''}</button>
      </div>

      {page.items.length === 0 ? (
        <p className="console-none">{search || filter !== 'ALL' ? '찾는 쿠폰이 없어요.' : '아직 지급한 쿠폰이 없어요.'}</p>
      ) : (
        <ul className="bm-list bm-checks console-card">
          {page.items.map(row => {
            const [label, tone] = COUPON_STATE[row.status] ?? [row.status, 'wait'];
            const where = usedWhere(row);
            return (
              <li key={row.couponId} className={row.status === 'VOID' ? 'is-off' : ''}>
                <label>
                  <input type="checkbox" checked={picked.has(row.couponId)} disabled={row.status === 'REDEEMED'}
                    aria-label={`${row.ticketRef} ${row.title} 선택`}
                    onChange={() => setPicked(current => {
                      const next = new Set(current);
                      if (next.has(row.couponId)) next.delete(row.couponId); else next.add(row.couponId);
                      return next;
                    })} />
                  <span className="bm-row-main">
                    <b>{row.title}</b>
                    <span className={`pill pill-${tone}`}>{label}</span>
                  </span>
                  <small>{row.ticketRef}{row.emailMasked ? ` · ${row.emailMasked}` : ''}</small>
                  <small>
                    지급 {shortTime(row.issuedAt)}
                    {row.redeemedAt ? ` · 사용 ${shortTime(row.redeemedAt)}${where ? ` (${where})` : ''}` : ''}
                  </small>
                </label>
              </li>
            );
          })}
        </ul>
      )}
      <Pager page={page} unit="장" onPage={setPageNo} />
      <p className="hint-text">사용한 쿠폰은 바꿀 수 없어요. 미사용 쿠폰은 무효화하고, 무효 쿠폰은 되돌릴 수 있어요.</p>

      <ConfirmDialog
        open={confirm !== null}
        title={confirm === 'VOID' ? `쿠폰 ${voidable.length}장을 무효화할까요?` : `쿠폰 ${restorable.length}장을 되돌릴까요?`}
        message={confirm === 'VOID'
          ? '관람객 화면에서 바로 사라지고 더 이상 쓸 수 없어요. 나중에 되돌릴 수 있어요.'
          : '관람객 화면에 다시 보이고 쓸 수 있게 돼요.'}
        confirmLabel={confirm === 'VOID' ? '무효화' : '되돌리기'}
        busy={busy}
        onCancel={() => setConfirm(null)}
        onConfirm={() => { if (confirm) void apply(confirm); }}
      />
    </div>
  );
}
