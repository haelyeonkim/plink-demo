import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { deleteContent, fetchContents } from '../api';
import type { ContentSummary } from '../types';

export default function ContentList() {
  const navigate = useNavigate();
  const [contents, setContents] = useState<ContentSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try { setContents(await fetchContents()); }
    catch { setError('컨텐츠를 불러오지 못했어요.'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function remove(event: React.MouseEvent, row: ContentSummary) {
    event.stopPropagation();
    if (!window.confirm(`“${row.title}” 컨텐츠를 삭제할까요?`)) return;
    setError('');
    try { await deleteContent(row.id); await load(); }
    catch (err) { setError(err instanceof Error ? err.message : '삭제하지 못했어요.'); }
  }

  return (
    <section className="page-section page-wide">
      <h2><Link className="crumb-link" to="/links">링크 관리</Link><img className="crumb-sep" src="/icons/chevron-right.svg" alt="" width="16" height="16" /><span className="crumb" aria-current="page">컨텐츠</span>
        <span className="count">{contents.length}</span>
      </h2>
      <div className="picked-bar">
        <span className="picked-meta">내 계정에 저장한 컨텐츠입니다. 이전에 가져온 자료를 선택해 다시 열고 수정할 수 있습니다.</span>
        <span className="picked-actions">
          <Link className="btn-primary" to="/contents/new">새 컨텐츠 만들기</Link>
        </span>
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="tab-panel">
        {loading ? <p role="status">컨텐츠를 불러오고 있어요.</p> : contents.length === 0 ? (
          <div className="empty-state">
            <p>아직 저장한 컨텐츠가 없어요.</p>
            <Link className="btn-secondary" to="/contents/new">첫 컨텐츠 만들기</Link>
          </div>
        ) : (
          <ul className="entity-list">
            {contents.map(row => (
              <li key={row.id} className="openable" tabIndex={0}
                onClick={() => navigate(`/contents/${row.id}/edit`)}
                onKeyDown={event => { if (event.key === 'Enter') navigate(`/contents/${row.id}/edit`); }}>
                <div className="entity-main">
                  <span className="entity-title">{row.title}</span>
                  <span className="entity-meta">
                    <span>{row.sourceType === 'URL' ? '웹페이지' : row.sourceType === 'PDF' ? 'PDF' : '직접 작성'}</span>
                    {row.sourceType === 'PDF' && row.sourceRef && <span>{row.sourceRef}</span>}
                    <span>연결된 링크 {row.linkCount}개</span>
                    {row.updatedAt && <span>{new Date(row.updatedAt).toLocaleString('ko-KR')}</span>}
                  </span>
                </div>
                <span className="entity-actions">
                  <button className="btn-tiny" onClick={event => void remove(event, row)}>삭제</button>
                  <span className="entity-chevron">›</span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
