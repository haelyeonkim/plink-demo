import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchLinks, deleteLink } from '../api';
import type { ProtectedLink } from '../types';

function timeRemaining(expiresAt: string | null): string {
  if (!expiresAt) return '-';
  const diff = new Date(expiresAt).getTime() - Date.now();
  if (diff <= 0) return '만료됨';
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}일 ${hours % 24}시간`;
  return `${hours}시간`;
}

export default function LinkList() {
  const navigate = useNavigate();
  const [links, setLinks] = useState<ProtectedLink[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      setLinks(await fetchLinks());
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleDelete = async (id: number) => {
    if (!confirm('이 링크를 삭제하시겠습니까?')) return;
    await deleteLink(id);
    load();
  };

  return (
    <section className="page-section">
      <div className="section-header">
        <p className="eyebrow"><span></span> MANAGE LINKS</p>
        <h2>링크 관리</h2>
        <p className="section-desc">생성한 보호 링크를 관리하고 열람 현황을 확인하세요.</p>
      </div>
      {loading ? (
        <p className="loading">불러오는 중...</p>
      ) : links.length === 0 ? (
        <p className="empty">생성된 링크가 없습니다.</p>
      ) : (
        <div className="link-table">
          <div className="table-header">
            <span>제목</span>
            <span>코드</span>
            <span>열람</span>
            <span>만료</span>
            <span>보호</span>
            <span></span>
          </div>
          {links.map((link) => (
            <div key={link.id} className="table-row" onClick={() => navigate(`/manage/${link.id}`)}>
              <span className="cell-title">{link.title || link.originalUrl}</span>
              <span className="cell-code">
                <code>{link.shortCode}</code>
                <button
                  className="btn-tiny"
                  onClick={(e) => { e.stopPropagation(); navigate(`/s/${link.shortCode}`); }}
                  title="접속 테스트"
                >
                  &#x2197;
                </button>
              </span>
              <span className="cell-views">
                {link.viewCount}{link.maxViews > 0 ? `/${link.maxViews}` : ''}
              </span>
              <span className={`cell-expires ${timeRemaining(link.expiresAt) === '만료됨' ? 'expired' : ''}`}>
                {timeRemaining(link.expiresAt)}
              </span>
              <span className="cell-lock">{link.hasPassword ? '&#x1F512;' : '-'}</span>
              <span className="cell-actions">
                <button
                  className="btn-delete"
                  onClick={(e) => { e.stopPropagation(); handleDelete(link.id); }}
                  title="삭제"
                >
                  &times;
                </button>
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
