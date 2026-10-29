import { useEffect, useState } from 'react';
import type { TicketCoupon } from '../ticket/api';
import { boothColor, boothInitial, liveGroups, type CouponGroup } from '../ticket/coupons';
import { clockText } from '../motion';

/** "10.29 13:45": the day as well as the time, since a coupon can wait from one day to the next. */
function issuedText(at: string): string {
  const d = new Date(at);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}.${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const QrIcon = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM14 18h2M18 14h2" />
  </svg>
);

/**
 * The holder's coupons: what can still be used, as stubs with their own code one tap
 * away, and what was used, stamped with when. A stub opens what it promises and how to
 * use it.
 */
export default function CouponWallet({ coupons, busy, onCode, onNews }: {
  coupons: TicketCoupon[] | undefined;
  busy: boolean;
  /** Opens the code of one coupon of the group: the oldest. */
  onCode: (group: CouponGroup) => void;
  /** The board, where this booth's notices are; null when it is not open to this holder yet. */
  onNews: (() => void) | null;
}) {
  const [shown, setShown] = useState<'live' | 'used'>('live');
  const [open, setOpen] = useState<CouponGroup | null>(null);
  const live = liveGroups(coupons);
  const liveCount = live.reduce((sum, group) => sum + group.ids.length, 0);
  const used = (coupons ?? []).filter(coupon => coupon.status === 'REDEEMED')
    .sort((a, b) => (b.redeemedAt ?? '').localeCompare(a.redeemedAt ?? ''));

  // A group that ran out while its sheet was open closes the sheet.
  useEffect(() => {
    if (open && !live.some(group => group.key === open.key)) setOpen(null);
  }, [open, live]);
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  const current = open ? live.find(group => group.key === open.key) ?? open : null;

  return (
    <div className="wallet">
      <header className="tab-head">
        <h2>쿠폰</h2>
        <span>쓸 수 있는 쿠폰 <b>{liveCount}장</b></span>
      </header>

      <div className="wallet-tabs" role="tablist" aria-label="쿠폰 보기">
        <button type="button" role="tab" aria-selected={shown === 'live'} className={shown === 'live' ? 'on' : ''}
          onClick={() => setShown('live')}>보유쿠폰 {liveCount}</button>
        <button type="button" role="tab" aria-selected={shown === 'used'} className={shown === 'used' ? 'on' : ''}
          onClick={() => setShown('used')}>사용함 {used.length}</button>
      </div>

      {shown === 'live' && (<>
        {live.length > 0 && (
          <p className="wallet-hint"><QrIcon size={16} /> 쿠폰마다 QR이 따로 있어요. 그 부스 단말에 비추면 한 장씩 쓰여요.</p>
        )}
        {live.length === 0 ? (
          <p className="console-none">{(coupons ?? []).length === 0
            ? '아직 받은 쿠폰이 없어요. 입장하거나 부스에 들르면 쿠폰이 생길 수 있어요.'
            : '쓸 수 있는 쿠폰을 모두 썼어요.'}</p>
        ) : (
          <ul className="stub-list">
            {live.map(group => {
              const coupon = group.first;
              const n = group.ids.length;
              return (
                <li key={group.key} className="stub">
                  <button type="button" className="stub-main" onClick={() => setOpen(group)}>
                    <span className="stub-tab" style={{ background: boothColor(coupon.booth) }} aria-hidden="true">
                      <b>{boothInitial(coupon.booth)}</b>{n > 1 && <small>{n}장</small>}
                    </span>
                    <span className="stub-text">
                      <b>{coupon.title}{n > 1 && <span className="stub-count"> ×{n}</span>}</b>
                      {coupon.detail && <span className="stub-detail">{coupon.detail}</span>}
                      <span className="stub-where">{[coupon.booth, coupon.boothNote].filter(Boolean).join(' · ')}</span>
                    </span>
                  </button>
                  <button type="button" className="stub-qr" disabled={busy} aria-label={`${coupon.title} QR 열기`}
                    onClick={() => onCode(group)}>
                    <QrIcon /><span>QR</span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </>)}

      {shown === 'used' && (
        used.length === 0 ? <p className="console-none">아직 사용한 쿠폰이 없어요.</p> : (
          <ul className="stub-list">
            {used.map(coupon => (
              <li key={coupon.couponId} className="stub stub-used">
                <span className="stub-main">
                  <span className="stub-tab" aria-hidden="true"><b>{boothInitial(coupon.booth)}</b></span>
                  <span className="stub-text">
                    <b>{coupon.title}</b>
                    <span className="stub-where">{[coupon.booth, coupon.boothNote].filter(Boolean).join(' · ')}</span>
                  </span>
                </span>
                <span className="stub-stamp">
                  <b>사용함</b>{coupon.redeemedAt && <small>{clockText(coupon.redeemedAt)}</small>}
                </span>
              </li>
            ))}
          </ul>
        )
      )}

      {current && (
        <div className="sheet-scrim wallet-scrim" role="presentation" onClick={() => setOpen(null)}>
          <section className="wallet-sheet" role="dialog" aria-modal="true" aria-labelledby="wallet-sheet-title"
            onClick={event => event.stopPropagation()}>
            <span className="sheet-grip" aria-hidden="true" />
            <div className="wallet-sheet-head" style={{ background: boothColor(current.first.booth) }}>
              <span className="wallet-sheet-mark" aria-hidden="true">{boothInitial(current.first.booth)}</span>
              <span>
                <small>{[current.first.booth, current.first.boothNote].filter(Boolean).join(' · ')}</small>
                <h3 id="wallet-sheet-title">{current.first.title}</h3>
              </span>
              <em>{current.ids.length}장</em>
            </div>
            {current.first.detail && <p className="wallet-sheet-detail">{current.first.detail}</p>}
            <ol className="wallet-steps">
              <li>{current.first.booth ?? '부스'}{current.first.boothNote ? `(${current.first.boothNote})` : ''} 단말 앞에서</li>
              <li>아래 버튼으로 이 쿠폰의 QR을 열어 비추면</li>
              <li>한 장이 쓰이고, 남은 장수가 바로 보여요</li>
            </ol>
            <dl className="wallet-facts">
              {current.first.issuedAt && <><dt>발급일시</dt><dd>{issuedText(current.first.issuedAt)}</dd></>}
              <dt>남은 장수</dt><dd>{current.ids.length}장{current.ids.length > 1 ? ' · 한 번에 한 장씩 써요' : ''}</dd>
            </dl>
            <button type="button" className="btn-primary wallet-use" disabled={busy}
              onClick={() => onCode(current)}><QrIcon size={18} /> QR 열고 쓰기</button>
            <div className="wallet-sheet-row">
              {onNews && <button type="button" className="btn-secondary" onClick={() => { setOpen(null); onNews(); }}>부스 소식 보기</button>}
              <button type="button" className="btn-secondary" onClick={() => setOpen(null)}>닫기</button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
