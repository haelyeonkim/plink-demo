import { useState } from 'react';

/**
 * The personal link, shown once.
 *
 * Only a keyed hash of the token is stored, so this is the single moment it can be
 * handed over without minting a new one. Copy and share are offered here rather than
 * a "show again" button that could not be honoured later.
 */
export default function IssuedLink({ url, phone, note }: { url: string; phone?: string | null; note?: string }) {
  const [copied, setCopied] = useState(false);

  const smsHref = `sms:${phone ?? ''}?body=${encodeURIComponent(`입장권 링크입니다.\n${url}`)}`;
  const canShare = typeof navigator !== 'undefined' && !!navigator.share;

  async function share() {
    try {
      await navigator.share({ title: '입장권', text: '입장권 링크입니다.', url });
    } catch { /* the person closed the sheet */ }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard blocked; the text is selectable above */ }
  }

  return (
    <div className="issued-link">
      {note && <p className="hint-text">{note}</p>}
      <code title={url}>{url}</code>
      <div className="share-row">
        <button type="button" onClick={copy}>{copied ? '복사됨' : '링크 복사'}</button>
        <a href={smsHref}>문자로 보내기</a>
        {canShare && <button type="button" onClick={share}>공유 (카카오톡 등)</button>}
      </div>
    </div>
  );
}
