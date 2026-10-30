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
  /** A booth's notice waits for the organiser; only PUBLISHED ones reach holders. */
  review: Review; rejectReason: string | null; reviewedAt: string | null; updatedAt: string;
}

type Review = 'PENDING' | 'PUBLISHED' | 'REJECTED';
type Filter = Review | 'ALL';

/** The photo the form will save with the notice, and how to show it. */
interface Photo { id: string; url: string; local: boolean }

const CATEGORY: Record<string, string> = { SALE: '할인·혜택', PROGRAM: '체험·프로그램', NOTICE: '운영 공지' };
const STATE: Record<AdRow['state'], [string, string]> = {
  LIVE: ['게시 중', 'ok'], SCHEDULED: ['예약', 'in'], ENDED: ['끝남', 'wait'], HIDDEN: ['내림', 'off'],
};
const FILTERS: Array<[Filter, string]> = [['ALL', '전체'], ['PENDING', '승인 대기'], ['PUBLISHED', '게시됨'], ['REJECTED', '반려']];

/** A row's pill: the organiser's answer while one is owed or was no, otherwise where it stands in time. */
function pill(ad: AdRow): [string, string] {
  if (ad.review === 'PENDING') return ['승인 대기', 'warn'];
  if (ad.review === 'REJECTED') return ['반려', 'off'];
  return STATE[ad.state];
}

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
  const [filter, setFilter] = useState<Filter>('ALL');
  // The pending notice whose 반려 is being written, and the reason so far.
  const [rejecting, setRejecting] = useState<number | null>(null);
  const [reason, setReason] = useState('');
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

  const review = (ad: AdRow, decision: 'APPROVE' | 'REJECT', why?: string) => act(
    () => send(`${base}/${ad.id}/review`, 'POST', { decision, reason: why?.trim() || null }),
    decision === 'APPROVE' ? `'${ad.title}' 글을 승인했어요. 관람객 알림판에 게시돼요.` : `'${ad.title}' 글을 반려했어요.`,
  ).then(ok => { if (ok) { setRejecting(null); setReason(''); } });

  if (!ads) return <p className="console-none">알림판을 불러오고 있어요.</p>;
  const draft = editing === 'new' ? null : editing;
  // Oldest request first: the booth that has waited longest is read first.
  const pending = ads.filter(ad => ad.review === 'PENDING')
    .sort((a, b) => a.updatedAt.localeCompare(b.updatedAt));
  const count = (key: Filter) => (key === 'ALL' ? ads.length : ads.filter(ad => ad.review === key).length);
  const shown = filter === 'ALL' ? ads : ads.filter(ad => ad.review === filter);

  return (
    <div className="console-stack">
      <div className="console-card-head">
        <p className="console-summary">게시 중 {ads.filter(ad => ad.review === 'PUBLISHED' && ad.state === 'LIVE').length} · 승인 대기 {pending.length} · 전체 {ads.length}</p>
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

      {pending.length > 0 && (
        <section className="console-card ad-review" aria-labelledby="ad-review-title">
          <h3 id="ad-review-title">승인 대기 <span className="count">{pending.length}</span></h3>
          <p className="console-summary">부스가 올리거나 고친 글이에요. 승인해야 관람객 알림판에 보여요.</p>
          <ul className="ad-review-list">
            {pending.map(ad => (
              <li key={ad.id}>
                {ad.imageId && <img src={photoOf(ad)} alt="" loading="lazy" />}
                <div className="ad-review-main">
                  <span className="ad-tags">
                    <span className={`ad-tag ${ad.category.toLowerCase()}`}>{CATEGORY[ad.category]}</span>
                    <b>{ad.booth}</b>
                  </span>
                  <strong>{ad.title}</strong>
                  <p>{ad.body}</p>
                  <small className="muted">
                    {[ad.place, `${shortTime(ad.startsAt)} – ${ad.endsAt ? shortTime(ad.endsAt) : '계속'}`].filter(Boolean).join(' · ')}
                    {' · '}요청 {shortTime(ad.updatedAt)}
                  </small>
                  {rejecting === ad.id ? (
                    <form className="ad-review-reject" onSubmit={e => { e.preventDefault(); void review(ad, 'REJECT', reason); }}>
                      <label className="visually-hidden" htmlFor={`reject-${ad.id}`}>반려 사유</label>
                      <input id={`reject-${ad.id}`} maxLength={200} value={reason} autoFocus
                        onChange={e => setReason(e.target.value)} placeholder="반려 사유 (선택) · 부스에 보여요" />
                      <button className="btn-tiny btn-tiny-danger" type="submit">반려하기</button>
                      <button className="btn-tiny" type="button" onClick={() => { setRejecting(null); setReason(''); }}>취소</button>
                    </form>
                  ) : (
                    <span className="row-actions ad-review-actions">
                      <button className="btn-tiny btn-tiny-primary" type="button" onClick={() => void review(ad, 'APPROVE')}>승인</button>
                      <button className="btn-tiny" type="button" onClick={() => { setRejecting(ad.id); setReason(''); }}>반려</button>
                      <button className="btn-tiny" type="button" onClick={() => setEditing(ad)}>고쳐서 게시</button>
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="chip-row" role="group" aria-label="승인 상태">
        {FILTERS.map(([key, label]) => (
          <button key={key} type="button" className={filter === key ? 'on' : ''} aria-pressed={filter === key}
            onClick={() => setFilter(key)}>{label} <small>{count(key).toLocaleString()}</small></button>
        ))}
      </div>

      <div className="console-table-box">
        <table className="console-table">
          <thead><tr><th>상태</th><th>제목</th><th>올린 곳</th><th>분류</th><th>게시 기간</th><th className="num">조회</th><th aria-label="작업" /></tr></thead>
          <tbody>
            {shown.map(ad => (
              <tr key={ad.id} className={ad.review === 'REJECTED' || ad.state === 'ENDED' || ad.state === 'HIDDEN' ? 'is-off' : ''}>
                <td><span className={`pill pill-${pill(ad)[1]}`}>{pill(ad)[0]}</span></td>
                <td className="wrap">
                  <span className="ad-row-title">
                    {ad.imageId && <img src={photoOf(ad)} alt="" loading="lazy" />}
                    <span><b>{ad.title}</b>{ad.pinned && <small className="cell-note">맨 위 고정</small>}
                      {ad.review === 'REJECTED' && ad.rejectReason && <small className="cell-note">반려 사유: {ad.rejectReason}</small>}</span>
                  </span>
                </td>
                <td>{ad.booth}</td>
                <td className="muted">{CATEGORY[ad.category]}</td>
                <td className="muted wrap">{shortTime(ad.startsAt)} – {ad.endsAt ? shortTime(ad.endsAt) : '종일'}</td>
                <td className="num">{ad.views}</td>
                <td className="row-actions">
                  {ad.review !== 'PUBLISHED' && (
                    <button className="btn-tiny" type="button" onClick={() => void review(ad, 'APPROVE')}>승인</button>
                  )}
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
            {shown.length === 0 && (
              <tr><td colSpan={7} className="console-none">{ads.length === 0
                ? '아직 올라온 글이 없어요. 부스는 부스 단말이나 부스 관리자 화면의 알림판 탭에서 올릴 수 있어요.'
                : '이 상태의 글이 없어요.'}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="console-foot">부스는 자기 부스 글만 올리고 고칠 수 있고, 올리거나 고친 글은 여기서 승인해야 게시돼요. 운영 글과 운영팀이 고친 글은 바로 게시되고, 맨 위에 고정할 수 있어요.</p>
    </div>
  );
}
