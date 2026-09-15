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
            <aside className="login-privacy" aria-labelledby="login-privacy-title">
              <h2 id="login-privacy-title">로그인 정보 이용 안내</h2>
              <p>Google에서 이름, 이메일, 계정 식별자를 받아 로그인 확인, 계정 표시, 내 링크 관리에 이용합니다. Google 비밀번호는 받지 않습니다.</p>
              <p>로그인 동안 서버 세션에 사용자 정보와 인증 토큰을 보관하고, 브라우저에는 로그인 유지용 쿠키와 화면 표시용 이름·이메일을 보관합니다. 로그아웃하거나 세션이 만료되면 해당 로그인 세션은 종료됩니다.</p>
              <p>링크를 만들면 소유자 확인을 위해 Google 계정 식별자를 링크와 함께 저장합니다. 이 정보는 로그아웃만으로 삭제되지 않습니다.</p>
              <p>Google 계정의 로그인 상태는 별도로 유지됩니다.</p>
              <p><Link to="/privacy">개인정보처리방침 보기</Link></p>
            </aside>
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
