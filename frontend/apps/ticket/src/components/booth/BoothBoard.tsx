import { useCallback, useEffect, useRef, useState } from 'react';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import {
  boardPhotoUrl, deleteBoardPost, endBoardPost, fetchBoard, saveBoardPost, uploadBoardPhoto, type BoardPost,
} from '../../booth/api';
import { AD_CATEGORY } from '../../ticket/api';
import { shrinkPhoto } from '../../ticket/photo';
import { localInput, shortTime } from '../../console/model';
import type { BoothTabProps } from './shared';

/** The notice's photo: what will be saved with it, and how to show it here. */
interface Photo { id: string; url: string | null }

/** Where a notice stands, as the booth reads it: the organiser's answer first, then time. */
function standing(post: BoardPost): [string, string] {
  if (post.review === 'PENDING') return ['승인 대기', 'warn'];
  if (post.review === 'REJECTED') return ['반려', 'off'];
  if (post.hidden) return ['운영팀이 내림', 'out'];
  if (post.state === 'SCHEDULED') return ['게시 예약', 'in'];
  if (post.state === 'ENDED') return ['끝남', 'wait'];
  return ['게시됨', 'ok'];
}

/**
 * The booth's notices on the event's board. Whatever the booth writes or changes goes to
 * the organiser first and reaches holders once approved, so the list says where each one
 * stands, and why when it was turned back.
 */
export default function BoothBoard({ client, dashboard, fail, notify }: BoothTabProps) {
  const [posts, setPosts] = useState<BoardPost[] | null>(null);
  const [editing, setEditing] = useState<BoardPost | null>(null);
  const [category, setCategory] = useState<'SALE' | 'PROGRAM'>('SALE');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [place, setPlace] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [photo, setPhoto] = useState<Photo | null>(null);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<BoardPost | null>(null);
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    try { setPosts(await fetchBoard(client)); }
    catch (err) { fail(err); }
  }, [client, fail]);
  useEffect(() => { void load(); }, [load]);

  // A preview made on this page is let go when the page no longer shows it.
  useEffect(() => () => { if (photo?.url) URL.revokeObjectURL(photo.url); }, [photo?.url]);

  function edit(post: BoardPost | null) {
    setEditing(post);
    setCategory(post?.category === 'PROGRAM' ? 'PROGRAM' : 'SALE');
    setTitle(post?.title ?? '');
    setBody(post?.body ?? '');
    setPlace(post?.place ?? '');
    setStartsAt(post ? localInput(post.startsAt) : '');
    setEndsAt(post ? localInput(post.endsAt) : '');
    setPhoto(post?.imageId ? { id: post.imageId, url: null } : null);
    if (post?.imageId) {
      const id = post.imageId;
      boardPhotoUrl(client, post.id)
        .then(url => setPhoto(current => current?.id === id ? { id, url } : (URL.revokeObjectURL(url), current)))
        .catch(() => { /* the photo stays attached; only its preview is missing */ });
    }
  }

  async function pick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setUploading(true);
    try {
      const small = await shrinkPhoto(file);
      const id = await uploadBoardPhoto(client, small);
      setPhoto({ id, url: URL.createObjectURL(small) });
    } catch (err) { fail(err); }
    finally { setUploading(false); }
  }

  async function run(work: () => Promise<unknown>, done: string): Promise<boolean> {
    setBusy(true);
    try { await work(); notify(done); await load(); return true; }
    catch (err) { fail(err); return false; }
    finally { setBusy(false); }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const at = (value: string) => (value ? new Date(value).toISOString() : null);
    const ok = await run(() => saveBoardPost(client, {
      category, title: title.trim(), body: body.trim(), place: place.trim() || null,
      startsAt: at(startsAt), endsAt: at(endsAt), imageId: photo?.id ?? null,
    }, editing?.id), editing ? '고쳤어요. 주최 측 승인 후 다시 게시돼요.' : '주최 측 승인 후 게시돼요.');
    if (ok) edit(null);
  }

  return (
    <div className="bm-stack">
      <section className="console-card">
        <h3>{editing ? '글 고치기' : '새 글 올리기'}</h3>
        <form className="bm-form" onSubmit={e => void submit(e)}>
          <div className="bm-mode" role="radiogroup" aria-label="분류">
            {(['SALE', 'PROGRAM'] as const).map(key => (
              <button key={key} type="button" role="radio" aria-checked={category === key}
                className={category === key ? 'on' : ''} onClick={() => setCategory(key)}>{AD_CATEGORY[key]}</button>
            ))}
          </div>
          <div className="field">
            <label htmlFor="bb-title">제목</label>
            <input id="bb-title" required maxLength={80} value={title} onChange={e => setTitle(e.target.value)}
              placeholder="예: 엽서 세트 2+1, 오후 3시까지만" />
          </div>
          <div className="field">
            <label htmlFor="bb-body">내용</label>
            <textarea id="bb-body" required maxLength={1000} rows={4} value={body} onChange={e => setBody(e.target.value)}
              placeholder="관람객이 부스에 와서 무엇을 하면 되는지 적어 주세요." />
          </div>
          <div className="field">
            <span className="bb-label">사진 <span className="label-note">(선택)</span></span>
            {photo ? (
              <span className="bb-photo-on">
                {photo.url ? <img src={photo.url} alt="올릴 사진" /> : <span className="bb-photo-wait">사진 있음</span>}
                <button type="button" className="btn-tiny" onClick={() => setPhoto(null)}>사진 빼기</button>
              </span>
            ) : (
              <span className="bb-photo-pick">
                <button type="button" className="btn-secondary" disabled={uploading} onClick={() => camera.current?.click()}>
                  {uploading ? '올리는 중…' : '지금 찍기'}</button>
                <button type="button" className="btn-secondary" disabled={uploading} onClick={() => gallery.current?.click()}>
                  사진 고르기</button>
              </span>
            )}
            <input ref={camera} type="file" accept="image/*" capture="environment" hidden onChange={e => void pick(e)} />
            <input ref={gallery} type="file" accept="image/jpeg,image/png,image/webp,image/heic,image/heif" hidden
              onChange={e => void pick(e)} />
          </div>
          <div className="field">
            <label htmlFor="bb-place">위치 <span className="label-note">(선택)</span></label>
            <input id="bb-place" maxLength={60} value={place} onChange={e => setPlace(e.target.value)}
              placeholder={dashboard.booth.note ?? '예: 로비 왼쪽, 2층 A-12'} />
          </div>
          <div className="bb-times">
            <div className="field">
              <label htmlFor="bb-start">시작 <span className="label-note">(비우면 승인 즉시)</span></label>
              <input id="bb-start" type="datetime-local" value={startsAt} onChange={e => setStartsAt(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="bb-end">끝 <span className="label-note">(비우면 계속)</span></label>
              <input id="bb-end" type="datetime-local" value={endsAt} onChange={e => setEndsAt(e.target.value)} />
            </div>
          </div>
          <div className="bm-actions">
            <button className="btn-primary" type="submit" disabled={busy || uploading || !title.trim() || !body.trim()}>
              {editing ? '고쳐서 다시 요청' : '승인 요청'}</button>
            {editing && <button className="btn-secondary" type="button" onClick={() => edit(null)}>취소</button>}
          </div>
          <p className="hint-text">주최 측 승인 후 입장한 관람객의 알림판에 게시돼요. 게시된 글을 고치면 다시 승인을 받아야 해요.</p>
        </form>
      </section>

      <section className="console-card">
        <h3>우리 부스 글{posts ? ` ${posts.length}` : ''}</h3>
        {posts === null ? <p className="console-none">불러오는 중…</p>
          : posts.length === 0 ? <p className="console-none">아직 올린 글이 없어요.</p> : (
          <ul className="bm-list">
            {posts.map(post => {
              const [label, tone] = standing(post);
              const live = post.review !== 'PUBLISHED' || post.state === 'LIVE' || post.state === 'SCHEDULED';
              return (
                <li key={post.id} className={live ? '' : 'is-off'}>
                  <span className="bm-row-main">
                    <span className={`pill pill-${tone}`}>{label}</span>
                    <b>{post.title}</b>
                  </span>
                  {post.review === 'REJECTED' && (
                    <small className="bb-reason">{post.rejectReason ? `반려 사유: ${post.rejectReason}` : '사유 없이 반려됐어요.'}</small>
                  )}
                  <small>
                    {AD_CATEGORY[post.category]} · {shortTime(post.startsAt)} – {post.endsAt ? shortTime(post.endsAt) : '계속'}
                    {post.review === 'PUBLISHED' ? ` · 조회 ${post.views.toLocaleString()}` : ''}
                  </small>
                  <span className="bm-row-actions">
                    <button type="button" className="btn-tiny" disabled={busy} onClick={() => edit(post)}>고치기</button>
                    {post.review === 'PUBLISHED' && (post.state === 'LIVE' || post.state === 'SCHEDULED') && (
                      <button type="button" className="btn-tiny" disabled={busy}
                        onClick={() => void run(() => endBoardPost(client, post.id), '알림판에서 내렸어요.')}>내리기</button>
                    )}
                    <button type="button" className="btn-tiny btn-tiny-danger" disabled={busy}
                      onClick={() => setRemoving(post)}>삭제</button>
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <ConfirmDialog
        open={removing != null}
        title={`'${removing?.title}' 글을 지울까요?`}
        message="알림판과 우리 부스 글 목록에서 모두 사라지고, 되돌릴 수 없어요."
        busy={busy}
        onCancel={() => setRemoving(null)}
        onConfirm={() => {
          const post = removing!;
          void run(() => deleteBoardPost(client, post.id), '글을 지웠어요.')
            .then(() => { setRemoving(null); if (editing?.id === post.id) edit(null); });
        }}
      />
    </div>
  );
}
