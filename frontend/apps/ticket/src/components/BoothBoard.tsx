import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AD_CATEGORY, gateAdEnd, gateAdPhoto, gateAdPhotoUrl, gateAdSave, gateAds, type BoothAd,
} from '../ticket/api';
import { shrinkPhoto } from '../ticket/photo';

/** The notice's photo: what will be saved with it, and how to show it here. */
interface Photo { id: string; url: string | null }

const UNTIL: Array<[string, string]> = [['60', '1시간 뒤'], ['120', '2시간 뒤'], ['180', '3시간 뒤'], ['day', '오늘 마감'], ['', '끝 없음']];
const STATE: Record<BoothAd['state'], string> = { LIVE: '게시 중', SCHEDULED: '예약', ENDED: '끝남', HIDDEN: '운영팀이 내림' };

function endsAt(choice: string): string | null {
  if (!choice) return null;
  const at = new Date();
  if (choice === 'day') at.setHours(23, 59, 0, 0);
  else at.setMinutes(at.getMinutes() + Number(choice));
  return at.toISOString();
}

function hm(value: string): string {
  const d = new Date(value);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * The booth's notices on the event's board, written at its own terminal: what it is
 * offering now and until when. Only this booth's posts are listed or changed here.
 */
export default function BoothBoard({ gateId, gateToken, booth, place, onClose }: {
  gateId: string; gateToken: string; booth: string; place: string | null; onClose: () => void;
}) {
  const [ads, setAds] = useState<BoothAd[]>([]);
  const [editing, setEditing] = useState<BoothAd | null>(null);
  const [category, setCategory] = useState<'SALE' | 'PROGRAM'>('SALE');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [until, setUntil] = useState('120');
  // Where the booth is, as a holder would look for it. The gate's zone to start with.
  const [where, setWhere] = useState(place ?? '');
  const [photo, setPhoto] = useState<Photo | null>(null);
  const [uploading, setUploading] = useState(false);
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try { setAds(await gateAds(gateId, gateToken)); }
    catch (err) { setError(err instanceof Error ? err.message : '광고판을 불러오지 못했어요.'); }
  }, [gateId, gateToken]);
  useEffect(() => { void load(); }, [load]);

  // A preview made on this page is let go when the page no longer shows it.
  useEffect(() => () => { if (photo?.url) URL.revokeObjectURL(photo.url); }, [photo?.url]);

  const reset = () => {
    setEditing(null); setCategory('SALE'); setTitle(''); setBody(''); setUntil('120');
    setWhere(place ?? ''); setPhoto(null);
  };

  async function pick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setUploading(true); setError('');
    try {
      const small = await shrinkPhoto(file);
      const id = await gateAdPhoto(gateId, gateToken, small);
      setPhoto({ id, url: URL.createObjectURL(small) });
    } catch (err) { setError(err instanceof Error ? err.message : '사진을 올리지 못했어요.'); }
    finally { setUploading(false); }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError(''); setMessage('');
    try {
      await gateAdSave(gateId, gateToken, {
        category, title, body, place: where.trim() || null, imageId: photo?.id ?? null,
        startsAt: editing ? editing.startsAt : undefined,
        endsAt: editing && until === 'keep' ? editing.endsAt : endsAt(until),
      }, editing?.id);
      setMessage(editing ? '고쳤어요.' : '광고판에 올렸어요. 입장한 관람객에게 바로 보여요.');
      reset();
      await load();
    } catch (err) { setError(err instanceof Error ? err.message : '올리지 못했어요.'); }
    finally { setBusy(false); }
  }

  async function end(ad: BoothAd) {
    setBusy(true); setError('');
    try { await gateAdEnd(gateId, gateToken, ad.id); setMessage('광고판에서 내렸어요.'); await load(); }
    catch (err) { setError(err instanceof Error ? err.message : '내리지 못했어요.'); }
    finally { setBusy(false); }
  }

  function edit(ad: BoothAd) {
    setEditing(ad);
    setCategory(ad.category === 'PROGRAM' ? 'PROGRAM' : 'SALE');
    setTitle(ad.title); setBody(ad.body); setUntil('keep'); setWhere(ad.place ?? '');
    setPhoto(ad.imageId ? { id: ad.imageId, url: null } : null);
    if (ad.imageId) {
      const id = ad.imageId;
      gateAdPhotoUrl(gateId, gateToken, ad.id)
        .then(url => setPhoto(current => current?.id === id ? { id, url } : (URL.revokeObjectURL(url), current)))
        .catch(() => { /* the photo stays attached; only its preview is missing */ });
    }
  }

  return (
    <div className="booth-board" role="dialog" aria-modal="true" aria-labelledby="booth-board-title">
      <header className="booth-board-head">
        <div>
          <span className="booth-board-kicker">광고판</span>
          <h2 id="booth-board-title">{booth}</h2>
        </div>
        <button type="button" className="btn-ghost" onClick={onClose}>스캔으로 돌아가기</button>
      </header>

      <div className="booth-board-body">
        <form className="booth-board-form" onSubmit={submit}>
          <h3>{editing ? '광고 고치기' : '새 광고 올리기'}</h3>
          <div className="booth-board-kinds" role="radiogroup" aria-label="분류">
            {(['SALE', 'PROGRAM'] as const).map(key => (
              <button key={key} type="button" role="radio" aria-checked={category === key}
                className={category === key ? 'on' : ''} onClick={() => setCategory(key)}>{AD_CATEGORY[key]}</button>
            ))}
          </div>
          <label>제목<input value={title} maxLength={80} required onChange={e => setTitle(e.target.value)}
            placeholder="예: 엽서 세트 2+1, 오후 3시까지만" /></label>
          <label>내용<textarea value={body} maxLength={1000} rows={4} required onChange={e => setBody(e.target.value)}
            placeholder="관람객이 부스에 와서 무엇을 하면 되는지 적어 주세요." /></label>
          <div className="booth-board-photo">
            <span className="booth-board-label">사진 <small>(선택)</small></span>
            {photo ? (
              <div className="booth-board-photo-on">
                {photo.url ? <img src={photo.url} alt="올릴 사진" /> : <span className="booth-board-photo-wait">사진 있음</span>}
                <button type="button" className="btn-ghost" onClick={() => setPhoto(null)}>사진 빼기</button>
              </div>
            ) : (
              <div className="booth-board-photo-pick">
                <button type="button" disabled={uploading} onClick={() => camera.current?.click()}>
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
                    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" /></svg>
                  {uploading ? '올리는 중…' : '지금 찍기'}</button>
                <button type="button" disabled={uploading} onClick={() => gallery.current?.click()}>사진 고르기</button>
              </div>
            )}
            <input ref={camera} type="file" accept="image/*" capture="environment" hidden onChange={e => void pick(e)} />
            <input ref={gallery} type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif" hidden
              onChange={e => void pick(e)} />
          </div>
          <label><span>위치 <small>(선택)</small></span><input value={where} maxLength={60} onChange={e => setWhere(e.target.value)}
            placeholder="예: 로비 왼쪽, 2층 A-12" /></label>
          <label>언제까지
            <select value={until} onChange={e => setUntil(e.target.value)}>
              {editing && <option value="keep">{editing.endsAt ? `지금처럼 (${hm(editing.endsAt)}까지)` : '지금처럼 (끝 없음)'}</option>}
              {UNTIL.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
          {message && <p className="booth-board-ok" role="status">{message}</p>}
          {error && <p className="gate-error" role="alert">{error}</p>}
          <div className="booth-board-actions">
            <button type="submit" className="booth-board-submit" disabled={busy || uploading || !title.trim() || !body.trim()}>
              {editing ? '저장' : '광고판에 올리기'}</button>
            {editing && <button type="button" className="btn-ghost" onClick={reset}>취소</button>}
          </div>
          <p className="booth-board-hint">입장한 관람객의 광고판에 바로 보여요. 운영팀이 내릴 수 있어요.</p>
        </form>

        <aside className="booth-board-side">
          <section>
            <h3>관람객에게 이렇게 보여요</h3>
            <div className="booth-board-preview">
              {photo?.url
                ? <img className="ad-thumb" src={photo.url} alt="" />
                : <span className={`ad-thumb ${category.toLowerCase()}`} aria-hidden="true">{booth.slice(0, 1)}</span>}
              <span>
                <span className={`ad-tag ${category.toLowerCase()}`}>{AD_CATEGORY[category]}</span>
                <b>{title || '제목'}</b>
                <small>{[booth, where.trim()].filter(Boolean).join(' · ')}</small>
              </span>
            </div>
          </section>
          <section>
            <h3>우리 부스 글</h3>
            {ads.length === 0 ? <p className="booth-board-hint">아직 올린 글이 없어요.</p> : (
              <ul className="booth-board-list">
                {ads.map(ad => (
                  <li key={ad.id}>
                    <span><em className={`state-${ad.state.toLowerCase()}`}>{STATE[ad.state]}</em>{ad.title}</span>
                    <small>조회 {ad.views} · {ad.endsAt ? `${hm(ad.endsAt)}까지` : '끝 없음'}</small>
                    {(ad.state === 'LIVE' || ad.state === 'SCHEDULED') && (
                      <span className="booth-board-row">
                        <button type="button" onClick={() => edit(ad)}>고치기</button>
                        <button type="button" disabled={busy} onClick={() => void end(ad)}>내리기</button>
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}
