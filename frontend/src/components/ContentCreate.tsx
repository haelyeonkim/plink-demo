import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { fetchContent, fetchContents, saveContent, deleteContent } from '../api';
import type { ContentSummary } from '../types';

interface Artwork {
  image: string; artist: string; title: string; year: string; medium: string;
  width: string; height: string; depth: string; unit: 'cm' | 'inch';
  description: string; price: string;
}

const empty = (): Artwork => ({
  image: '', artist: '', title: '', year: '', medium: '',
  width: '', height: '', depth: '', unit: 'cm', description: '', price: '',
});

/**
 * Composes a page to share behind a protected link, and keeps it.
 *
 * <p>What is written here is stored on the account and served to no one except through
 * a link's own passkey ceremony: it has no address of its own, which is the whole point
 * of writing it here rather than publishing it somewhere and protecting the copy.
 */
export default function ContentCreate() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const editing = Number(params.get('id')) || null;

  const [exhibition, setExhibition] = useState('');
  const [intro, setIntro] = useState('');
  const [columns, setColumns] = useState<'1' | '2'>('2');
  const [artworks, setArtworks] = useState<Artwork[]>([empty()]);
  const [saved, setSaved] = useState<ContentSummary[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadSaved = useCallback(async () => {
    try { setSaved(await fetchContents()); }
    catch { /* the studio still works without the list */ }
  }, []);
  useEffect(() => { void loadSaved(); }, [loadSaved]);

  useEffect(() => {
    if (!editing) return;
    let live = true;
    fetchContent(editing).then(document => {
      if (!live) return;
      setExhibition(document.title);
      setIntro(document.body.intro ?? '');
      setColumns(document.body.columns ?? '2');
      const rows = (document.body.artworks ?? []).map(row => ({ ...empty(), ...row } as Artwork));
      setArtworks(rows.length > 0 ? rows : [empty()]);
    }).catch(() => { if (live) setError('컨텐츠를 불러오지 못했어요.'); });
    return () => { live = false; };
  }, [editing]);

  async function save() {
    setError(''); setNotice(''); setBusy(true);
    try {
      const document = await saveContent(editing, exhibition.trim() || '제목 없는 컨텐츠',
        { intro, columns, artworks: artworks.map(artwork => ({ ...artwork })) });
      setNotice('저장했어요. 링크를 만들 때 이 컨텐츠를 고를 수 있습니다.');
      await loadSaved();
      if (!editing) navigate(`/content/create?id=${document.id}`, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '저장하지 못했어요.');
    } finally { setBusy(false); }
  }

  async function remove(id: number) {
    setError(''); setNotice('');
    try {
      await deleteContent(id);
      await loadSaved();
      if (editing === id) navigate('/content/create', { replace: true });
    } catch (err) { setError(err instanceof Error ? err.message : '삭제하지 못했어요.'); }
  }

  const update = (index: number, key: keyof Artwork, value: string) =>
    setArtworks(items => items.map((item, i) => (i === index ? { ...item, [key]: value } : item)));

  return (
    <section className="page-section page-wide">
      <h2>
        컨텐츠
        {editing && <><span className="crumb-sep">/</span><span className="crumb">{exhibition || '제목 없음'}</span></>}
      </h2>

      <div className="picked-bar">
        <span className="picked-meta">
          {editing ? '저장된 컨텐츠를 편집하고 있어요.' : '새 컨텐츠를 작성하고 있어요.'}
        </span>
        <span className="picked-actions">
          <button className="btn-secondary" onClick={save} disabled={busy}>
            {busy ? '저장 중…' : editing ? '저장' : '컨텐츠 저장'}
          </button>
          <Link className="btn-secondary" to="/links">링크 관리</Link>
        </span>
      </div>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      {saved.length > 0 && (
        <div className="tab-panel">
          <div className="panel-head">
            <h3>저장된 컨텐츠 <span className="count">{saved.length}</span></h3>
            {editing && <Link className="btn-tiny" to="/content/create">새로 작성</Link>}
          </div>
          <ul className="entity-list">
            {saved.map(row => (
              <li key={row.id} className={row.id === editing ? 'no-hover' : 'openable'}
                onClick={() => row.id !== editing && navigate(`/content/create?id=${row.id}`)}>
                <div className="entity-main">
                  <span className="entity-title">{row.title}</span>
                  <span className="entity-meta">
                    <span>링크 {row.linkCount}개</span>
                    {row.updatedAt && <span>{new Date(row.updatedAt).toLocaleString('ko-KR')}</span>}
                  </span>
                </div>
                <span className="entity-actions">
                  <button className="btn-tiny" onClick={event => { event.stopPropagation(); void remove(row.id); }}>
                    삭제
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="tab-panel tab-split content-split">
        <div>
          <h3>전시 정보</h3>
          <div className="field">
            <label htmlFor="content-title">전시 제목</label>
            <input id="content-title" value={exhibition} maxLength={100} placeholder="예: KIAF 2026"
              onChange={event => setExhibition(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="content-intro">소개글</label>
            <textarea id="content-intro" rows={5} value={intro} maxLength={2000}
              placeholder="전시와 작품을 소개해 주세요."
              onChange={event => setIntro(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="content-columns">나열 방식</label>
            <select id="content-columns" value={columns}
              onChange={event => setColumns(event.target.value as '1' | '2')}>
              <option value="1">1열</option>
              <option value="2">2열</option>
            </select>
          </div>

          <div className="panel-head">
            <h3>작품 {artworks.length}점</h3>
            <button type="button" className="btn-tiny"
              onClick={() => setArtworks(items => [...items, empty()])}>작품 추가</button>
          </div>

          {artworks.map((artwork, index) => (
            <div className="artwork-editor" key={index}>
              <div className="panel-head">
                <h3>작품 {index + 1}</h3>
                <button type="button" className="btn-delete" title="작품 삭제"
                  disabled={artworks.length === 1}
                  onClick={() => setArtworks(items => items.filter((_, i) => i !== index))}>
                  &times;
                </button>
              </div>
              <div className="field">
                <label htmlFor={`art-image-${index}`}>이미지 주소</label>
                <input id={`art-image-${index}`} type="url" value={artwork.image}
                  placeholder="https://…/artwork.jpg"
                  onChange={event => update(index, 'image', event.target.value)} />
              </div>
              <div className="form-row">
                <div className="field">
                  <label htmlFor={`art-artist-${index}`}>작가</label>
                  <input id={`art-artist-${index}`} value={artwork.artist}
                    onChange={event => update(index, 'artist', event.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor={`art-title-${index}`}>작품명</label>
                  <input id={`art-title-${index}`} value={artwork.title}
                    onChange={event => update(index, 'title', event.target.value)} />
                </div>
              </div>
              <div className="form-row">
                <div className="field">
                  <label htmlFor={`art-year-${index}`}>제작연도</label>
                  <input id={`art-year-${index}`} value={artwork.year} placeholder="2026"
                    onChange={event => update(index, 'year', event.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor={`art-medium-${index}`}>재료</label>
                  <input id={`art-medium-${index}`} value={artwork.medium} placeholder="Oil on canvas"
                    onChange={event => update(index, 'medium', event.target.value)} />
                </div>
              </div>
              <div className="form-row">
                <div className="field">
                  <label htmlFor={`art-width-${index}`}>가로</label>
                  <input id={`art-width-${index}`} type="number" min={0} step={0.1} value={artwork.width}
                    onChange={event => update(index, 'width', event.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor={`art-height-${index}`}>세로</label>
                  <input id={`art-height-${index}`} type="number" min={0} step={0.1} value={artwork.height}
                    onChange={event => update(index, 'height', event.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor={`art-depth-${index}`}>폭 (선택)</label>
                  <input id={`art-depth-${index}`} type="number" min={0} step={0.1} value={artwork.depth}
                    onChange={event => update(index, 'depth', event.target.value)} />
                </div>
                <div className="field">
                  <label htmlFor={`art-unit-${index}`}>단위</label>
                  <select id={`art-unit-${index}`} value={artwork.unit}
                    onChange={event => update(index, 'unit', event.target.value)}>
                    <option value="cm">cm</option>
                    <option value="inch">inch</option>
                  </select>
                </div>
              </div>
              <div className="field">
                <label htmlFor={`art-desc-${index}`}>작품 설명</label>
                <textarea id={`art-desc-${index}`} rows={3} value={artwork.description}
                  onChange={event => update(index, 'description', event.target.value)} />
              </div>
              <div className="field">
                <label htmlFor={`art-price-${index}`}>가격 또는 문의 문구</label>
                <input id={`art-price-${index}`} value={artwork.price} placeholder="가격 문의"
                  onChange={event => update(index, 'price', event.target.value)} />
              </div>
            </div>
          ))}
        </div>

        <div className="content-preview">
          <p className="eyebrow"><span></span> PREVIEW · {columns === '1' ? '1열' : '2열'}</p>
          <h3>{exhibition || '전시 제목'}</h3>
          <p className="section-desc">{intro || '전시 소개가 여기에 표시됩니다.'}</p>
          <div className={`artwork-grid artwork-grid-${columns}`}>
            {artworks.map((artwork, index) => {
              const size = [artwork.width, artwork.height, artwork.depth].filter(Boolean).join(' × ');
              return (
                <article key={index}>
                  {artwork.image
                    ? <img src={artwork.image} alt={artwork.title || `작품 ${index + 1}`} />
                    : <div className="artwork-placeholder">작품 이미지</div>}
                  <b>{artwork.artist || '작가명'}</b>
                  <h4>{artwork.title || '작품 제목'}{artwork.year ? `, ${artwork.year}` : ''}</h4>
                  <small>{artwork.medium || '재료'} · {size ? `${size} ${artwork.unit}` : '크기'}</small>
                  {artwork.description && <p>{artwork.description}</p>}
                  {artwork.price && <em>{artwork.price}</em>}
                </article>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
