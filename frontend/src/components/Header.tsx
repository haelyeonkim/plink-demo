import { useState } from 'react';
import { useAuth } from '../auth';
import { Link } from 'react-router-dom';

export default function Header() {
  const { session, loading, logout } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function handleLogout() {
    setBusy(true);
    setError('');
    try { await logout(); }
    catch { setError('로그아웃에 실패했습니다. 다시 시도해 주세요.'); }
    finally { setBusy(false); }
  }
  return (
    <header className="header">
      <Link className="logo" to="/">
        <img src="/logo.svg" alt="P-Link" height="28" />
      </Link>
      <nav>
        <Link to="/create">링크 생성</Link>
        <Link to="/manage">링크 관리</Link>
        <Link to="/stats">링크 통계</Link>
        <Link to="/guide">사용자 가이드</Link>
      </nav>
      <div className="header-account">
        {session?.user ? <>
          <span className="account-name" title={session.user.email || ''}>{session.user.name || session.user.email}</span>
          <button className="login" disabled={busy} onClick={handleLogout}>{busy ? '로그아웃 중…' : '로그아웃'}</button>
        </> : <Link className="login" to="/login">{loading ? '확인 중…' : '로그인'}</Link>}
        {error && <span className="account-error" role="alert">{error}</span>}
      </div>
    </header>
  );
}
