import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { accessLink, verifyLink } from '../api';
import type { AccessInfo } from '../types';

export default function AccessLink() {
  const { shortCode } = useParams<{ shortCode: string }>();
  const [info, setInfo] = useState<AccessInfo | null>(null);
  const [password, setPassword] = useState('');
  const [viewerName, setViewerName] = useState('');
  const [error, setError] = useState('');
  const [redirectUrl, setRedirectUrl] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (shortCode) {
      accessLink(shortCode)
        .then(setInfo)
        .catch(() => setError('링크를 찾을 수 없습니다.'))
        .finally(() => setLoading(false));
    }
  }, [shortCode]);

  const handleVerify = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      const url = await verifyLink(shortCode!, password, viewerName || 'Anonymous');
      setRedirectUrl(url);
    } catch {
      setError('비밀번호가 올바르지 않거나 접근이 거부되었습니다.');
    }
  };

  const handleOpenWithoutPassword = async () => {
    setError('');
    try {
      const url = await verifyLink(shortCode!, '', viewerName || 'Anonymous');
      setRedirectUrl(url);
    } catch {
      setError('접근이 거부되었습니다.');
    }
  };

  if (loading) return <section className="page-section"><p className="loading">확인 중...</p></section>;

  if (error && !info) {
    return (
      <section className="page-section">
        <div className="access-card">
          <div className="access-icon error-icon">&#x2716;</div>
          <h2>링크를 찾을 수 없습니다</h2>
          <p>코드를 다시 확인해 주세요.</p>
          <Link className="btn-secondary" to="/">돌아가기</Link>
        </div>
      </section>
    );
  }

  if (redirectUrl) {
    return (
      <section className="page-section">
        <div className="access-card">
          <div className="access-icon success-icon">&#x2713;</div>
          <h2>인증 완료</h2>
          <p>원본 링크로 이동할 수 있습니다.</p>
          <a className="btn-primary" href={redirectUrl} target="_blank" rel="noopener">
            원본 링크 열기 &rarr;
          </a>
          <Link className="btn-secondary" to="/">홈으로</Link>
        </div>
      </section>
    );
  }

  if (!info) return null;

  return (
    <section className="page-section">
      <div className="access-card">
        <div className="access-lock">
          <img className="card-logo" src="/logo-small.svg" alt="P" width="36" height="36" />
        </div>
        <p className="secure-label">PROTECTED LINK</p>
        <h2>{info.title || '보호된 링크'}</h2>
        {info.expired ? (
          <>
            <p className="error-text">이 링크는 만료되었습니다.</p>
            <Link className="btn-secondary" to="/">돌아가기</Link>
          </>
        ) : (
          <form onSubmit={info.hasPassword ? handleVerify : (e) => { e.preventDefault(); handleOpenWithoutPassword(); }}>
            <div className="field">
              <label>이름</label>
              <input
                type="text"
                placeholder="열람자 이름"
                value={viewerName}
                onChange={(e) => setViewerName(e.target.value)}
              />
            </div>
            {info.hasPassword && (
              <div className="field">
                <label>비밀번호</label>
                <input
                  type="password"
                  required
                  placeholder="공유자에게 받은 비밀번호 입력"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </div>
            )}
            {error && <p className="error-text">{error}</p>}
            <button className="btn-primary" type="submit">
              안전하게 링크 열기 &rarr;
            </button>
          </form>
        )}
      </div>
    </section>
  );
}
