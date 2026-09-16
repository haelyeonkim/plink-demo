import { useEffect, useState } from 'react';
import { useAuth } from '../auth';
import { Link, useLocation } from 'react-router-dom';

const LINKS: Array<[string, string]> = [
  ['/links', '링크 관리'],
  ['/tickets/admin', '입장권 관리'],
  ['/guide', '사용자 가이드'],
];

/**
 * Screens a visitor or a gate terminal sees, rather than an operator. A ticket holder
 * has no account here and a terminal is a kiosk, so neither gets site navigation or a
 * sign-in button.
 */
function isStandalone(pathname: string): boolean {
  if (pathname === '/tickets/gate') return true;
  // /tickets/{sessionId}/{token} - the holder's own page.
  return /^\/tickets\/[^/]+\/[^/]+/.test(pathname);
}

export default function Header() {
  const { session, loading, logout } = useAuth();
  const { pathname } = useLocation();
  const standalone = isStandalone(pathname);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);

  // A tap that navigates should also put the menu away.
  useEffect(() => { setOpen(false); }, [pathname]);
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  async function handleLogout() {
    setBusy(true);
    setError('');
    try { await logout(); }
    catch { setError('로그아웃에 실패했습니다. 다시 시도해 주세요.'); }
    finally { setBusy(false); }
  }

  if (standalone) {
    return (
      <header className="header header-plain">
        <span className="logo"><img src="/logo.svg" alt="패스링크" height="28" /></span>
      </header>
    );
  }

  const account = session?.user ? (
    <>
      {/* Administration is not part of the product menu: only an owner is shown it. */}
      {session.user.canAccounts && <Link className="admin-link" to="/admin">관리자</Link>}
      <span className="account-name" title={session.user.email || ''}>
        {session.user.name || session.user.email}
      </span>
      <button className="login" disabled={busy} onClick={handleLogout}>
        {busy ? '로그아웃 중…' : '로그아웃'}
      </button>
    </>
  ) : (
    <Link className="login" to="/login">{loading ? '확인 중…' : '로그인'}</Link>
  );

  return (
    <>
      <header className="header">
        <Link className="logo" to="/">
          <img src="/logo.svg" alt="패스링크" height="28" />
        </Link>
        <nav>
          {LINKS.map(([to, label]) => <Link key={to} to={to}>{label}</Link>)}
        </nav>
        <div className="header-account">
          {account}
          {error && <span className="account-error" role="alert">{error}</span>}
        </div>
        <button className="menu-toggle" aria-expanded={open} aria-controls="mobile-menu"
          aria-label={open ? '메뉴 닫기' : '메뉴 열기'} onClick={() => setOpen(value => !value)}>
          {open ? '✕' : '☰'}
        </button>
      </header>

      {open && (
        <div className="mobile-menu" id="mobile-menu">
          <nav>
            {LINKS.map(([to, label]) => <Link key={to} to={to}>{label}</Link>)}
          </nav>
          <div className="mobile-account">{account}</div>
        </div>
      )}
    </>
  );
}
