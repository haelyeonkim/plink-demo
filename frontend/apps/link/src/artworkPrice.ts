export interface PriceData {
  price?: string;
  priceMode?: string;
  priceAmount?: string;
  priceCurrency?: string;
  priceNote?: string;
  priceKrwRate?: string;
  priceRateDate?: string;
}
export interface PriceDraft {
  priceMode: string;
  priceAmount: string;
  priceCurrency: string;
  priceNote: string;
  priceKrwRate: string;
  priceRateDate: string;
}
export function priceDraft(value: PriceData = {}): PriceDraft {
  return {
    priceMode: value.priceMode || (value.price ? 'inquiry' : 'hidden'),
    priceAmount: value.priceAmount || '', priceCurrency: value.priceCurrency || 'USD',
    priceNote: value.priceNote || (!value.priceMode ? value.price || '' : ''),
    priceKrwRate: value.priceKrwRate || '', priceRateDate: value.priceRateDate || '',
  };
}
function grouped(value: string): string {
  const [integer, fraction] = value.split('.');
  const digits = fraction?.replace(/0+$/, '');
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (digits ? `.${digits}` : '');
}
export function priceText(value: PriceData): string {
  if (!value.priceMode) return value.price || '';
  if (value.priceMode === 'hidden') return '';
  if (value.priceMode === 'inquiry') return value.priceNote || '가격 문의';
  return value.priceAmount ? `${value.priceCurrency || 'USD'} ${grouped(value.priceAmount)}` : '';
}
/** Integer arithmetic keeps large amounts and half-won rounding exact. */
export function krwEstimate(value: PriceData): string {
  if (value.priceMode !== 'amount' || value.priceCurrency === 'KRW' || !value.priceRateDate
    || !/^[0-9]{1,16}(?:\.[0-9]{1,2})?$/.test(value.priceAmount || '')
    || !/^[0-9]{1,12}(?:\.[0-9]{1,6})?$/.test(value.priceKrwRate || '')) return '';
  const scaled = (text: string, places: number) => {
    const [integer, fraction = ''] = text.split('.');
    return BigInt(integer + fraction.padEnd(places, '0'));
  };
  const rate = scaled(value.priceKrwRate!, 6);
  if (rate === 0n) return '';
  return grouped(((scaled(value.priceAmount!, 2) * rate + 50000000n) / 100000000n).toString());
}
