import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { signIn, useAuth } from '../auth';

export default function Login() {
  const { session, loading, error, refresh } = useAuth();
  const [params] = useSearchParams();
  const navigate = useNavigate();

  // Signed in already, or just now: the login page has nothing left to say.
  useEffect(() => {
    if (session?.user) navigate('/', { replace: true });
  }, [session?.user, navigate]);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [loginError, setLoginError] = useState('');

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setLoginError('');
    try {
      await signIn(email, password);
      await refresh();
    } catch (err) {
      setLoginError(err instanceof Error ? err.message : '로그인하지 못했습니다.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="page-section login-page">
      <div className="access-card login-card">
        <img src="/logo.svg" alt="패스링크" height="32" />
        <p className="eyebrow center">WELCOME TO PASSLINK</p>
        <h1>{session?.user ? '로그인되었습니다' : '반가워요, 패스링크입니다'}</h1>

        {loading ? <p role="status">로그인 상태를 확인하고 있어요.</p> : error ? (
          <>
            <p className="error-text" role="alert">{error}</p>
            <button className="btn-primary" onClick={refresh}>다시 시도</button>
          </>
        ) : session?.user ? (
          <p role="status">{session.user.name || session.user.email}님, 환영합니다.</p>
        ) : (
          <>
            {params.has('error') && (
              <p className="error-text" role="alert">Google 로그인을 완료하지 못했습니다. 다시 시도해 주세요.</p>
            )}
            {loginError && <p className="error-text" role="alert">{loginError}</p>}

            <form onSubmit={submit}>
              <div className="field">
                <label htmlFor="login-email">이메일</label>
                <input id="login-email" type="email" autoComplete="username" required
                  value={email} onChange={e => setEmail(e.target.value)} placeholder="admin@example.com" />
              </div>
              <div className="field">
                <label htmlFor="login-password">비밀번호</label>
                <input id="login-password" type="password" autoComplete="current-password" required
                  value={password} onChange={e => setPassword(e.target.value)} />
              </div>
              <button className="btn-primary" type="submit" disabled={busy}>
                {busy ? '로그인 중…' : '로그인'}
              </button>
            </form>

            {session?.googleEnabled && (
              <>
                <p className="login-note">또는</p>
                <a className="google-login" href="/oauth2/authorization/google">Google로 계속하기</a>
              </>
            )}
            <Link className="login-home" to="/">홈으로 돌아가기</Link>
          </>
        )}
      </div>

      {/* What signing in actually hands over, next to the form that does it. */}
      <aside className="login-privacy" aria-labelledby="login-privacy-title">
        <h2 id="login-privacy-title">로그인 정보 이용 안내</h2>
        <p>
          이메일과 비밀번호로 로그인하면 서버는 이메일과 비밀번호 <b>해시</b>만 보관합니다. 비밀번호
          원본은 저장하지 않습니다.
        </p>
        {session?.googleEnabled && (
          <p>
            Google로 로그인하면 이름·이메일·계정 식별자를 받아 로그인 확인과 소유자 표시에만
            사용합니다. Google 비밀번호는 받지 않습니다.
          </p>
        )}
        <p>
          로그인 상태는 서버 세션과 브라우저 쿠키로 유지되며, 로그아웃하거나 세션이 만료되면
          종료됩니다. 만든 링크·행사는 로그아웃만으로 지워지지 않습니다.
        </p>
        <p><Link to="/privacy">개인정보처리방침 보기</Link></p>
      </aside>
    </section>
  );
}
