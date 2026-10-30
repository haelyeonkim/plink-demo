import { useCallback, useEffect, useRef, useState } from 'react';
import { mutate } from '@shared/auth';
import { shrinkPhoto } from '../ticket/photo';
import { openLive } from '../ticket/live';
import { useCoalesced } from '../ticket/coalesce';
import { localInput, read, shortTime, type BoothRow } from '../console/model';

interface AdRow {
  id: number; category: 'SALE' | 'PROGRAM' | 'NOTICE'; title: string; body: string; booth: string;
  boothId: number | null; place: string | null; startsAt: string; endsAt: string | null; pinned: boolean;
  hidden: boolean; views: number; state: 'LIVE' | 'SCHEDULED' | 'ENDED' | 'HIDDEN'; imageId: string | null;
}

/** The photo the form will save with the notice, and how to show it. */
interface Photo { id: string; url: string; local: boolean }

const CATEGORY: Record<string, string> = { SALE: '할인·혜택', PROGRAM: '체험·프로그램', NOTICE: '운영 공지' };
const STATE: Record<AdRow['state'], [string, string]> = {
  LIVE: ['게시 중', 'ok'], SCHEDULED: ['예약', 'in'], ENDED: ['끝남', 'wait'], HIDDEN: ['내림', 'off'],
};

/** The organiser's view of the event's board: every booth's notices, and their own. */
export default function ConsoleAds({ sessionId, booths, onNotice, onError }: {
  /** Ignored: the board listens for ADS itself, rather than reloading on every chat line. */
  tick?: number;
  sessionId: number; booths: BoothRow[];
  onNotice: (text: string) => void; onError: (text: string) => void;
}) {
  const [ads, setAds] = useState<AdRow[] | null>(null);
  const [editing, setEditing] = useState<AdRow | 'new' | null>(null);
  const [photo, setPhoto] = useState<Photo | null>(null);
  const [uploading, setUploading] = useState(false);
  const base = `/api/admin/sessions/${sessionId}/ads`;
  const photoOf = (ad: AdRow) => `${base}/${ad.id}/image?v=${ad.imageId!.slice(0, 8)}`;

  // Opening the form shows what the notice carries now; a photo picked here is let go after.
  useEffect(() => {
    setPhoto(editing && editing !== 'new' && editing.imageId
      ? { id: editing.imageId, url: photoOf(editing), local: false } : null);
  }, [editing]);
  useEffect(() => () => { if (photo?.local) URL.revokeObjectURL(photo.url); }, [photo]);

  async function pick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setUploading(true);
    try {
      const small = await shrinkPhoto(file);
      const form = new FormData();
      form.append('file', small, 'photo.jpg');
      const { imageId } = await read(await mutate(`${base}/images`, { method: 'POST', body: form })) as { imageId: string };
      setPhoto({ id: imageId, url: URL.createObjectURL(small), local: true });
    } catch (err) { onError(err instanceof Error ? err.message : '사진을 올리지 못했어요.'); }
    finally { setUploading(false); }
  }

  // Only the latest read may fill the list: a slow answer to an older one is dropped.
  const latest = useRef(0);
  const load = useCallback(async () => {
    const seq = ++latest.current;
    try {
      const next = await read(await fetch(base)) as AdRow[];
      if (seq === latest.current) setAds(next);
    } catch (err) {
      if (seq === latest.current) onError(err instanceof Error ? err.message : '알림판을 불러오지 못했어요.');
    }
  }, [base, onError]);
  useEffect(() => { void load(); }, [load]);
  const nudge = useCoalesced(useCallback(() => { void load(); }, [load]), 600);
  useEffect(() => {
    const live = openLive(`/ws/admin/sessions/${sessionId}`, message => { if (message.type === 'ADS') nudge(); });
    return () => live.close();
  }, [sessionId, nudge]);

  const act = async (work: () => Promise<unknown>, done: string) => {
    try { await work(); onNotice(done); await load(); return true; }
    catch (err) { onError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); return false; }
  };
  const send = (url: string, method: string, body?: unknown) => mutate(url, {
    method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
  }).then(read);

  const save = (form: HTMLFormElement) => {
    const data = new FormData(form);
    const at = (name: string) => { const v = String(data.get(name) || ''); return v ? new Date(v).toISOString() : null; };
    const body = {
      category: data.get('category'), title: data.get('title'), body: data.get('body'), place: data.get('place'),
      boothId: data.get('boothId') ? Number(data.get('boothId')) : null,
      startsAt: at('startsAt'), endsAt: at('endsAt'), pinned: data.get('pinned') === 'on',
      imageId: photo?.id ?? null,
    };
    const current = editing;
    void act(() => current === 'new' || current === null ? send(base, 'POST', body) : send(`${base}/${current.id}`, 'PUT', body),
      current === 'new' ? '알림판에 올렸어요.' : '글을 고쳤어요.').then(ok => { if (ok) setEditing(null); });
  };

  if (!ads) return <p className="console-none">알림판을 불러오고 있어요.</p>;
  const draft = editing === 'new' ? null : editing;

  return (
    <div className="console-stack">
      <div className="console-card-head">
        <p className="console-summary">게시 중 {ads.filter(ad => ad.state === 'LIVE').length} · 예약 {ads.filter(ad => ad.state === 'SCHEDULED').length} · 전체 {ads.length}</p>
        {editing === null && <button className="btn-primary" type="button" onClick={() => setEditing('new')}>운영 글 쓰기</button>}
      </div>

      {editing !== null && (
        <form className="console-card ad-form" key={draft?.id ?? 'new'} onSubmit={e => { e.preventDefault(); save(e.currentTarget); }}>
          <h3>{draft ? '글 고치기' : '운영 글 쓰기'}</h3>
          <div className="console-fields wide">
            <label>분류
              <select name="category" defaultValue={draft?.category ?? 'NOTICE'}>
                {Object.entries(CATEGORY).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
              </select></label>
            <label>올린 곳
              <select name="boothId" defaultValue={draft?.boothId ?? ''}>
                <option value="">운영팀</option>
                {booths.map(booth => <option key={booth.boothId} value={booth.boothId}>{booth.name}</option>)}
              </select></label>
            <label className="span">제목<input name="title" required maxLength={80} defaultValue={draft?.title ?? ''} /></label>
            <label className="span">내용<textarea name="body" required maxLength={1000} rows={4} defaultValue={draft?.body ?? ''} /></label>
            <div className="span ad-form-photo">
              <span className="ad-form-label">사진 (선택)</span>
              {photo ? (
                <span className="ad-form-photo-on">
                  <img src={photo.url} alt="올릴 사진" />
                  <button className="btn-tiny" type="button" onClick={() => setPhoto(null)}>사진 빼기</button>
                </span>
              ) : (
                <label className="btn-secondary ad-form-photo-pick">
                  {uploading ? '올리는 중…' : '사진 고르기'}
                  <input type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif" hidden
                    disabled={uploading} onChange={e => void pick(e)} />
                </label>
              )}
            </div>
            <label>위치 (선택)<input name="place" maxLength={60} defaultValue={draft?.place ?? ''} placeholder="예: 로비 왼쪽" /></label>
            <label className="console-switch inline"><input type="checkbox" name="pinned" defaultChecked={draft?.pinned ?? false} /> 맨 위에 고정</label>
            <label>시작<input name="startsAt" type="datetime-local" defaultValue={draft ? localInput(draft.startsAt) : ''} /></label>
            <label>끝 (비우면 종일)<input name="endsAt" type="datetime-local" defaultValue={draft ? localInput(draft.endsAt) : ''} /></label>
          </div>
          <div className="button-row">
            <button className="btn-primary" type="submit" disabled={uploading}>{draft ? '저장' : '올리기'}</button>
            <button className="btn-secondary" type="button" onClick={() => setEditing(null)}>취소</button>
          </div>
        </form>
      )}

      <div className="console-table-box">
        <table className="console-table">
          <thead><tr><th>상태</th><th>제목</th><th>올린 곳</th><th>분류</th><th>게시 기간</th><th className="num">조회</th><th aria-label="작업" /></tr></thead>
          <tbody>
            {ads.map(ad => (
              <tr key={ad.id} className={ad.state === 'ENDED' || ad.state === 'HIDDEN' ? 'is-off' : ''}>
                <td><span className={`pill pill-${STATE[ad.state][1]}`}>{STATE[ad.state][0]}</span></td>
                <td className="wrap">
                  <span className="ad-row-title">
                    {ad.imageId && <img src={photoOf(ad)} alt="" loading="lazy" />}
                    <span><b>{ad.title}</b>{ad.pinned && <small className="cell-note">맨 위 고정</small>}</span>
                  </span>
                </td>
                <td>{ad.booth}</td>
                <td className="muted">{CATEGORY[ad.category]}</td>
                <td className="muted wrap">{shortTime(ad.startsAt)} – {ad.endsAt ? shortTime(ad.endsAt) : '종일'}</td>
                <td className="num">{ad.views}</td>
                <td className="row-actions">
                  <button className="btn-tiny" type="button" onClick={() => setEditing(ad)}>수정</button>
                  <button className="btn-tiny" type="button" onClick={() => void act(
                    () => send(`${base}/${ad.id}/hidden`, 'POST', { hidden: !ad.hidden }),
                    ad.hidden ? '다시 게시했어요.' : '알림판에서 내렸어요.')}>{ad.hidden ? '다시 게시' : '내리기'}</button>
                  <button className="btn-tiny btn-tiny-danger" type="button" onClick={() => {
                    if (window.confirm(`'${ad.title}' 글을 지울까요? 되돌릴 수 없어요.`)) {
                      void act(() => send(`${base}/${ad.id}`, 'DELETE'), '글을 지웠어요.');
                    }
                  }}>삭제</button>
                </td>
              </tr>
            ))}
            {ads.length === 0 && <tr><td colSpan={7} className="console-none">아직 올라온 글이 없어요. 부스는 부스 단말의 알림판 모드에서 올릴 수 있어요.</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="console-foot">부스는 부스 단말에서 자기 부스 글만 올리고 고칠 수 있어요. 운영 글은 맨 위에 고정할 수 있어요.</p>
    </div>
  );
}
