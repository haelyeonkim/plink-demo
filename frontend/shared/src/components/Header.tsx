import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth';
import { useLocation } from 'react-router-dom';
import { AppLink as Link, useBrand, type BrandName } from '../scope';

/** The administration button's label, and so the one name a header need not repeat. */
const ADMIN = '관리자';

/** Each tool's header is its own: neither lists the other's console. */
const MENUS: Record<BrandName, Array<[string, string]>> = {
  site: [['/links', '링크 관리'], ['/tickets', '입장권 관리'], ['/guide', '사용자 가이드']],
  link: [['/links', '링크 관리'], ['/links/contents', '컨텐츠'], ['/guide', '사용자 가이드']],
  ticket: [['/tickets', '입장권 관리'], ['/guide', '사용자 가이드']],
};

/**
 * Screens a visitor or a gate terminal sees, rather than an operator. A ticket holder or
 * a link's recipient has no account here and a terminal is a kiosk, so none of them gets
 * site navigation or a sign-in button.
 */
export function isStandalone(pathname: string): boolean {
  if (pathname === '/tickets/gate') return true;
  // /s/{code} and /s/{issuer}/{code} - what a link's recipient opens.
  if (/^\/s\/[^/]+/.test(pathname)) return true;
  // Operator screens live under /tickets too, and they keep the site's navigation.
  if (pathname.startsWith('/tickets/admin') || pathname.startsWith('/tickets/sessions')) return false;
  // /tickets/{sessionId}/{token} - the holder's own page.
  return /^\/tickets\/[^/]+\/[^/]+/.test(pathname);
}

export default function Header() {
  const { session, loading, logout } = useAuth();
  const brand = useBrand();
  const menu = MENUS[brand.key];
  const { pathname } = useLocation();
  const standalone = isStandalone(pathname);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const accountBox = useRef<HTMLDivElement>(null);

  // A tap that navigates should also put the menu away.
  useEffect(() => { setOpen(false); setAccountOpen(false); }, [pathname]);
  // The account menu closes on a tap anywhere else, or on Escape.
  useEffect(() => {
    if (!accountOpen) return;
    const onDown = (event: PointerEvent) => {
      if (!accountBox.current?.contains(event.target as Node)) setAccountOpen(false);
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setAccountOpen(false); };
    window.addEventListener('pointerdown', onDown);
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('pointerdown', onDown); window.removeEventListener('keydown', onKey); };
  }, [accountOpen]);
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
        <span className="logo"><img src={brand.logo} alt={brand.name} height="28" /></span>
      </header>
    );
  }

  const who = session?.user?.name || session?.user?.email || '';
  const account = session?.user ? (
    <>
      {/* Administration is not part of the product menu: only an owner is shown it. */}
      {session.user.canAccounts && <Link className="admin-link" to="/admin">{ADMIN}</Link>}
      {/* An account literally named "관리자" would otherwise print the button's own
          label back at it. */}
      {/* One round button with the account's initial; the address and the way out are
          behind it, so a long address is never cut short in the header. */}
      <div className="account-box" ref={accountBox}>
        <button type="button" className="account-avatar" aria-haspopup="menu" aria-expanded={accountOpen}
          aria-label={`계정 메뉴 · ${session.user.email || who}`} onClick={() => setAccountOpen(value => !value)}>
          {[...(who || '?')][0].toUpperCase()}
        </button>
        {accountOpen && (
          <div className="account-menu" role="menu">
            <p className="account-menu-who">
              {who !== ADMIN && who !== session.user.email && <b>{who}</b>}
              <span>{session.user.email}</span>
            </p>
            <button type="button" role="menuitem" disabled={busy} onClick={handleLogout}>
              {busy ? '로그아웃 중…' : '로그아웃'}
            </button>
          </div>
        )}
      </div>
    </>
  ) : (
    <Link className="login" to={brand.login}>{loading ? '확인 중…' : '로그인'}</Link>
  );

  return (
    <>
      <header className="header">
        <Link className="logo" to={brand.home}>
          <img src={brand.logo} alt={brand.name} height="28" />
        </Link>
        <nav>
          {menu.map(([to, label]) => <Link key={to} to={to}>{label}</Link>)}
        </nav>
        <div className={`header-account${session?.user ? '' : ' header-account-guest'}`}>
          {account}
          {error && <span className="account-error" role="alert">{error}</span>}
        </div>
        <button className="menu-toggle" aria-expanded={open} aria-controls="mobile-menu"
          aria-label={open ? '메뉴 닫기' : '메뉴 열기'} onClick={() => setOpen(value => !value)}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
            aria-hidden="true" focusable="false">
            <path d={open ? 'M6 6l12 12M6 18L18 6' : 'M4 6h16M4 12h16M4 18h16'} />
          </svg>
        </button>
      </header>

      {open && (
        <div className="mobile-menu" id="mobile-menu">
          <nav>
            {menu.map(([to, label]) => <Link key={to} to={to}>{label}</Link>)}
          </nav>
          {session?.user && <div className="mobile-account">{account}</div>}
        </div>
      )}
    </>
  );
}
