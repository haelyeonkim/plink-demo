import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createLink } from '../api';

export default function CreateLink() {
  const navigate = useNavigate();
  const [url, setUrl] = useState('');
  const [title, setTitle] = useState('');
  const [password, setPassword] = useState('');
  const [recipients, setRecipients] = useState('');
  const [maxViews, setMaxViews] = useState('');
  const [expiresIn, setExpiresIn] = useState('72');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<{ shortCode: string } | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      const hours = parseInt(expiresIn) || 72;
      const expiresAt = new Date(Date.now() + hours * 3600000)
        .toISOString()
        .replace('T', ' ')
        .substring(0, 19);

      const link = await createLink({
        originalUrl: url,
        title: title || undefined,
        password: password || undefined,
        expiresAt,
        recipientNames: recipients || undefined,
        maxViews: maxViews ? parseInt(maxViews) : undefined,
      });
      setResult({ shortCode: link.shortCode });
    } catch {
      alert('링크 생성에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  if (result) {
    return (
      <section className="page-section">
        <div className="result-card">
          <div className="result-icon">&#x2713;</div>
          <h2>보호 링크가 생성되었습니다</h2>
          <div className="result-code">
            <label>공유 코드</label>
            <div className="code-display">
              <code>{result.shortCode}</code>
              <button
                onClick={() => navigator.clipboard.writeText(result.shortCode)}
                title="복사"
              >
                복사
              </button>
            </div>
          </div>
          <p className="result-hint">이 코드를 공유 대상에게 전달하세요.</p>
          <div className="result-actions">
            <Link className="btn-primary" to="/manage">링크 관리로 이동</Link>
            <button className="btn-secondary" onClick={() => { setResult(null); setUrl(''); setTitle(''); setPassword(''); setRecipients(''); setMaxViews(''); }}>
              새 링크 만들기
            </button>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="page-section">
      <div className="section-header">
        <p className="eyebrow"><span></span> CREATE LINK</p>
        <h2>보호 링크 만들기</h2>
        <p className="section-desc">원본 링크를 입력하고 보호 옵션을 설정하세요.</p>
      </div>
      <form className="create-form" onSubmit={handleSubmit}>
        <div className="field">
          <label>원본 링크 *</label>
          <input
            type="url"
            required
            placeholder="https://example.com/my-document"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
        </div>
        <div className="field">
          <label>링크 제목</label>
          <input
            type="text"
            placeholder="예: Q3 브랜드 리뉴얼 제안서"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </div>
        <div className="field-row">
          <div className="field">
            <label>비밀번호</label>
            <input
              type="password"
              placeholder="선택 사항"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <div className="field">
            <label>만료 시간 (시간)</label>
            <input
              type="number"
              min="1"
              placeholder="72"
              value={expiresIn}
              onChange={(e) => setExpiresIn(e.target.value)}
            />
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label>공유 대상</label>
            <input
              type="text"
              placeholder="쉼표로 구분 (김지수,박현우)"
              value={recipients}
              onChange={(e) => setRecipients(e.target.value)}
            />
          </div>
          <div className="field">
            <label>최대 열람 수</label>
            <input
              type="number"
              min="0"
              placeholder="0 = 제한 없음"
              value={maxViews}
              onChange={(e) => setMaxViews(e.target.value)}
            />
          </div>
        </div>
        <button className="btn-primary" type="submit" disabled={loading}>
          {loading ? '생성 중...' : '보호 링크 생성'}
        </button>
      </form>
    </section>
  );
}
