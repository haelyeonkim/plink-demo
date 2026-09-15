import { useState } from 'react';

type Artwork = { image: string; artist: string; title: string; year: string; medium: string; width: string; height: string; depth: string; unit: 'cm' | 'inch'; description: string; price: string };
const emptyArtwork = (): Artwork => ({ image: '', artist: '', title: '', year: '', medium: '', width: '', height: '', depth: '', unit: 'cm', description: '', price: '' });

export default function ContentCreate() {
  const [exhibition, setExhibition] = useState('');
  const [intro, setIntro] = useState('');
  const [artworks, setArtworks] = useState<Artwork[]>([emptyArtwork()]);
  const [columns, setColumns] = useState<'1' | '2'>('2');
  const [saved, setSaved] = useState(false);
  const [linkCreated, setLinkCreated] = useState(false);
  const update = (index: number, key: keyof Artwork, value: string) => setArtworks(items => items.map((item, i) => i === index ? { ...item, [key]: value } : item));
  return (
    <section className="page-section content-create-page">
      <div className="section-header"><p className="eyebrow"><span /> CONTENT STUDIO</p><h2>컨텐츠 생성</h2><p className="section-desc">전시·작품 리스트 형태의 공유 콘텐츠를 임시로 구성합니다.</p></div>
      <div className="content-editor">
        <div className="content-form">
          <h3>전시 정보</h3>
          <label>전시 제목<input value={exhibition} onChange={e => setExhibition(e.target.value)} placeholder="예: KIAF 2026" maxLength={100} /></label>
          <label>소개글<textarea value={intro} onChange={e => setIntro(e.target.value)} placeholder="전시와 작품을 소개해 주세요." rows={5} maxLength={2000} /></label>
          <div className="artwork-form-heading"><h3>작품 리스트</h3><div className="artwork-controls"><label>나열 방식<select value={columns} onChange={e => setColumns(e.target.value as '1' | '2')}><option value="1">1열</option><option value="2">2열</option></select></label><button type="button" className="btn-secondary" onClick={() => setArtworks(items => [...items, emptyArtwork()])}>＋ 작품 추가</button></div></div>
          {artworks.map((artwork, index) => <fieldset className="artwork-editor" key={index}><legend>작품 {index + 1}</legend><button type="button" className="artwork-remove" disabled={artworks.length === 1} onClick={() => setArtworks(items => items.filter((_, i) => i !== index))}>삭제</button>
            <label>이미지 URL<input type="url" value={artwork.image} onChange={e => update(index, 'image', e.target.value)} placeholder="https://.../artwork.jpg" /></label>
            <div className="field-row"><label>작가<input value={artwork.artist} onChange={e => update(index, 'artist', e.target.value)} placeholder="작가명" /></label><label>작품명<input value={artwork.title} onChange={e => update(index, 'title', e.target.value)} placeholder="작품 제목" /></label></div>
            <div className="field-row"><label>제작연도<input value={artwork.year} onChange={e => update(index, 'year', e.target.value)} placeholder="2026" /></label><label>재료<input value={artwork.medium} onChange={e => update(index, 'medium', e.target.value)} placeholder="Oil on canvas" /></label></div>
            <div className="size-label">크기</div><div className="size-fields"><label>가로<input type="number" min="0" step="0.1" value={artwork.width} onChange={e => update(index, 'width', e.target.value)} placeholder="가로" /></label><label>세로<input type="number" min="0" step="0.1" value={artwork.height} onChange={e => update(index, 'height', e.target.value)} placeholder="세로" /></label><label>폭 (옵션)<input type="number" min="0" step="0.1" value={artwork.depth} onChange={e => update(index, 'depth', e.target.value)} placeholder="폭" /></label><label>단위<select value={artwork.unit} onChange={e => update(index, 'unit', e.target.value as 'cm' | 'inch')}><option value="cm">cm</option><option value="inch">inch</option></select></label></div>
            <label>작품 설명<textarea value={artwork.description} onChange={e => update(index, 'description', e.target.value)} rows={3} placeholder="작품 설명" /></label>
            <label>가격 또는 문의 문구<input value={artwork.price} onChange={e => update(index, 'price', e.target.value)} placeholder="가격 문의" /></label>
          </fieldset>)}
          <div className="content-actions"><button type="button" className="btn-secondary" onClick={() => { setSaved(true); setLinkCreated(false); }}>컨텐츠 저장</button><button type="button" className="btn-primary" onClick={() => { setSaved(true); setLinkCreated(true); }}>컨텐츠로 링크 생성</button></div>{saved && <p className="content-save-note" role="status">{linkCreated ? '컨텐츠 링크가 생성되었습니다. 아래 미리보기를 확인해 주세요.' : '임시 컨텐츠가 이 화면에 반영되었습니다. 현재 서버에는 저장되지 않습니다.'}</p>}
        </div>
        <div className="content-preview"><p className="preview-label">PREVIEW · {columns === '1' ? '1열' : '2열'}</p><h3>{exhibition || '전시 제목'}</h3><p>{intro || '전시 소개가 여기에 표시됩니다.'}</p><div className={`artwork-grid artwork-grid-${columns}`}>{artworks.map((artwork, index) => { const dimensions = [artwork.width, artwork.height, artwork.depth].filter(Boolean).join(' × '); return <article key={index}>{artwork.image ? <img src={artwork.image} alt={artwork.title || `작품 ${index + 1}`} /> : <div className="artwork-placeholder">작품 이미지</div>}<div><b>{artwork.artist || '작가명'}</b><h4>{artwork.title || '작품 제목'}{artwork.year && `, ${artwork.year}`}</h4><small>{artwork.medium || '재료'} · {dimensions ? `${dimensions} ${artwork.unit}` : '크기'}</small>{artwork.price && <em>{artwork.price}</em>}</div></article>; })}</div></div>
      </div>
    </section>
  );
}
