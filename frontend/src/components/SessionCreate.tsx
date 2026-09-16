import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { mutate } from '../auth';

/** Creating a session is its own task, so it gets its own page rather than a corner of the console. */
export default function SessionCreate() {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    const data = new FormData(event.currentTarget);
    try {
      const startsAt = new Date(String(data.get('startsAt'))).toISOString();
      const gateOpensRaw = String(data.get('gateOpensAt') || '');
      const response = await mutate('/api/admin/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: data.get('name'),
          venue: data.get('venue'),
          startsAt,
          gateOpensAt: gateOpensRaw ? new Date(gateOpensRaw).toISOString() : null,
        }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || '행사를 만들지 못했어요.');
      navigate('/tickets/admin', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '행사를 만들지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="page-section">
      <Link className="btn-back" to="/tickets/admin">← 입장권 관리</Link>
      <p className="eyebrow"><span></span> NEW SESSION</p>
      <h2>행사 추가</h2>

      <form className="create-form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="name">행사 이름</label>
          <input id="name" name="name" required placeholder="2026 봄 콘서트 1행사" />
        </div>
        <div className="field">
          <label htmlFor="venue">장소</label>
          <input id="venue" name="venue" placeholder="서울 데모홀" />
        </div>
        <div className="form-row">
          <div className="field">
            <label htmlFor="startsAt">시작 시각</label>
            <input id="startsAt" name="startsAt" type="datetime-local" required />
          </div>
          <div className="field">
            <label htmlFor="gateOpensAt">개찰 시각 (선택)</label>
            <input id="gateOpensAt" name="gateOpensAt" type="datetime-local" />
          </div>
        </div>
        <p className="hint-text">
          개찰 시각을 지정하면 그 전에는 입장이 거부됩니다. 재입장·양도·얼굴 정책은 만든 뒤 설정 탭에서
          조정할 수 있어요.
        </p>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn-primary" type="submit" disabled={busy}>
          {busy ? '만드는 중…' : '행사 만들기'}
        </button>
      </form>
    </section>
  );
}
