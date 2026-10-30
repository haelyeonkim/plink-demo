import { useEffect, useRef, useState } from 'react';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { fetchTickets, issueCoupons, type BoothTicket, type IssueTarget } from '../../booth/api';
import { MAX_PER_TICKET } from '../../console/model';
import { EMPTY_PAGE, Pager, useDebounced, type Paged } from '../ListTools';
import type { BoothTabProps } from './shared';

/**
 * Gives one of the booth's coupons to many tickets at once: everybody, everybody inside,
 * or the tickets picked from the guest list. The quantity is how many each ticket should
 * end up holding, so sending it twice does not double anybody's coupons.
 */
export default function BoothIssue({ client, dashboard, refresh, fail, notify }: BoothTabProps) {
  const offers = dashboard.offers.filter(offer => offer.active);
  const [offerId, setOfferId] = useState<number | null>(offers[0]?.offerId ?? null);
  const [target, setTarget] = useState<IssueTarget>('ALL');
  const [quantity, setQuantity] = useState(1);
  // Picked tickets survive a new search or page: the list changes, the choice does not.
  const [picked, setPicked] = useState<Map<number, BoothTicket>>(new Map());
  const [query, setQuery] = useState('');
  const search = useDebounced(query.trim());
  const [insideOnly, setInsideOnly] = useState(false);
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Paged<BoothTicket>>(EMPTY_PAGE);
  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  // A kind switched off elsewhere is not one to give.
  useEffect(() => {
    if (offerId !== null && !offers.some(offer => offer.offerId === offerId)) setOfferId(offers[0]?.offerId ?? null);
  }, [offerId, offers]);
  useEffect(() => { setPageNo(0); }, [search, insideOnly]);

  // Only the newest read may fill the list: a slow answer to an older search is dropped.
  const latest = useRef(0);
  useEffect(() => {
    if (target !== 'PICKED') return;
    const mine = ++latest.current;
    setLoading(true);
    fetchTickets(client, { q: search, page: pageNo, inside: insideOnly, offerId })
      .then(next => { if (mine === latest.current) setPage(next); })
      .catch(err => { if (mine === latest.current) fail(err); })
      .finally(() => { if (mine === latest.current) setLoading(false); });
  }, [client, target, search, pageNo, insideOnly, offerId, fail]);

  const offer = offers.find(row => row.offerId === offerId) ?? null;
  const reach = target === 'ALL' ? dashboard.liveTickets : target === 'INSIDE' ? dashboard.inside : picked.size;
  const validQuantity = Number.isInteger(quantity) && quantity >= 1 && quantity <= MAX_PER_TICKET;
  const ready = offer !== null && reach > 0 && validQuantity;

  function toggle(row: BoothTicket) {
    setPicked(current => {
      const next = new Map(current);
      if (next.has(row.ticketId)) next.delete(row.ticketId); else next.set(row.ticketId, row);
      return next;
    });
  }
  const pageAllPicked = page.items.length > 0 && page.items.every(row => picked.has(row.ticketId));
  function togglePage() {
    setPicked(current => {
      const next = new Map(current);
      page.items.forEach(row => { if (pageAllPicked) next.delete(row.ticketId); else next.set(row.ticketId, row); });
      return next;
    });
  }

  async function send() {
    if (!offer) return;
    setBusy(true);
    try {
      const result = await issueCoupons(client, {
        offerId: offer.offerId, target, quantity,
        ...(target === 'PICKED' ? { ticketIds: [...picked.keys()] } : {}),
      });
      const each = quantity > 1 ? ` (1인당 ${quantity}장까지)` : '';
      notify(result.given > 0
        ? `'${result.title}' 쿠폰을 입장권 ${result.tickets.toLocaleString()}장에 모두 ${result.given.toLocaleString()}장 발급했어요${each}`
          + (result.already > 0 ? ` · 이미 다 가진 ${result.already.toLocaleString()}명은 그대로예요.` : '.')
        : `모두 이미 '${result.title}' 쿠폰을 ${quantity}장 이상 가지고 있어 새로 발급하지 않았어요.`);
      setPicked(new Map());
      setConfirming(false);
      await refresh();
    } catch (err) {
      setConfirming(false);
      fail(err);
    } finally { setBusy(false); }
  }

  if (dashboard.offers.length === 0 || offers.length === 0) {
    return (
      <section className="console-card">
        <h3>쿠폰 발급</h3>
        <p className="console-none">
          {dashboard.offers.length === 0
            ? '발급할 쿠폰이 없어요. 쿠폰 종류에서 먼저 만들어 주세요.'
            : '발급 중인 쿠폰이 없어요. 쿠폰 종류에서 하나를 다시 발급으로 바꿔 주세요.'}
        </p>
      </section>
    );
  }

  return (
    <div className="bm-stack">
      <section className="console-card">
        <h3>무엇을</h3>
        <div className="field">
          <label htmlFor="bm-issue-offer">쿠폰</label>
          <select id="bm-issue-offer" value={offerId ?? ''} onChange={e => setOfferId(Number(e.target.value))}>
            {offers.map(row => <option key={row.offerId} value={row.offerId}>{row.title}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="bm-issue-quantity">1인당 장수</label>
          <input id="bm-issue-quantity" type="number" inputMode="numeric" min={1} max={MAX_PER_TICKET}
            value={Number.isFinite(quantity) ? quantity : ''} onChange={e => setQuantity(e.target.valueAsNumber)} />
          <p className="hint-text">
            입장권마다 이 쿠폰이 {validQuantity ? quantity : 1}장이 되도록 채워요. 이미 그만큼 가진 입장권은 건너뛰어요.
            {!validQuantity && <b> 1장부터 {MAX_PER_TICKET}장까지 고를 수 있어요.</b>}
          </p>
        </div>
      </section>

      <section className="console-card">
        <h3>누구에게</h3>
        <div className="bm-radios" role="radiogroup" aria-label="받을 사람">
          {([
            ['ALL', '전체 입장권', `${dashboard.liveTickets.toLocaleString()}장`],
            ['INSIDE', '지금 장내', `${dashboard.inside.toLocaleString()}명`],
            ['PICKED', '명단에서 고르기', picked.size > 0 ? `${picked.size.toLocaleString()}장 고름` : ''],
          ] as Array<[IssueTarget, string, string]>).map(([key, label, note]) => (
            <label key={key} className={target === key ? 'on' : ''}>
              <input type="radio" name="bm-target" checked={target === key} onChange={() => setTarget(key)} />
              <span>{label}</span>{note && <b>{note}</b>}
            </label>
          ))}
        </div>

        {target === 'PICKED' && (
          <div className="bm-pick">
            <input type="search" className="bm-search" value={query} placeholder="입장권 번호나 이메일로 찾기"
              aria-label="입장권 번호나 이메일로 찾기" onChange={e => setQuery(e.target.value)} />
            <div className="bm-pick-tools">
              <label className="field-inline">
                <input type="checkbox" checked={insideOnly} onChange={e => setInsideOnly(e.target.checked)} /> 장내만
              </label>
              <button type="button" className="btn-tiny" disabled={page.items.length === 0} onClick={togglePage}>
                {pageAllPicked ? '이 쪽 선택 해제' : '이 쪽 모두 선택'}</button>
              {picked.size > 0 && (
                <button type="button" className="btn-tiny" onClick={() => setPicked(new Map())}>선택 비우기</button>
              )}
            </div>
            {loading && page.items.length === 0 ? <p className="console-none">불러오는 중…</p>
              : page.items.length === 0 ? <p className="console-none">찾는 입장권이 없어요.</p> : (
                <ul className="bm-list bm-checks">
                  {page.items.map(row => (
                    <li key={row.ticketId}>
                      <label>
                        <input type="checkbox" checked={picked.has(row.ticketId)} onChange={() => toggle(row)} />
                        <span className="bm-row-main">
                          <b>{row.ticketRef}</b>
                          {row.inside && <span className="pill pill-in">장내</span>}
                          {row.held > 0 && <span className="pill pill-wait">이미 {row.held}장</span>}
                        </span>
                        <small>{row.emailMasked ?? '이메일 없음'}</small>
                      </label>
                    </li>
                  ))}
                </ul>
              )}
            <Pager page={page} unit="장" onPage={setPageNo} />
          </div>
        )}
      </section>

      <button type="button" className="btn-primary bm-submit" disabled={!ready || busy} onClick={() => setConfirming(true)}>
        {reach > 0 ? `입장권 ${reach.toLocaleString()}장에 발급하기` : '받을 입장권을 골라 주세요'}
      </button>

      <ConfirmDialog
        open={confirming}
        title={offer ? `'${offer.title}' 쿠폰을 발급할까요?` : '쿠폰을 발급할까요?'}
        tone="primary"
        message={`입장권 ${reach.toLocaleString()}장이 이 쿠폰을 받아요. 받은 사람의 입장권에 바로 보여요.`}
        details={[
          ['쿠폰', offer?.title ?? ''],
          ['받는 입장권', `${reach.toLocaleString()}장`],
          ['1인당', `${quantity}장까지`],
        ]}
        confirmLabel="발급"
        busy={busy}
        onCancel={() => setConfirming(false)}
        onConfirm={() => void send()}
      />
    </div>
  );
}
