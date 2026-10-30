import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth';
import { useLocation } from 'react-router-dom';
import { AppLink as Link, useBrand, type BrandName } from '../scope';
import { useMenuFocus } from '../menuFocus';

/** A name some accounts carry that says nothing about who they are. */
const ADMIN = '관리자';

/**
 * Each tool's header is its own and names its own screens; the front page names the two
 * tools the way its cards do. The other tool is reached from the account menu.
 */
const MENUS: Record<BrandName, Array<[string, string]>> = {
  site: [['/links', '보호 링크'], ['/tickets', '입장권'], ['/guide', '가이드']],
  link: [['/links', '링크'], ['/links/contents', '컨텐츠'], ['/guide', '가이드']],
  ticket: [['/tickets', '이벤트'], ['/guide', '가이드']],
};

/** The menu entry the page belongs to: the longest one its path starts with. */
function currentEntry(menu: Array<[string, string]>, pathname: string): string | undefined {
  return menu.map(([to]) => to)
    .filter(to => pathname === to || pathname.startsWith(to + '/'))
    .sort((a, b) => b.length - a.length)[0];
}

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
  // /tickets/{sessionId}/{token} - the holder's own page - and /tickets/booth/{token},
  // a booth manager's, who has a passkey for that booth but no account on the site.
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
  const accountButton = useRef<HTMLButtonElement>(null);
  const accountMenu = useRef<HTMLDivElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const mobileMenu = useRef<HTMLDivElement>(null);

  // A tap that navigates should also put the menu away.
  useEffect(() => { setOpen(false); setAccountOpen(false); }, [pathname]);
  // The account menu closes on a tap anywhere else, or on Escape.
  useEffect(() => {
    if (!accountOpen) return;
    const onDown = (event: PointerEvent) => {
      if (!accountBox.current?.contains(event.target as Node)) setAccountOpen(false);
    };
    window.addEventListener('pointerdown', onDown);
    return () => window.removeEventListener('pointerdown', onDown);
  }, [accountOpen]);
  useMenuFocus(accountOpen, accountButton, accountMenu, () => setAccountOpen(false));
  useMenuFocus(open, menuButton, mobileMenu, () => setOpen(false));

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
  const current = currentEntry(menu, pathname);
  const user = session?.user;
  // Where else this account can go: the other tool it may use, and administration for
  // an owner. Neither belongs in a tool's own menu.
  const elsewhere: Array<[string, string]> = user ? [
    ...(brand.key === 'ticket' && user.canLinks ? [['/links', '보호 링크로 이동'] as [string, string]] : []),
    ...(brand.key === 'link' && user.canTickets ? [['/tickets', '입장권으로 이동'] as [string, string]] : []),
    ...(brand.key !== 'site' ? [['/', '패스링크 홈'] as [string, string]] : []),
    ...(user.canAccounts ? [['/admin', '계정 관리'] as [string, string]] : []),
  ] : [];
  const nav = menu.map(([to, label]) => (
    <Link key={to} to={to} aria-current={to === current ? 'page' : undefined}>{label}</Link>
  ));
  const account = session?.user ? (
    <>
      {/* One round button with the account's initial; the address, the other places the
          account may go and the way out are behind it. */}
      <div className="account-box" ref={accountBox}>
        <button ref={accountButton} type="button" className="account-avatar" aria-haspopup="menu" aria-expanded={accountOpen}
          aria-label={`계정 메뉴 · ${session.user.email || who}`} onClick={() => setAccountOpen(value => !value)}>
          {[...(who || '?')][0].toUpperCase()}
        </button>
        {accountOpen && (
          <div ref={accountMenu} className="account-menu" role="menu">
            <p className="account-menu-who">
              {who !== ADMIN && who !== session.user.email && <b>{who}</b>}
              <span>{session.user.email}</span>
            </p>
            {elsewhere.map(([to, label]) => <Link key={to} role="menuitem" to={to}>{label}</Link>)}
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
        <nav>{nav}</nav>
        <div className={`header-account${session?.user ? '' : ' header-account-guest'}`}>
          {account}
          {error && <span className="account-error" role="alert">{error}</span>}
        </div>
        <button ref={menuButton} className="menu-toggle" aria-expanded={open} aria-controls="mobile-menu"
          aria-label={open ? '메뉴 닫기' : '메뉴 열기'} onClick={() => setOpen(value => !value)}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
            aria-hidden="true" focusable="false">
            <path d={open ? 'M6 6l12 12M6 18L18 6' : 'M4 6h16M4 12h16M4 18h16'} />
          </svg>
        </button>
      </header>

      {open && (
        <div ref={mobileMenu} className="mobile-menu" id="mobile-menu">
          <nav>{nav}</nav>
          {user && (
            <div className="mobile-account">
              <p className="account-menu-who">
                {who !== ADMIN && who !== user.email && <b>{who}</b>}
                <span>{user.email}</span>
              </p>
              {elsewhere.map(([to, label]) => <Link key={to} to={to}>{label}</Link>)}
              <button type="button" disabled={busy} onClick={handleLogout}>
                {busy ? '로그아웃 중…' : '로그아웃'}
              </button>
              {error && <span className="account-error" role="alert">{error}</span>}
            </div>
          )}
        </div>
      )}
    </>
  );
}
