import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth';

export default function Login() {
  const { session, loading, error, refresh } = useAuth();
  const [params] = useSearchParams();
  return (
    <section className="page-section login-page">
      <div className="access-card login-card">
        <img src="/logo.svg" alt="P-Link" height="32" />
        <p className="eyebrow center">WELCOME TO P-LINK</p>
        <h1>{session?.user ? '로그인되었습니다' : '반가워요, P-Link입니다'}</h1>
        <p className="login-description">Google 계정으로 간편하게 시작하세요.</p>
        {loading ? <p role="status">로그인 상태를 확인하고 있어요.</p> : error ? (
          <><p className="error-text" role="alert">{error}</p><button className="btn-primary" onClick={refresh}>다시 시도</button></>
        ) : session?.user ? (
          <><p>{session.user.name || session.user.email}님, 환영합니다.</p><Link className="btn-primary" to="/manage">링크 관리로 이동</Link></>
        ) : (
          <>
            {params.has('error') && <p className="error-text" role="alert">Google 로그인을 완료하지 못했습니다. 다시 시도해 주세요.</p>}
            {session?.googleEnabled ? (
              <a className="google-login" href="/oauth2/authorization/google">Google로 계속하기</a>
            ) : (
              <><button className="google-login" disabled>Google로 계속하기</button><p className="login-note">Google 로그인을 준비 중입니다. 잠시 후 다시 방문해 주세요.</p></>
            )}
            <Link className="login-home" to="/">홈으로 돌아가기</Link>
          </>
        )}
      </div>
    </section>
  );
}
