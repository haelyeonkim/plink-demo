import { useState } from 'react';

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
 * Composes an exhibition page to share behind a protected link.
 *
 * <p>Nothing is stored yet: the preview is the output, and the page says so rather
 * than implying a draft is waiting on the server. What it settles is the shape of the
 * content - what an artwork row carries - before that decision costs a migration.
 */
export default function ContentCreate() {
  const [exhibition, setExhibition] = useState('');
  const [intro, setIntro] = useState('');
  const [columns, setColumns] = useState<'1' | '2'>('2');
  const [artworks, setArtworks] = useState<Artwork[]>([empty()]);

  const update = (index: number, key: keyof Artwork, value: string) =>
    setArtworks(items => items.map((item, i) => (i === index ? { ...item, [key]: value } : item)));

  return (
    <section className="page-section page-wide">
      <p className="eyebrow"><span></span> CONTENT STUDIO</p>
      <h2>컨텐츠 생성</h2>

      <p className="notice-text" role="status">
        미리보기 전용입니다. 아직 서버에 저장되지 않으며, 새로고침하면 사라집니다.
      </p>

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
