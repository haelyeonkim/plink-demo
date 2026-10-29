import type { TicketCoupon } from './api';

/**
 * A booth's colour on the holder's coupons: the same booth always the same colour, so a
 * stub can be recognised at a glance across the list, the code and the stamp.
 */
const PALETTE = ['#6a4cf0', '#d0892a', '#1c8a6a', '#3d4663', '#c2366d', '#2f6fd0'];

export function boothColor(booth: string | null): string {
  const name = booth ?? '';
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.codePointAt(0)!) >>> 0;
  return PALETTE[hash % PALETTE.length];
}

export function boothInitial(booth: string | null): string {
  return booth ? [...booth][0] : '쿠';
}

/**
 * Coupons that promise the same thing at the same stand, as one card: "웰컴 드링크 ×2".
 * The ids are oldest first, so the code that opens spends the oldest.
 */
export interface CouponGroup {
  key: string;
  first: TicketCoupon;
  ids: number[];
}

export function liveGroups(coupons: TicketCoupon[] | undefined): CouponGroup[] {
  const groups = new Map<string, CouponGroup>();
  for (const coupon of [...(coupons ?? [])].sort((a, b) => a.couponId - b.couponId)) {
    if (coupon.status !== 'ISSUED') continue;
    const key = `${coupon.booth}|${coupon.title}|${coupon.detail ?? ''}`;
    const group = groups.get(key);
    if (group) group.ids.push(coupon.couponId);
    else groups.set(key, { key, first: coupon, ids: [coupon.couponId] });
  }
  return [...groups.values()];
}

/** What is left of the group a coupon belongs to, after it was spent. */
export function groupOf(coupons: TicketCoupon[] | undefined, like: { booth: string | null; title: string; detail?: string | null }) {
  return liveGroups(coupons).find(group => group.first.booth === like.booth && group.first.title === like.title
    && (like.detail === undefined || (group.first.detail ?? '') === (like.detail ?? ''))) ?? null;
}
