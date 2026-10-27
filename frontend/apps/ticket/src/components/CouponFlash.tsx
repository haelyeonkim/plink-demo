import SealMark from './SealMark';
import { clockText } from '../motion';

/**
 * What the phone shows when a stand takes a coupon, or a terminal gives one.
 *
 * <p>It arrives on the live channel, so the holder is not being told what probably
 * happened: the row changed, and this is the row. Where a code was on screen it was also
 * spent doing this, which is why the code burns underneath.
 */
export interface Handover {
  outcome: string;
  /** Null when a terminal gave it: the coupons may belong to several booths. */
  booth: string | null;
  title: string;
  at: string;
}

export default function CouponFlash({ used, spent }: { used: Handover; spent: boolean }) {
  const given = used.outcome === 'GRANTED';
  const taken = used.outcome === 'REDEEMED' || given;
  return (
    <div className={`scan-flash verdict-${taken ? 'admit' : 'repeat'}${spent ? ' flash-spent' : ''}`}
      role="status" aria-live="assertive">
      <SealMark tone={taken ? 'admit' : 'repeat'} className="flash-seal" />
      <strong className="flash-headline">
        {given ? '쿠폰을 받았어요' : taken ? '쿠폰을 사용했어요' : '이미 사용한 쿠폰이에요'}
      </strong>
      {spent && <p className="flash-note">이 코드는 방금 사용되어 폐기됐어요.</p>}
      <dl className="flash-state">
        <dt>쿠폰</dt><dd>{used.title}</dd>
        {used.booth && <><dt>부스</dt><dd>{used.booth}</dd></>}
        <dt>시각</dt><dd>{clockText(used.at)}</dd>
      </dl>
    </div>
  );
}
