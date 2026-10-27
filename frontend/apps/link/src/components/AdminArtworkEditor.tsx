import ArtworkPriceFields from './ArtworkPriceFields';
import { priceDraft } from '../artworkPrice';
import { useEffect, useRef, useState } from 'react';
import type { ArtworkRecord } from '../types';
import { mutate } from '@shared/auth';

const fields = [
  ['title', '작품명', 500], ['artist', '작가', 300], ['year', '제작연도', 100],
  ['medium', '재료', 10000], ['width', '가로', 100], ['height', '세로', 100],
  ['depth', '깊이', 100], ['unit', '단위', 20],
  ['description', '설명', 10000],
] as const;

export default function AdminArtworkEditor({ artwork, onClose, onSaved }: {
  artwork: ArtworkRecord & { sourceType?: string; sourceRef?: string | null }; onClose: () => void; onSaved: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [values, setValues] = useState(() => Object.fromEntries(fields.map(([key]) => [key, artwork[key] || ''])));
  const [price, setPrice] = useState(() => priceDraft(artwork));
  const [sourceRef, setSourceRef] = useState(artwork.sourceRef || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { dialog.current?.showModal(); }, []);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError('');
    try {
      const response = await mutate(`/api/admin/artworks/${artwork.id}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...values, ...price, ...(sourceRef !== (artwork.sourceRef || '') ? { sourceRef } : {}) }),
      });
      if (!response.ok) throw new Error(response.status === 403 ? '수정 권한이 없습니다.'
        : response.status === 404 ? '작품이 삭제되었어요. 목록을 새로고침해 주세요.' : '저장하지 못했어요. 입력 내용을 확인하고 다시 시도해 주세요.');
      onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : '저장하지 못했어요.'); }
    finally { setBusy(false); }
  }
  return <dialog ref={dialog} className="admin-artwork-editor" aria-labelledby="artwork-edit-title"
    onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <form onSubmit={event => void save(event)}>
      <h3 id="artwork-edit-title">작품 수정</h3>
      <p className="hint-text">원본 컨텐츠와 작품 목록에 반영됩니다. 이미 발송한 수신자별 작품 내용은 유지됩니다.</p>
      {artwork.image && <img className="admin-artwork-edit-preview" src={artwork.image} alt={artwork.title} referrerPolicy="no-referrer" />}
      <fieldset disabled={busy} className="admin-artwork-edit-fields">
        {fields.map(([key, label, maxLength]) => <div className="field" key={key}>
          <label htmlFor={`edit-artwork-${key}`}>{label}</label>
          {key === 'description' ? <textarea id={`edit-artwork-${key}`} value={values[key]} maxLength={maxLength}
            onChange={event => setValues(current => ({ ...current, [key]: event.target.value }))} />
            : <input id={`edit-artwork-${key}`} value={values[key]} maxLength={maxLength} required={key === 'title'}
              autoFocus={key === 'title'} onChange={event => setValues(current => ({ ...current, [key]: event.target.value }))} />}
        </div>)}
        {artwork.sourceType === 'URL' && <div className="field artwork-source-field">
          <label htmlFor="edit-artwork-source">원본 웹페이지 주소</label>
          <input id="edit-artwork-source" type="url" maxLength={500}
            value={/^[0-9a-f]{64}$/.test(sourceRef) ? '' : sourceRef} placeholder="https://"
            onChange={event => setSourceRef(event.target.value)} />
          <small>같은 컨텐츠에 속한 작품의 출처에 함께 반영됩니다.</small>
        </div>}
        <ArtworkPriceFields id="edit-artwork-price" value={price} onChange={setPrice} />
      </fieldset>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="modal-actions">
        <button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>취소</button>
        <button type="submit" className="btn-primary" disabled={busy}>{busy ? '저장 중…' : '저장'}</button>
      </div>
    </form>
  </dialog>;
}
