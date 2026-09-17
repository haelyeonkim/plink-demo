import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { fetchContent, importContentPdf, importContentUrl, saveContent } from '../api';

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
  const { contentId } = useParams<{ contentId: string }>();
  const navigate = useNavigate();
  const editing = Number(contentId) || null;
  const requestedSource = params.get('source');
  const source = requestedSource === 'manual' || requestedSource === 'url' || requestedSource === 'pdf'
    ? requestedSource : null;

  const [exhibition, setExhibition] = useState('');
  const [intro, setIntro] = useState('');
  const [columns, setColumns] = useState<'1' | '2'>('2');
  const [artworks, setArtworks] = useState<Artwork[]>([empty()]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [url, setUrl] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [imported, setImported] = useState(false);
  const [warnings, setWarnings] = useState<string[]>([]);

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
      if (!editing) navigate(`/contents/${document.id}/edit`, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '저장하지 못했어요.');
    } finally { setBusy(false); }
  }

  async function runImport() {
    if (busy || !source || source === 'manual') return;
    setBusy(true); setError(''); setNotice(''); setWarnings([]);
    try {
      const result = source === 'url'
        ? await importContentUrl(url.trim())
        : await importContentPdf(file as File);
      setExhibition(result.title);
      setIntro(result.body.intro ?? '');
      setColumns(result.body.columns ?? '2');
      const rows = (result.body.artworks ?? []).map(row => ({ ...empty(), ...row } as Artwork));
      setArtworks(rows.length ? rows : [empty()]);
      setWarnings(result.warnings ?? []);
      setImported(true);
      setNotice(`${result.artworkCount}개 작품을 가져왔어요. 저장 전에 내용을 확인해 주세요.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '작품을 가져오지 못했어요.');
    } finally { setBusy(false); }
  }

  const update = (index: number, key: keyof Artwork, value: string) =>
    setArtworks(items => items.map((item, i) => (i === index ? { ...item, [key]: value } : item)));

  if (!editing && !source) {
    return (
      <section className="page-section page-wide">
        <h2>링크 관리<span className="crumb-sep">/</span><span className="crumb">새 컨텐츠 만들기</span></h2>
        <div className="picked-bar">
          <span className="picked-meta">시작할 방법을 선택하세요. 가져온 내용은 저장 전에 수정할 수 있습니다.</span>
          <span className="picked-actions"><Link className="btn-secondary" to="/contents">목록으로</Link></span>
        </div>
        <div className="content-source-grid">
          <Link to="/contents/new?source=manual" className="content-source-card">
            <span className="source-icon">✎</span><h3>직접 작성</h3>
            <p>전시와 작품 정보를 하나씩 입력하고 바로 미리봅니다.</p><b>작성 시작</b>
          </Link>
          <Link to="/contents/new?source=url" className="content-source-card">
            <span className="source-icon">↗</span><h3>웹페이지에서 가져오기</h3>
            <p>공개 뷰잉룸 URL을 분석해 작품 정보 초안을 만듭니다.</p><b>URL 입력</b>
          </Link>
          <Link to="/contents/new?source=pdf" className="content-source-card">
            <span className="source-icon">PDF</span><h3>PDF에서 가져오기</h3>
            <p>작품 목록 PDF의 텍스트를 분석해 편집 가능한 초안을 만듭니다.</p><b>파일 선택</b>
          </Link>
        </div>
      </section>
    );
  }

  if (!editing && source !== 'manual' && !imported) {
    return (
      <section className="page-section page-wide">
        <h2>링크 관리<span className="crumb-sep">/</span>
          <span className="crumb">{source === 'url' ? '웹페이지에서 가져오기' : 'PDF에서 가져오기'}</span>
        </h2>
        <div className="picked-bar">
          <span className="picked-meta">가져온 결과는 자동 저장되지 않습니다.</span>
          <span className="picked-actions"><Link className="btn-secondary" to="/contents/new">다른 방식 선택</Link></span>
        </div>
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="tab-panel import-panel">
          {source === 'url' ? (
            <div className="field">
              <label htmlFor="import-url">공개 웹페이지 URL</label>
              <input id="import-url" type="url" value={url} onChange={event => setUrl(event.target.value)}
                placeholder="https://viewing.example.com/exhibition" disabled={busy} />
              <p className="hint-text">로그인이 필요하거나 브라우저에서만 생성되는 페이지는 가져오지 못할 수 있어요.</p>
            </div>
          ) : (
            <div className="field">
              <label htmlFor="import-pdf">작품 목록 PDF</label>
              <input id="import-pdf" type="file" accept="application/pdf,.pdf" disabled={busy}
                onChange={event => setFile(event.target.files?.[0] ?? null)} />
              <p className="hint-text">20MB 이하의 텍스트 PDF를 지원합니다. 스캔 문서는 OCR이 필요해요.</p>
            </div>
          )}
          <button className="btn-primary" disabled={busy || (source === 'url' ? !url.trim() : !file)}
            onClick={() => void runImport()}>{busy ? '작품을 분석하는 중…' : '작품 정보 가져오기'}</button>
        </div>
      </section>
    );
  }

  return (
    <section className="page-section page-wide">
      <h2>
        링크 관리<span className="crumb-sep">/</span><span className="crumb">컨텐츠</span>
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
          <Link className="btn-secondary" to="/contents">목록으로</Link>
        </span>
      </div>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}
      {warnings.length > 0 && <div className="import-warnings" role="status">
        <b>가져오기 결과 확인</b>
        <ul>{warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
      </div>}

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
