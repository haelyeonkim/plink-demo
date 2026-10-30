import { useState } from 'react';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { createOffer, deleteOffer, setOfferActive, updateOffer, type BoothOffer } from '../../booth/api';
import type { BoothTabProps } from './shared';

/**
 * The kinds of coupon this booth gives. A manager's new kind is usable at once - the
 * organiser sees it in the event's console like any other and can still switch it off.
 */
export default function BoothOffers({ token, dashboard, refresh, fail, notify }: BoothTabProps) {
  // null: the form adds a new kind; an offer: the form edits that one.
  const [editing, setEditing] = useState<BoothOffer | null>(null);
  const [title, setTitle] = useState('');
  const [detail, setDetail] = useState('');
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<BoothOffer | null>(null);

  async function run(work: () => Promise<unknown>, done: string): Promise<boolean> {
    setBusy(true);
    try { await work(); notify(done); await refresh(); return true; }
    catch (err) { fail(err); return false; }
    finally { setBusy(false); }
  }

  function edit(offer: BoothOffer | null) {
    setEditing(offer);
    setTitle(offer?.title ?? '');
    setDetail(offer?.detail ?? '');
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const ok = editing
      ? await run(() => updateOffer(token, editing.offerId, title.trim(), detail.trim()),
        '쿠폰 종류를 고쳤어요. 이미 준 쿠폰은 받을 때의 문구 그대로예요.')
      : await run(() => createOffer(token, title.trim(), detail.trim()),
        `${title.trim()} 쿠폰을 만들었어요. 바로 발급할 수 있어요.`);
    if (ok) edit(null);
  }

  return (
    <div className="bm-stack">
      <section className="console-card">
        <h3>{editing ? `'${editing.title}' 고치기` : '새 쿠폰 종류'}</h3>
        <form className="bm-form" onSubmit={e => void submit(e)}>
          <div className="field">
            <label htmlFor="bm-offer-title">쿠폰 이름</label>
            <input id="bm-offer-title" required maxLength={80} value={title}
              onChange={e => setTitle(e.target.value)} placeholder="예: 웰컴 드링크" />
          </div>
          <div className="field">
            <label htmlFor="bm-offer-detail">상세 안내 <span className="label-note">(선택)</span></label>
            <input id="bm-offer-detail" maxLength={200} value={detail}
              onChange={e => setDetail(e.target.value)} placeholder="예: 따뜻한 음료 1잔, 오후 6시까지" />
          </div>
          <div className="bm-actions">
            <button className="btn-primary" type="submit" disabled={busy || !title.trim()}>
              {editing ? '저장' : '만들기'}</button>
            {editing && <button className="btn-secondary" type="button" onClick={() => edit(null)}>취소</button>}
          </div>
        </form>
      </section>

      <section className="console-card">
        <h3>우리 부스 쿠폰 {dashboard.offers.length}종</h3>
        {dashboard.offers.length === 0 ? <p className="console-none">아직 만든 쿠폰이 없어요.</p> : (
          <ul className="bm-list">
            {dashboard.offers.map(offer => (
              <li key={offer.offerId} className={offer.active ? '' : 'is-off'}>
                <span className="bm-row-main">
                  <b>{offer.title}</b>
                  <span className={`pill pill-${offer.active ? 'ok' : 'off'}`}>{offer.active ? '발급 중' : '중지'}</span>
                </span>
                {offer.detail && <small>{offer.detail}</small>}
                <small>지급 {offer.issued.toLocaleString()} · 사용 {offer.redeemed.toLocaleString()}</small>
                <span className="bm-row-actions">
                  <button type="button" className="btn-tiny" disabled={busy} onClick={() => edit(offer)}>고치기</button>
                  <button type="button" className="btn-tiny" disabled={busy}
                    onClick={() => void run(() => setOfferActive(token, offer.offerId, !offer.active),
                      offer.active ? `${offer.title} 발급을 중지했어요. 이미 준 쿠폰은 그대로 쓸 수 있어요.`
                        : `${offer.title}을 다시 발급할 수 있어요.`)}>
                    {offer.active ? '중지' : '다시 발급'}</button>
                  <button type="button" className="btn-tiny btn-tiny-danger" disabled={busy}
                    onClick={() => setRemoving(offer)}>삭제</button>
                </span>
              </li>
            ))}
          </ul>
        )}
        <p className="hint-text">
          한 장이라도 지급된 종류는 지울 수 없어 중지만 돼요. 이름이나 안내를 고쳐도 이미 받은 쿠폰은 받을 때의 문구 그대로예요.
        </p>
      </section>

      <ConfirmDialog
        open={removing != null}
        title={`'${removing?.title}' 쿠폰 종류를 지울까요?`}
        message="아직 아무에게도 주지 않은 종류만 지울 수 있어요. 지운 뒤에는 되돌릴 수 없어요."
        busy={busy}
        onCancel={() => setRemoving(null)}
        onConfirm={() => {
          const offer = removing!;
          void run(() => deleteOffer(token, offer.offerId), `${offer.title} 쿠폰 종류를 지웠어요.`)
            .then(() => { setRemoving(null); if (editing?.offerId === offer.offerId) edit(null); });
        }}
      />
    </div>
  );
}
