import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { mutate } from '@shared/auth';
import { PAGE_SIZE, percent, read, shortTime, type BoothRow, type CouponFilter, type CouponRow, type OfferRow } from '../console/model';
import { EMPTY_PAGE, Pager, useDebounced, type Paged } from './ListTools';

type View = 'offers' | 'issued';
type Counts = Record<'ISSUED' | 'REDEEMED' | 'VOID', number>;

const STATE: Record<string, [string, string]> = {
  ISSUED: ['미사용', 'wait'], REDEEMED: ['사용함', 'ok'], VOID: ['무효', 'off'],
};

const send = (url: string, method: string, body?: unknown) => mutate(url, {
  method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
}).then(read);

/**
 * The event's coupons as two tables: the kinds each booth gives, with how far each has
 * gone, and every coupon handed out, to find one, act on many at once or export them.
 */
export default function ConsoleCoupons({ sessionId, booths, boothId, onBooth, onChanged, onDeleteBooth, onNotice, onError }: {
  sessionId: number; booths: BoothRow[]; boothId: number | null; onBooth: (id: number | null) => void;
  onChanged: () => Promise<void>; onDeleteBooth: (booth: BoothRow) => void;
  onNotice: (text: string) => void; onError: (text: string) => void;
}) {
  const [view, setView] = useState<View>('offers');
  const [managing, setManaging] = useState(false);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);

  // The issued list: its own filters, page and selection.
  const [query, setQuery] = useState('');
  const search = useDebounced(query.trim());
  const [status, setStatus] = useState<CouponFilter>('ALL');
  const [offerId, setOfferId] = useState<number | null>(null);
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Paged<CouponRow> & { counts?: Counts }>(EMPTY_PAGE);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [open, setOpen] = useState<CouponRow | null>(null);
  const [revision, setRevision] = useState(0);

  const act = async (work: () => Promise<unknown>, done: string) => {
    try { await work(); if (done) onNotice(done); await onChanged(); setRevision(value => value + 1); return true; }
    catch (err) { onError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); return false; }
  };

  const scope = boothId === null ? booths : booths.filter(row => row.boothId === boothId);
  const offers = scope.flatMap(booth => booth.offers.map(offer => ({ booth, offer })));
  const sum = (key: keyof Pick<OfferRow, 'issued' | 'redeemed' | 'voided' | 'waiting'>) =>
    booths.reduce((total, booth) => total + booth.offers.reduce((inner, offer) => inner + offer[key], 0), 0);
  const totals = { issued: sum('issued'), redeemed: sum('redeemed'), voided: sum('voided'), waiting: sum('waiting') };
  const offerCount = booths.reduce((total, booth) => total + booth.offers.length, 0);

  const filterQuery = useMemo(() => {
    const params = new URLSearchParams();
    if (search) params.set('q', search);
    if (status !== 'ALL') params.set('status', status);
    if (boothId !== null) params.set('boothId', String(boothId));
    if (offerId !== null) params.set('offerId', String(offerId));
    return params;
  }, [search, status, boothId, offerId]);

  useEffect(() => { setPageNo(0); setPicked(new Set()); }, [filterQuery]);
  // A choice is about the rows on screen: another page starts it over.
  useEffect(() => { setPicked(new Set()); }, [pageNo]);
  // With no booth yet there is nothing else to do here, so the booth form opens itself.
  useEffect(() => { if (booths.length === 0) setManaging(true); }, [booths.length]);
  // The detail closes on Escape, as the other overlays do.
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  // A kind of coupon from another booth is not a filter this booth can use.
  useEffect(() => {
    if (offerId !== null && !offers.some(row => row.offer.offerId === offerId)) setOfferId(null);
  }, [offerId, offers]);

  // Only the latest request may fill the table: a slow answer to an older filter is dropped.
  const latest = useRef(0);
  const load = useCallback(async () => {
    const params = new URLSearchParams(filterQuery);
    params.set('page', String(pageNo));
    params.set('size', String(PAGE_SIZE));
    const ticket = ++latest.current;
    try {
      const next = await read(await fetch(`/api/admin/sessions/${sessionId}/coupons?${params}`)) as typeof page;
      if (ticket !== latest.current) return;
      setPage(next);
      setOpen(current => current ? next.items.find(row => row.couponId === current.couponId) ?? current : null);
    } catch (err) { onError(err instanceof Error ? err.message : '쿠폰을 불러오지 못했어요.'); }
  }, [sessionId, filterQuery, pageNo, onError]);
  useEffect(() => { if (view === 'issued') void load(); }, [view, load, revision]);

  const setMany = (ids: number[], next: 'ISSUED' | 'REDEEMED' | 'VOID') => act(async () => {
    const result = await send(`/api/admin/sessions/${sessionId}/coupons/status`, 'POST', { couponIds: ids, status: next }) as
      { changed: number; skipped: number };
    setPicked(new Set());
    const verb = next === 'VOID' ? '무효로 했어요' : next === 'REDEEMED' ? '사용 처리했어요' : '다시 쓸 수 있게 했어요';
    onNotice(`${result.changed.toLocaleString()}장을 ${verb}.${result.skipped ? ` ${result.skipped}장은 이미 그 상태이거나 바꿀 수 없어 그대로 뒀어요.` : ''}`);
  }, '');

  const showIssued = (offer: OfferRow | null, booth: BoothRow | null) => {
    if (booth) onBooth(booth.boothId);
    setOfferId(offer?.offerId ?? null);
    setStatus('ALL');
    setView('issued');
  };

  const counts = page.counts;
  const allCount = counts ? counts.ISSUED + counts.REDEEMED + counts.VOID : 0;
  const allPicked = page.items.length > 0 && page.items.every(row => picked.has(row.couponId));
  const pickedRows = page.items.filter(row => picked.has(row.couponId));

  return (
    <div className="console-stack coupons">
      <div className="kpi-grid">
        <div className="kpi kpi-accent">
          <span className="kpi-label">지급</span>
          <strong className="kpi-num">{totals.issued.toLocaleString()}</strong>
          <span className="kpi-note">쿠폰 종류 {offerCount}개 · 부스 {booths.length}곳</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">사용</span>
          <strong className="kpi-num">{totals.redeemed.toLocaleString()}</strong>
          <span className="kpi-note">사용률 {percent(totals.redeemed, totals.issued - totals.voided)}%</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">미사용</span>
          <strong className="kpi-num">{totals.waiting.toLocaleString()}</strong>
          <span className="kpi-note">관람객이 아직 들고 있는 쿠폰</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">무효</span>
          <strong className={`kpi-num${totals.voided > 0 ? ' tone-off' : ''}`}>{totals.voided.toLocaleString()}</strong>
          <span className="kpi-note">운영자가 거둔 쿠폰</span>
        </div>
      </div>

      <div className="console-tabs" role="tablist" aria-label="쿠폰 보기">
        <button type="button" role="tab" aria-selected={view === 'offers'} className={view === 'offers' ? 'on' : ''}
          onClick={() => setView('offers')}>쿠폰 종류 <em>{offerCount}</em></button>
        <button type="button" role="tab" aria-selected={view === 'issued'} className={view === 'issued' ? 'on' : ''}
          onClick={() => setView('issued')}>발급 내역 <em>{totals.issued.toLocaleString()}</em></button>
      </div>

      <div className="coupon-toolbar">
        <div className="chip-row" role="group" aria-label="부스">
          <button type="button" className={boothId === null ? 'on' : ''} aria-pressed={boothId === null}
            onClick={() => onBooth(null)}>부스 전체</button>
          {booths.map(booth => (
            <button key={booth.boothId} type="button" className={boothId === booth.boothId ? 'on' : ''}
              aria-pressed={boothId === booth.boothId} onClick={() => onBooth(booth.boothId)}>
              {booth.name} <small>{booth.offers.length}</small></button>
          ))}
        </div>
        <span className="coupon-toolbar-gap" />
        <button type="button" className={`btn-tiny${managing ? ' on' : ''}`} aria-expanded={managing}
          onClick={() => setManaging(value => !value)}>부스 관리</button>
      </div>

      {managing && (
        <section className="console-card booth-manage" aria-labelledby="booth-manage">
          <h3 id="booth-manage">부스</h3>
          {booths.length > 0 && (
            <div className="console-table-box flat">
              <table className="console-table">
                <thead><tr><th>이름</th><th>위치 안내</th><th className="num">쿠폰 종류</th><th className="num">지급</th><th aria-label="작업" /></tr></thead>
                <tbody>
                  {booths.map(booth => (
                    <tr key={booth.boothId}>
                      <td><b>{booth.name}</b></td>
                      <td className="muted">{booth.note ?? '–'}</td>
                      <td className="num">{booth.offers.length}</td>
                      <td className="num">{booth.offers.reduce((n, o) => n + o.issued, 0).toLocaleString()}</td>
                      <td className="row-actions">
                        <button type="button" className="btn-tiny btn-tiny-danger" onClick={() => onDeleteBooth(booth)}>삭제</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <form className="inline-form" onSubmit={e => {
            e.preventDefault();
            const form = e.currentTarget;
            const data = new FormData(form);
            void act(() => send(`/api/admin/sessions/${sessionId}/booths`, 'POST', { name: data.get('name'), note: data.get('note') }),
              `${data.get('name')} 부스를 만들었어요.`).then(ok => { if (ok) form.reset(); });
          }}>
            <label>이름<input name="name" required maxLength={80} placeholder="예: 커피 스탠드" /></label>
            <label>위치 안내<input name="note" maxLength={120} placeholder="예: 로비 왼쪽" /></label>
            <button className="btn-secondary" type="submit">부스 추가</button>
          </form>
        </section>
      )}

      {view === 'offers' && (<>
        <div className="coupon-toolbar">
          <p className="console-summary">
            {boothId === null ? '모든 부스' : scope[0]?.name}의 쿠폰 종류 {offers.length}개 · 발급 중인 것만 입장권에 줄 수 있어요
          </p>
          <span className="coupon-toolbar-gap" />
          {booths.length > 0 && !adding && (
            <button type="button" className="btn-secondary" onClick={() => setAdding(true)}>+ 쿠폰 종류 추가</button>
          )}
        </div>

        {adding && (
          <form className="console-card inline-form" onSubmit={e => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            const target = Number(data.get('booth'));
            void act(() => send(`/api/admin/booths/${target}/offers`, 'POST', { title: data.get('title'), detail: data.get('detail') }),
              `${data.get('title')} 쿠폰을 추가했어요.`).then(ok => { if (ok) setAdding(false); });
          }}>
            <label>부스
              <select name="booth" defaultValue={boothId ?? booths[0]?.boothId}>
                {booths.map(booth => <option key={booth.boothId} value={booth.boothId}>{booth.name}</option>)}
              </select></label>
            <label>쿠폰 이름<input name="title" required maxLength={80} placeholder="예: 웰컴 드링크" /></label>
            <label className="grow">상세 안내<input name="detail" placeholder="예: 따뜻한 음료 1잔, 오후 6시까지" /></label>
            <button className="btn-primary" type="submit">추가</button>
            <button className="btn-secondary" type="button" onClick={() => setAdding(false)}>취소</button>
          </form>
        )}

        <div className="console-table-box">
          <table className="console-table offer-table">
            <thead>
              <tr>
                <th>쿠폰</th>{boothId === null && <th>부스</th>}<th>상태</th>
                <th className="num">지급</th><th className="num">사용</th><th className="rate">사용률</th>
                <th className="num">미사용</th><th className="num">무효</th><th aria-label="작업" />
              </tr>
            </thead>
            <tbody>
              {offers.map(({ booth, offer }) => editing === offer.offerId ? (
                <tr key={offer.offerId} className="is-open">
                  <td colSpan={boothId === null ? 9 : 8}>
                    <form className="inline-form" onSubmit={e => {
                      e.preventDefault();
                      const data = new FormData(e.currentTarget);
                      void act(() => send(`/api/admin/offers/${offer.offerId}`, 'PUT', { title: data.get('title'), detail: data.get('detail') }),
                        '쿠폰 종류를 고쳤어요. 이미 준 쿠폰은 받을 때의 문구 그대로예요.').then(ok => { if (ok) setEditing(null); });
                    }}>
                      <label>쿠폰 이름<input name="title" required maxLength={80} defaultValue={offer.title} /></label>
                      <label className="grow">상세 안내<input name="detail" defaultValue={offer.detail ?? ''} /></label>
                      <button className="btn-primary" type="submit">저장</button>
                      <button className="btn-secondary" type="button" onClick={() => setEditing(null)}>취소</button>
                    </form>
                  </td>
                </tr>
              ) : (
                <tr key={offer.offerId} className={offer.active ? '' : 'is-off'}>
                  <td className="wrap"><b>{offer.title}</b>{offer.detail && <small className="cell-note">{offer.detail}</small>}</td>
                  {boothId === null && <td>{booth.name}</td>}
                  <td><span className={`pill pill-${offer.active ? 'ok' : 'off'}`}>{offer.active ? '발급 중' : '중지'}</span></td>
                  <td className="num">{offer.issued.toLocaleString()}</td>
                  <td className="num">{offer.redeemed.toLocaleString()}</td>
                  <td className="rate">
                    <span className="meter">
                      <span className="meter-track"><span style={{ width: `${percent(offer.redeemed, offer.issued - offer.voided)}%` }} /></span>
                      <span className="meter-value">{offer.issued - offer.voided > 0 ? `${percent(offer.redeemed, offer.issued - offer.voided)}%` : '–'}</span>
                    </span>
                  </td>
                  <td className="num">{offer.waiting.toLocaleString()}</td>
                  <td className="num muted">{offer.voided.toLocaleString()}</td>
                  <td className="row-actions">
                    <button type="button" className="btn-tiny" disabled={offer.issued === 0} onClick={() => showIssued(offer, booth)}>발급 내역</button>
                    <button type="button" className="btn-tiny" onClick={() => setEditing(offer.offerId)}>수정</button>
                    <button type="button" className="btn-tiny" onClick={() => void act(
                      () => send(`/api/admin/offers/${offer.offerId}/active`, 'POST', { active: !offer.active }),
                      offer.active ? `${offer.title} 발급을 중지했어요. 이미 준 쿠폰은 그대로 쓸 수 있어요.` : `${offer.title}을 다시 발급할 수 있어요.`)}>
                      {offer.active ? '중지' : '다시 발급'}</button>
                    {offer.issued === 0 && (
                      <button type="button" className="btn-tiny btn-tiny-danger" onClick={() => {
                        if (window.confirm(`'${offer.title}' 쿠폰 종류를 지울까요?`)) {
                          void act(() => send(`/api/admin/offers/${offer.offerId}`, 'DELETE'), `${offer.title} 쿠폰 종류를 지웠어요.`);
                        }
                      }}>삭제</button>
                    )}
                  </td>
                </tr>
              ))}
              {offers.length === 0 && (
                <tr><td colSpan={9} className="console-none">
                  {booths.length === 0 ? '먼저 부스를 만들어 주세요. 위의 부스 관리에서 추가할 수 있어요.'
                    : '아직 정해 둔 쿠폰 종류가 없어요. 쿠폰 종류 추가로 시작하세요.'}
                </td></tr>
              )}
            </tbody>
            {offers.length > 1 && (() => {
              const t = offers.reduce((acc, { offer }) => ({
                issued: acc.issued + offer.issued, redeemed: acc.redeemed + offer.redeemed,
                waiting: acc.waiting + offer.waiting, voided: acc.voided + offer.voided,
              }), { issued: 0, redeemed: 0, waiting: 0, voided: 0 });
              return (
                <tfoot>
                  <tr>
                    <td><b>합계</b></td>{boothId === null && <td />}<td />
                    <td className="num"><b>{t.issued.toLocaleString()}</b></td>
                    <td className="num"><b>{t.redeemed.toLocaleString()}</b></td>
                    <td className="rate"><b>{t.issued - t.voided > 0 ? `${percent(t.redeemed, t.issued - t.voided)}%` : '–'}</b></td>
                    <td className="num"><b>{t.waiting.toLocaleString()}</b></td>
                    <td className="num muted">{t.voided.toLocaleString()}</td><td />
                  </tr>
                </tfoot>
              );
            })()}
          </table>
        </div>
        <p className="console-foot">한 장이라도 지급된 종류는 지울 수 없어 중지만 돼요. 이름이나 안내를 고쳐도 이미 받은 쿠폰은 받을 때의 문구 그대로예요.</p>
      </>)}

      {view === 'issued' && (<>
        <div className="coupon-toolbar">
          <label className="visually-hidden" htmlFor="coupon-q">쿠폰 찾기</label>
          <input id="coupon-q" className="coupon-search" type="search" value={query} onChange={e => setQuery(e.target.value)}
            placeholder="입장권·좌석·이메일·쿠폰 찾기" />
          <div className="chip-row" role="group" aria-label="상태">
            {([['ALL', '전체', allCount], ['ISSUED', '미사용', counts?.ISSUED], ['REDEEMED', '사용함', counts?.REDEEMED], ['VOID', '무효', counts?.VOID]] as Array<[CouponFilter, string, number | undefined]>)
              .map(([key, label, n]) => (
                <button key={key} type="button" className={status === key ? 'on' : ''} aria-pressed={status === key}
                  onClick={() => setStatus(key)}>{label} <small>{(n ?? 0).toLocaleString()}</small></button>
              ))}
          </div>
          <span className="coupon-toolbar-gap" />
          <label className="visually-hidden" htmlFor="coupon-offer">쿠폰 종류</label>
          <select id="coupon-offer" className="coupon-select" value={offerId ?? ''}
            onChange={e => setOfferId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">쿠폰 종류 전체</option>
            {offers.map(({ booth, offer }) => (
              <option key={offer.offerId} value={offer.offerId}>{boothId === null ? `${booth.name} · ` : ''}{offer.title}</option>
            ))}
          </select>
          <a className="btn-secondary" href={`/api/admin/sessions/${sessionId}/coupons/export?${filterQuery}`} download>CSV</a>
        </div>

        {pickedRows.length > 0 && (
          <div className="bulk-bar" role="region" aria-label="선택한 쿠폰">
            <b>{pickedRows.length}장 선택</b>
            <button type="button" disabled={!pickedRows.some(row => row.status === 'ISSUED')}
              onClick={() => void setMany(pickedRows.map(row => row.couponId), 'REDEEMED')}>사용 처리</button>
            <button type="button" disabled={!pickedRows.some(row => row.status === 'VOID')}
              onClick={() => void setMany(pickedRows.map(row => row.couponId), 'ISSUED')}>되살리기</button>
            <span className="bulk-gap" />
            <button type="button" className="danger" disabled={!pickedRows.some(row => row.status !== 'VOID')} onClick={() => {
              if (window.confirm(`쿠폰 ${pickedRows.length}장을 무효로 할까요? 관람객 화면에서 바로 사라져요.`)) {
                void setMany(pickedRows.map(row => row.couponId), 'VOID');
              }
            }}>무효화…</button>
            <button type="button" className="quiet" onClick={() => setPicked(new Set())}>선택 해제</button>
          </div>
        )}

        <div className={`tickets-layout${open ? ' with-detail' : ''}`}>
          <div className="console-table-box">
            <table className="console-table ticket-list coupon-table">
              <thead>
                <tr>
                  <th className="check">
                    <input type="checkbox" aria-label="이 페이지 전체 선택" checked={allPicked}
                      onChange={() => setPicked(allPicked ? new Set() : new Set(page.items.map(row => row.couponId)))} />
                  </th>
                  <th>쿠폰</th><th>입장권</th><th>발급일시</th><th>상태</th><th>사용</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map(row => {
                  const isOpen = open?.couponId === row.couponId;
                  return (
                    <tr key={row.couponId} className={`${row.status === 'VOID' ? 'is-off' : ''}${isOpen ? ' is-open' : ''}${picked.has(row.couponId) ? ' is-checked' : ''}`}>
                      <td className="check">
                        <input type="checkbox" aria-label={`${row.ticketRef} ${row.title} 선택`} checked={picked.has(row.couponId)}
                          onChange={() => setPicked(current => {
                            const next = new Set(current);
                            if (next.has(row.couponId)) next.delete(row.couponId); else next.add(row.couponId);
                            return next;
                          })} />
                      </td>
                      <td>
                        <button type="button" className="ticket-ref" aria-expanded={isOpen}
                          onClick={() => setOpen(isOpen ? null : row)}>{row.title}</button>
                        {boothId === null && row.booth && <small className="cell-note">{row.booth}</small>}
                      </td>
                      <td><b>{row.ticketRef}</b>{row.seat && <span className="muted"> · {row.seat}</span>}
                        <small className="cell-note">{row.issuedToEmail}</small></td>
                      <td className="muted">{row.issuedAt ? shortTime(row.issuedAt) : '–'}</td>
                      <td><span className={`pill pill-${STATE[row.status]?.[1] ?? 'wait'}`}>{STATE[row.status]?.[0] ?? row.status}</span></td>
                      <td className="muted">{row.redeemedAt ? <>{shortTime(row.redeemedAt)}
                        <small className="cell-note">{row.redeemedAtGate ?? '운영자 처리'}</small></> : '–'}</td>
                    </tr>
                  );
                })}
                {page.items.length === 0 && (
                  <tr><td colSpan={6} className="console-none">
                    {search || status !== 'ALL' || offerId !== null ? '조건에 맞는 쿠폰이 없어요.' : '아직 지급한 쿠폰이 없어요.'}
                  </td></tr>
                )}
              </tbody>
            </table>
            <Pager page={page} unit="장" onPage={setPageNo} />
          </div>

          {open && <div className="ticket-detail-scrim" aria-hidden="true" onClick={() => setOpen(null)} />}
          {open && (
            <aside className="ticket-detail" aria-label={`${open.title} 상세`}>
              <div className="ticket-detail-head">
                <div>
                  <small>{open.booth ?? '부스'}</small>
                  <h3>{open.title}</h3>
                </div>
                <button type="button" className="sheet-close" aria-label="상세 닫기" onClick={() => setOpen(null)}>✕</button>
              </div>
              <div className="ticket-detail-tags">
                <span className={`pill pill-${STATE[open.status]?.[1] ?? 'wait'}`}>{STATE[open.status]?.[0] ?? open.status}</span>
                <span className="pill pill-out">{open.ticketRef}{open.seat ? ` · ${open.seat}` : ''}</span>
              </div>
              <dl className="ticket-detail-facts">
                <div><dt>받는 사람</dt><dd>{open.issuedToEmail ?? '–'}</dd></div>
                {open.detail && <div><dt>안내</dt><dd>{open.detail}</dd></div>}
              </dl>
              <section className="ticket-detail-part">
                <h4>기록</h4>
                <ol className="timeline">
                  {open.issuedAt && <li><span className="muted">{shortTime(open.issuedAt)}</span><span>발급</span></li>}
                  {open.redeemedAt && <li><span className="muted">{shortTime(open.redeemedAt)}</span><span>사용 · {open.redeemedAtGate ?? '운영자 처리'}</span></li>}
                  {open.status === 'VOID' && <li><span className="muted">–</span><span>운영자가 무효로 함</span></li>}
                </ol>
              </section>
              <div className="ticket-detail-actions">
                {open.status === 'ISSUED' && (
                  <button type="button" className="btn-secondary" onClick={() => void setMany([open.couponId], 'REDEEMED')}>사용 처리</button>
                )}
                {open.status === 'VOID' ? (
                  <button type="button" className="btn-secondary" onClick={() => void setMany([open.couponId], 'ISSUED')}>되살리기</button>
                ) : (
                  <button type="button" className="btn-secondary danger" onClick={() => {
                    if (window.confirm(`${open.ticketRef}의 '${open.title}' 쿠폰을 무효로 할까요?`)) void setMany([open.couponId], 'VOID');
                  }}>무효화</button>
                )}
              </div>
            </aside>
          )}
        </div>
      </>)}
    </div>
  );
}
