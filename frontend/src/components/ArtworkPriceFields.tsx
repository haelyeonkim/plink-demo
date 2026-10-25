import { priceDraft, type PriceData, type PriceDraft } from '../artworkPrice';
import ArtworkPrice from './ArtworkPrice';

const currencies = [['USD', '미국 달러'], ['EUR', '유로'], ['KRW', '한국 원'], ['GBP', '영국 파운드'],
  ['JPY', '일본 엔'], ['CHF', '스위스 프랑'], ['CNY', '중국 위안'], ['HKD', '홍콩 달러'],
  ['SGD', '싱가포르 달러'], ['AUD', '호주 달러'], ['CAD', '캐나다 달러']];
export default function ArtworkPriceFields({ value, onChange, id }: {
  value: PriceData; onChange: (next: PriceDraft) => void; id: string;
}) {
  const draft = priceDraft(value);
  const set = (next: Partial<PriceDraft>) => onChange({ ...draft, ...next });
  return <div className="artwork-price-fields">
    <div className="field"><label htmlFor={`${id}-mode`}>가격 표시</label>
      <select id={`${id}-mode`} value={draft.priceMode} onChange={event => set({ priceMode: event.target.value })}>
        <option value="amount">금액 표시</option><option value="inquiry">가격 문의</option><option value="hidden">미표기</option>
      </select>
    </div>
    {draft.priceMode === 'amount' && <>
      <div className="form-row">
        <div className="field"><label htmlFor={`${id}-amount`}>금액</label>
          <input id={`${id}-amount`} type="text" inputMode="decimal" pattern="[0-9]{1,16}(\.[0-9]{1,2})?"
            maxLength={19} required value={draft.priceAmount} placeholder="5000.00"
            onChange={event => set({ priceAmount: event.target.value })} /></div>
        <div className="field"><label htmlFor={`${id}-currency`}>통화</label>
          <select id={`${id}-currency`} value={draft.priceCurrency}
            onChange={event => set({ priceCurrency: event.target.value, priceKrwRate: '', priceRateDate: '' })}>
            {currencies.map(([code, label]) => <option value={code} key={code}>{code} · {label}</option>)}
          </select></div>
      </div>
      {draft.priceCurrency !== 'KRW' && <>
        <p className="hint-text">원화 참고 금액을 표시하려면 환율과 기준일을 입력하세요.</p>
        <div className="form-row">
          <div className="field"><label htmlFor={`${id}-rate`}>환율 (1 {draft.priceCurrency}당 원)</label>
            <input id={`${id}-rate`} type="text" inputMode="decimal" pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
              maxLength={19} required={!!draft.priceRateDate} value={draft.priceKrwRate}
              onChange={event => set({ priceKrwRate: event.target.value })} /></div>
          <div className="field"><label htmlFor={`${id}-date`}>환율 기준일</label>
            <input id={`${id}-date`} type="date" required={!!draft.priceKrwRate} value={draft.priceRateDate}
              onChange={event => set({ priceRateDate: event.target.value })} /></div>
        </div>
      </>}
      <ArtworkPrice value={draft} />
    </>}
    {draft.priceMode === 'inquiry' && <div className="field"><label htmlFor={`${id}-note`}>가격 안내 문구</label>
      <input id={`${id}-note`} maxLength={300} value={draft.priceNote} placeholder="가격 문의"
        onChange={event => set({ priceNote: event.target.value })} /></div>}
  </div>;
}
