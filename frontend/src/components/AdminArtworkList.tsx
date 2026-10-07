import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { ArtworkRecord } from '../types';
import { mutate } from '../auth';
import LegacyArtworkLinks from './LegacyArtworkLinks';

interface AdminArtwork extends ArtworkRecord {
  ownerSub: string; ownerEmail: string | null; ownerName: string | null;
  contentTitle: string; sourceType: string; updatedAt: string | null;
  archived: boolean;
}
interface ArtworkPage { items: AdminArtwork[]; total: number; page: number; pageSize: number }
const statuses = { unsold: '미판매', hold: '대기', sold: '판매 완료' };

function Thumbnail({ src }: { src: string }) {
  const [failed, setFailed] = useState(false);
  return src && !failed ? <img src={src} alt="" loading="lazy" referrerPolicy="no-referrer"
    onError={() => setFailed(true)} /> : <span className="admin-artwork-placeholder">이미지 없음</span>;
}

export default function AdminArtworkList() {
  const [params, setParams] = useSearchParams();
  const search = params.get('search') || '';
  const status = params.get('status') || 'all';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const [draft, setDraft] = useState(search);
  const [result, setResult] = useState<ArtworkPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [saving, setSaving] = useState<number | null>(null);
  const [notice, setNotice] = useState('');
  useEffect(() => setDraft(search), [search]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError('');
    const query = new URLSearchParams({ search, status, page: String(page), pageSize: '30' });
    void (async () => {
      try {
        const response = await fetch(`/api/admin/artworks?${query}`, { signal: controller.signal });
        if (!response.ok) throw new Error('작품 목록을 불러오지 못했어요. 권한과 로그인 상태를 확인해 주세요.');
        const data: ArtworkPage = await response.json();
        if (!controller.signal.aborted) setResult(data);
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '작품 목록을 불러오지 못했어요.');
      } finally { if (!controller.signal.aborted) setLoading(false); }
    })();
    return () => controller.abort();
  }, [search, status, page, revision]);

  function change(nextSearch: string, nextStatus: string, nextPage = 0) {
    setParams({ search: nextSearch, status: nextStatus, page: String(nextPage) });
  }
  async function changeStatus(row: AdminArtwork, value: string) {
    if (!window.confirm(`“${row.title}”의 상태를 ${statuses[value as keyof typeof statuses] || '미판매'}로 변경할까요? 이 작품을 포함한 모든 연결된 링크에 반영됩니다.`)) return;
    setSaving(row.id); setNotice('');
    try {
      const response = await mutate(`/api/admin/artworks/${row.id}/status`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ saleStatus: value || null }),
      });
      if (!response.ok) throw new Error('판매 상태를 변경하지 못했어요.');
      setNotice('판매 상태를 변경했습니다. 연결된 링크에도 반영됩니다.');
      setRevision(current => current + 1);
    } catch (err) { setNotice(err instanceof Error ? err.message : '판매 상태를 변경하지 못했어요.'); }
    finally { setSaving(null); }
  }
  return <section className="page-section page-wide">
    <h2><Link className="crumb-link" to="/admin">관리자</Link>
      <img className="crumb-sep" src="/icons/chevron-right.svg" alt="" width="16" height="16" />
      <span className="crumb" aria-current="page">작품 목록</span>
    </h2>
    <p className="section-desc">전체 계정에서 직접 등록하거나 가져온 작품을 확인합니다.</p>
    {notice && <p role="status" className="hint-text">{notice}</p>}
    <LegacyArtworkLinks />
    <form className="admin-artwork-filters" onSubmit={event => { event.preventDefault(); change(draft.trim(), status); }}>
      <div className="field"><label htmlFor="artwork-search">작품 검색</label>
        <input id="artwork-search" type="search" maxLength={200} value={draft}
          placeholder="작품명, 작가, 컨텐츠, 등록 계정" onChange={event => setDraft(event.target.value)} /></div>
      <div className="field"><label htmlFor="artwork-status">판매 상태</label>
        <select id="artwork-status" value={status} onChange={event => change(draft.trim(), event.target.value)}>
          <option value="all">전체</option>
          {Object.entries(statuses).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
        </select></div>
      <button className="btn-primary" type="submit">검색</button>
      <button className="btn-secondary" type="button" onClick={() => setRevision(value => value + 1)}>새로고침</button>
    </form>
    {loading ? <p role="status">작품을 불러오고 있어요.</p> : error ? <p className="error-text" role="alert">{error}</p> : result && <>
      <p role="status">{search || status !== 'all' ? '검색 결과' : '전체'} {result.total.toLocaleString()}점</p>
      {result.items.length === 0 ? <div className="empty-state"><p>{search || status !== 'all'
        ? '검색 조건에 맞는 작품이 없어요.' : '등록된 작품이 없어요.'}</p></div> :
        <div className="admin-artwork-table-wrap" tabIndex={0} role="region" aria-label="전체 작품 목록">
          <table className="admin-artwork-table">
            <thead><tr><th scope="col">작품</th><th scope="col">작가 / 제작연도</th><th scope="col">가격</th>
              <th scope="col">판매 상태</th><th scope="col">등록 계정</th><th scope="col">컨텐츠 / 출처</th><th scope="col">수정일</th></tr></thead>
            <tbody>{result.items.map(row => <tr key={row.id}>
              <td><div className="admin-artwork-work"><Thumbnail key={row.image} src={row.image} />
                <div><strong>{row.title || '제목 없음'}</strong><small>{row.medium || '재료 미입력'}</small>
                  {row.archived && <small>원본 목록에서 제거됨 · 기존 링크 유지</small>}</div></div></td>
              <td>{row.artist || '작가 미입력'}<small>{row.year || '—'}</small></td>
              <td>{row.price || '—'}</td><td><select className={`artwork-status ${row.saleStatus || 'unsold'}`}
                aria-label={`${row.title} 판매 상태`} value={row.saleStatus || ''} disabled={saving !== null}
                onChange={event => void changeStatus(row, event.target.value)}>
                <option value="">미판매</option><option value="hold">대기</option><option value="sold">판매 완료</option>
              </select></td>
              <td>{row.ownerName || row.ownerEmail || row.ownerSub}<small>{row.ownerName ? row.ownerEmail : ''}</small></td>
              <td>{row.contentTitle}<small>{row.sourceType === 'URL' ? '웹페이지' : row.sourceType === 'PDF' ? 'PDF' : '직접 작성'}</small></td>
              <td>{row.updatedAt ? new Date(row.updatedAt).toLocaleDateString('ko-KR') : '—'}</td>
            </tr>)}</tbody>
          </table>
        </div>}
      {(result.total > result.pageSize || page > 0) && <nav className="admin-artwork-pagination" aria-label="작품 목록 페이지">
        <button className="btn-secondary" disabled={page === 0} onClick={() => change(search, status, page - 1)}>이전</button>
        <span>{page + 1} / {Math.max(1, Math.ceil(result.total / result.pageSize))}</span>
        <button className="btn-secondary" disabled={(page + 1) * result.pageSize >= result.total}
          onClick={() => change(search, status, page + 1)}>다음</button>
      </nav>}
    </>}
  </section>;
}
