import { useId } from 'react';
import type { ClaimChoice } from '../passkeyClaim';

/**
 * What a visitor agrees to before claiming a link or a ticket, and the two ways to do it.
 * The first passkey registered claims it for good, so the page says so and asks them to
 * confirm it was sent to them; then it offers the likelier way first.
 */
export default function ClaimConsent({ what, preferred, agreed, onAgreed, busy, disabled, onClaim }: {
  /** What is being claimed, as the sentences name it: "링크" or "입장권". */
  what: '링크' | '입장권';
  preferred: ClaimChoice;
  agreed: boolean;
  onAgreed: (value: boolean) => void;
  busy: boolean;
  disabled?: boolean;
  onClaim: (choice: ClaimChoice) => void;
}) {
  const noticeId = useId();
  // 링크 ends in a vowel, 입장권 in a consonant: the particles follow.
  const asObject = what === '링크' ? '링크를' : '입장권을';
  const asSubject = what === '링크' ? '링크가' : '입장권이';
  const other: ClaimChoice = preferred === 'new' ? 'existing' : 'new';
  const label = (choice: ClaimChoice) => choice === 'new' ? '이 휴대폰으로 받기' : '내 패스키로 받기';
  const otherLabel = other === 'existing' ? '이미 패스링크 패스키가 있어요' : '처음이에요 · 새로 만들기';
  return (
    <div className="claim-consent">
      <div className="claim-notice" id={noticeId}>
        <h3>처음 등록한 휴대폰에서만 열려요</h3>
        <p>등록한 뒤에는 이 {asObject} 다른 사람에게 전달해도 열리지 않아요. 본인이 받은 {what}인지 확인한 뒤 등록해 주세요.</p>
        <small>같은 Apple 계정이나 Google 계정으로 패스키가 동기화된 내 다른 기기에서는 열 수 있어요.</small>
      </div>
      <label className="claim-agree">
        <input type="checkbox" checked={agreed} disabled={busy} onChange={event => onAgreed(event.target.checked)} />
        제가 받은 {asSubject} 맞아요
      </label>
      <button type="button" className="btn-primary" aria-describedby={noticeId}
        disabled={busy || disabled || !agreed} onClick={() => onClaim(preferred)}>
        {busy ? '기기에서 패스키 확인을 완료해 주세요…' : label(preferred)}
      </button>
      <button type="button" className="claim-other" disabled={busy || disabled || !agreed} onClick={() => onClaim(other)}>
        {otherLabel}
      </button>
    </div>
  );
}
