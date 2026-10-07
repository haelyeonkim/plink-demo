import type { TicketCoupon } from '../ticket/api';

/**
 * What this ticket can still collect, on the ticket itself.
 *
 * <p>A coupon is only worth having if the holder can see it without going looking, so
 * the first few sit on the ticket and the rest are one tap away. Each row opens what it
 * actually promises - the wording the organiser gave, not a title guessing at it.
 */
export default function CouponList({ coupons, onOpen }: {
  coupons: TicketCoupon[];
  onOpen: (coupon: TicketCoupon) => void;
}) {
  if (coupons.length === 0) return null;
  const live = coupons.filter(coupon => coupon.status === 'ISSUED');
  const spent = coupons.length - live.length;
  // Unused ones first: they are the ones that can still be acted on. Three refills at
  // one stand are one row that says three, not three rows that say the same thing.
  const rows: { coupon: TicketCoupon; count: number }[] = [];
  for (const coupon of [...live, ...coupons.filter(coupon => coupon.status !== 'ISSUED')]) {
    const same = rows.find(row => row.coupon.booth === coupon.booth
      && row.coupon.title === coupon.title && row.coupon.status === coupon.status);
    if (same) same.count += 1;
    else rows.push({ coupon, count: 1 });
  }

  return (
    <div className="coupon-strip">
      <p className="coupon-head">
        쿠폰 <b>{live.length}장</b>
        {spent > 0 && <span className="coupon-spent"> · 사용 {spent}장</span>}
      </p>
      <ul>
        {rows.map(({ coupon, count }) => (
          <li key={coupon.couponId}>
            <button type="button" className="coupon-row" onClick={() => onOpen(coupon)}>
              <span className="coupon-title">
                {coupon.title}
                {count > 1 && <span className="coupon-count"> ×{count}</span>}
              </span>
              {coupon.booth && <span className="coupon-booth">{coupon.booth}</span>}
              <span className={`pill pill-${coupon.status === 'REDEEMED' ? 'off' : 'ok'}`}>
                {coupon.status === 'REDEEMED' ? '사용함' : '사용 가능'}
              </span>
              {/* It opens: the row says so the way every other list on a phone does. */}
              <span className="coupon-chevron" aria-hidden="true">›</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
