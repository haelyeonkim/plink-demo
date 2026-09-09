import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { fetchLink } from '../api';
import type { LinkDetail as LinkDetailType } from '../types';

function formatDate(s: string): string {
  const d = new Date(s);
  return d.toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function timeAgo(s: string): string {
  const diff = Date.now() - new Date(s).getTime();
  const min = Math.floor(diff / 60000);
  if (min < 60) return `${min}분 전`;
  const hours = Math.floor(min / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

export default function LinkDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [link, setLink] = useState<LinkDetailType | null>(null);

  useEffect(() => {
    if (id) fetchLink(Number(id)).then(setLink).catch(() => {});
  }, [id]);

  if (!link) return <section className="page-section"><p className="loading">불러오는 중...</p></section>;

  const expired = link.expiresAt ? new Date(link.expiresAt).getTime() < Date.now() : false;

  return (
    <section className="page-section">
      <Link className="btn-back" to="/manage">&larr; 목록으로</Link>
      <div className="detail-header">
        <h2>{link.title || '제목 없음'}</h2>
        <div className="detail-meta">
          <span className={`status ${expired ? 'status-expired' : 'status-active'}`}>
            {expired ? '만료됨' : '활성'}
          </span>
          <span className="status status-lock">{link.claimed ? '패스키 수신 확정' : '수신 대기'}</span>
          {link.hasPassword && <span className="status status-lock">비밀번호 보호</span>}
        </div>
      </div>

      <div className="detail-grid">
        <div className="detail-card">
          <h3>링크 정보</h3>
          <dl>
            <dt>원본 URL</dt>
            <dd><a href={link.originalUrl} target="_blank" rel="noopener">{link.originalUrl}</a></dd>
            <dt>공유 코드</dt>
            <dd>
              <code>{link.shortCode}</code>
              <button className="btn-tiny" onClick={() => navigator.clipboard.writeText(`${window.location.origin}/s/${link.shortCode}`)}>복사</button>
              <button className="btn-tiny" onClick={() => navigate(`/s/${link.shortCode}`)}>수신 화면</button>
            </dd>
            <dt>수신자 메모 (관리용)</dt>
            <dd>{link.recipientNames || '-'}</dd>
            <dt>수신 안내</dt><dd>수신 화면에서 직접 패스키를 등록하면 관리자 본인에게 귀속됩니다. 수신자에게 전달할 링크는 먼저 등록하지 마세요.</dd>
            <dt>생성일</dt>
            <dd>{formatDate(link.createdAt)}</dd>
            <dt>만료일</dt>
            <dd>{link.expiresAt ? formatDate(link.expiresAt) : '없음'}</dd>
            <dt>최대 열람</dt>
            <dd>{link.maxViews > 0 ? `${link.maxViews}회` : '제한 없음'}</dd>
          </dl>
        </div>

        <div className="detail-card">
          <h3>열람 현황 <span className="count">{link.views.length}회</span></h3>
          {link.views.length === 0 ? (
            <p className="empty">아직 열람 기록이 없습니다.</p>
          ) : (
            <ul className="view-list">
              {link.views.map((v, i) => (
                <li key={i}>
                  <span className="viewer-avatar">{v.viewerName.charAt(0)}</span>
                  <div>
                    <b>{v.viewerName}</b>
                    <small>{timeAgo(v.viewedAt)}</small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}
