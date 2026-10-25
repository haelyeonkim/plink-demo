import { krwEstimate, priceText, type PriceData } from '../artworkPrice';

export default function ArtworkPrice({ value }: { value: PriceData }) {
  const text = priceText(value);
  const krw = krwEstimate(value);
  if (!text) return null;
  return <span className="artwork-price"><span>{text}</span>
    {krw && <small>약 ₩{krw} · {value.priceRateDate} 기준</small>}
  </span>;
}
