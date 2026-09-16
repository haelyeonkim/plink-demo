import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { createLink } from '../api';

/** "yyyy-MM-ddTHH:mm" in the operator's own zone, which is what the input speaks. */
function localInput(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const DEFAULT_HOURS = 72;

/**
 * Registering a link is its own task, so it gets its own page - the same shape as adding
 * an event on the ticket side. Recipients are issued afterwards, in the console.
 */
export default function LinkCreate() {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expiresAt, setExpiresAt] = useState(
    localInput(new Date(Date.now() + DEFAULT_HOURS * 3600000)));

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    const data = new FormData(event.currentTarget);
    try {
      const link = await createLink({
        originalUrl: String(data.get('originalUrl')),
        title: String(data.get('title') || '') || undefined,
        password: String(data.get('password') || '') || undefined,
        // Sent as the operator picked it: converting to UTC here moved every deadline.
        expiresAt: expiresAt ? `${expiresAt.replace('T', ' ')}:00` : undefined,
        maxViews: Number(data.get('maxViews') || 0) || undefined,
      });
      navigate(`/links?tab=issue&link=${link.id}`, { replace: true });
    } catch {
      setError('링크를 만들지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="page-section">
      <Link className="btn-back" to="/links">← 링크 관리</Link>
      <p className="eyebrow"><span></span> NEW LINK</p>
      <h2>링크 추가</h2>

      <form className="create-form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="originalUrl">원본 주소</label>
          <input id="originalUrl" name="originalUrl" type="url" required
            placeholder="https://example.com/my-document" />
        </div>
        <div className="field">
          <label htmlFor="title">제목</label>
          <input id="title" name="title" placeholder="예: Q3 브랜드 리뉴얼 제안서" />
        </div>
        <div className="form-row">
          <div className="field">
            <label htmlFor="password">비밀번호 (선택)</label>
            <input id="password" name="password" type="password" autoComplete="new-password" />
          </div>
          <div className="field">
            <label htmlFor="maxViews">최대 열람</label>
            <input id="maxViews" name="maxViews" type="number" min={0} placeholder="0 = 제한 없음" />
          </div>
        </div>
        <div className="field">
          <label htmlFor="expiresAt">만료 일시</label>
          <input id="expiresAt" type="datetime-local" value={expiresAt}
            onChange={event => setExpiresAt(event.target.value)} />
          <p className="hint-text">비워 두면 만료되지 않습니다.</p>
        </div>
        <p className="hint-text">
          만든 뒤 수신자마다 개인 주소를 하나씩 발급해 전달합니다. 링크를 처음 열고 패스키를 등록한
          사람에게 귀속되며, 다른 사람에게 전달해도 열리지 않습니다.
        </p>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn-primary" type="submit" disabled={busy}>
          {busy ? '만드는 중…' : '링크 만들기'}
        </button>
      </form>
    </section>
  );
}
