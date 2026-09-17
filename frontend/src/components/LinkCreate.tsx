import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createArtworkDelivery, createLink, fetchArtworks, fetchContents } from '../api';
import { mutate } from '../auth';
import { parseTable } from '../ticket/csv';
import type { ArtworkRecord, ContentSummary } from '../types';

/** "yyyy-MM-ddTHH:mm" in the operator's own zone, which is what the input speaks. */
function localInput(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const DEFAULT_HOURS = 72;

/**
 * Registering a link is its own task, so it gets its own page - the same shape as adding
 * an event on the ticket side. Recipients are issued afterwards, in the console.
 */
export default function LinkCreate() {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expiresAt, setExpiresAt] = useState(
    localInput(new Date(Date.now() + DEFAULT_HOURS * 3600000)));
  const [recipients, setRecipients] = useState('');
  const [notify, setNotify] = useState(false);
  // A link opens one of two things: an address elsewhere, or something written here.
  const [target, setTarget] = useState<'url' | 'content' | 'artworks'>('url');
  const [contentId, setContentId] = useState('');
  const [contents, setContents] = useState<ContentSummary[]>([]);
  const [artworks, setArtworks] = useState<ArtworkRecord[]>([]);
  const [selectedWorks, setSelectedWorks] = useState<number[]>([]);
  const [recipientEmail, setRecipientEmail] = useState('');
  const [recipientLabel, setRecipientLabel] = useState('');

  useEffect(() => { fetchContents().then(setContents).catch(() => setContents([])); }, []);
  useEffect(() => { fetchArtworks().then(setArtworks).catch(() => setArtworks([])); }, []);

  // One address per line, or pasted from a sheet. Empty means the link is created and
  // the addresses are issued later in the console.
  const addresses = parseTable(recipients).rows
    .map(cells => (cells[0] ?? '').trim())
    .filter(Boolean);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    const data = new FormData(event.currentTarget);
    try {
      if (target === 'artworks') {
        const delivery = await createArtworkDelivery({
          artworkIds: selectedWorks,
          email: recipientEmail,
          label: recipientLabel || undefined,
          title: String(data.get('title') || '') || undefined,
          password: String(data.get('password') || '') || undefined,
          expiresAt: expiresAt ? `${expiresAt.replace('T', ' ')}:00` : undefined,
          maxViews: Number(data.get('maxViews') || 0) || undefined,
          notify,
        });
        navigate(`/links?tab=links&link=${delivery.id}`, { replace: true });
        return;
      }
      if (target === 'content' && !contentId) throw new Error('연결할 컨텐츠를 선택해 주세요.');
      const link = await createLink({
        originalUrl: target === 'url' ? String(data.get('originalUrl')) : undefined,
        contentId: target === 'content' ? Number(contentId) : undefined,
        title: String(data.get('title') || '') || undefined,
        password: String(data.get('password') || '') || undefined,
        // Sent as the operator picked it: converting to UTC here moved every deadline.
        expiresAt: expiresAt ? `${expiresAt.replace('T', ' ')}:00` : undefined,
        maxViews: Number(data.get('maxViews') || 0) || undefined,
      });
      if (addresses.length > 0) {
        const response = await mutate(`/api/links/${link.id}/recipients/bulk`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ notify, rows: addresses.map(email => ({ email })) }),
        });
        const result = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(result.error || '수신자를 발급하지 못했어요.');
        navigate(`/links?tab=links&link=${link.id}`, { replace: true });
        return;
      }
      navigate(`/links?tab=issue&link=${link.id}`, { replace: true });
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : '링크를 만들지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="page-section">
      <Link className="btn-back" to="/links">← 링크 관리</Link>
      <p className="eyebrow"><span></span> NEW LINK</p>
      <h2>링크 추가</h2>

      <form className="create-form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="link-target">무엇을 열까요</label>
          <select id="link-target" value={target}
            onChange={event => setTarget(event.target.value as 'url' | 'content' | 'artworks')}>
            <option value="url">외부 주소 · 이미 올려 둔 문서나 페이지</option>
            <option value="content">여기서 만든 컨텐츠</option>
            <option value="artworks">작품 선택 · 수신자별 맞춤 작품 목록</option>
          </select>
        </div>
        {target === 'url' ? (
          <div className="field">
            <label htmlFor="originalUrl">원본 주소</label>
            <input id="originalUrl" name="originalUrl" type="url" required
              placeholder="https://example.com/my-document" />
          </div>
        ) : target === 'content' ? (
          <div className="field">
            <label htmlFor="link-content">컨텐츠</label>
            <select id="link-content" value={contentId} required
              onChange={event => setContentId(event.target.value)}>
              <option value="">컨텐츠를 선택하세요</option>
              {contents.map(content => (
                <option key={content.id} value={content.id}>{content.title}</option>
              ))}
            </select>
            <p className="hint-text">
              {contents.length === 0
                ? '아직 만든 컨텐츠가 없어요. '
                : '여기서 만든 컨텐츠는 주소가 따로 없고, 수신자의 패스키로만 열립니다. '}
              <Link to="/contents/new">컨텐츠 만들기 →</Link>
            </p>
          </div>
        ) : <div className="field">
          <label>전달할 작품 ({selectedWorks.length}점 선택)</label>
          {artworks.length === 0 ? <p className="hint-text">
            저장된 작품이 없어요. <Link to="/contents/new">URL 또는 PDF에서 작품 가져오기 →</Link>
          </p> : <div className="artwork-picker">
            {artworks.map(artwork => {
              const checked = selectedWorks.includes(artwork.id);
              return <label key={artwork.id} className={`artwork-pick-card${checked ? ' selected' : ''}`}>
                <input type="checkbox" checked={checked} onChange={() => setSelectedWorks(current =>
                  checked ? current.filter(id => id !== artwork.id) : [...current, artwork.id])} />
                {artwork.image
                  ? <img src={artwork.image} alt="" loading="lazy" referrerPolicy="no-referrer" />
                  : <span className="artwork-pick-placeholder">이미지 없음</span>}
                <span><b>{artwork.title || '제목 없음'}</b><small>{artwork.artist || '작가 미입력'}
                  {artwork.year ? ` · ${artwork.year}` : ''}</small></span>
              </label>;
            })}
          </div>}
        </div>}
        <div className="field">
          <label htmlFor="title">제목</label>
          <input id="title" name="title" placeholder="예: Q3 브랜드 리뉴얼 제안서" />
        </div>
        <div className="form-row">
          <div className="field">
            <label htmlFor="password">비밀번호 (선택)</label>
            <input id="password" name="password" type="password" autoComplete="new-password" />
          </div>
          <div className="field">
            <label htmlFor="maxViews">최대 열람</label>
            <input id="maxViews" name="maxViews" type="number" min={0} placeholder="0 = 제한 없음" />
          </div>
        </div>
        <div className="field">
          <label htmlFor="expiresAt">만료 일시</label>
          <input id="expiresAt" type="datetime-local" value={expiresAt}
            onChange={event => setExpiresAt(event.target.value)} />
          <p className="hint-text">비워 두면 만료되지 않습니다.</p>
        </div>
        {target === 'artworks' ? <>
          <div className="form-row">
            <div className="field">
              <label htmlFor="artwork-recipient">수신자 이메일</label>
              <input id="artwork-recipient" type="email" required value={recipientEmail}
                onChange={event => setRecipientEmail(event.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="artwork-recipient-label">수신자 메모 (선택)</label>
              <input id="artwork-recipient-label" value={recipientLabel}
                onChange={event => setRecipientLabel(event.target.value)} />
            </div>
          </div>
          <label className="field-inline">
            <input type="checkbox" checked={notify} onChange={event => setNotify(event.target.checked)} />
            생성한 개인 링크를 이메일로 보내기
          </label>
        </> : <div className="field">
          <label htmlFor="recipients">수신자 이메일 (선택)</label>
          <textarea id="recipients" rows={4} value={recipients}
            placeholder={'한 줄에 한 명씩\nkim@example.com\nlee@example.com'}
            onChange={event => setRecipients(event.target.value)} />
          <p className="hint-text">
            여기에 적으면 링크를 만들면서 <b>사람마다 개인 주소를 하나씩</b> 발급합니다. 비워 두면
            링크만 만들고 나중에 발급할 수 있어요. 엑셀에서 복사한 표도 붙여넣을 수 있습니다.
          </p>
        </div>}
        {target !== 'artworks' && addresses.length > 0 && (
          <label className="field-inline">
            <input type="checkbox" checked={notify}
              onChange={event => setNotify(event.target.checked)} />
            발급하면서 {addresses.length}명에게 메일로 보내기
          </label>
        )}
        <p className="hint-text">
          만든 뒤 수신자마다 개인 주소를 하나씩 발급해 전달합니다. 링크를 처음 열고 패스키를 등록한
          사람에게 귀속되며, 다른 사람에게 전달해도 열리지 않습니다.
        </p>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn-primary" type="submit"
          disabled={busy || (target === 'artworks' && (selectedWorks.length === 0 || !recipientEmail.trim()))}>
          {busy ? '만드는 중…' : target === 'artworks' ? `선택 작품 ${selectedWorks.length}점으로 링크 만들기`
            : addresses.length > 0 ? `링크 만들고 ${addresses.length}명 발급` : '링크 만들기'}
        </button>
      </form>
    </section>
  );
}
