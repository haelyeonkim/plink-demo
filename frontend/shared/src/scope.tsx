import { createContext, useContext, type AnchorHTMLAttributes } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

/**
 * The two front ends share one origin: the ticket app owns /tickets, the link app the
 * rest. A path inside the app on screen goes through its router; a path in the other
 * app is a page load, since that app's code is not in this bundle.
 */
export type AppName = 'link' | 'ticket';

export function appOf(path: string): AppName {
  return /^\/tickets(?:[/?#]|$)/.test(path) ? 'ticket' : 'link';
}

const Scope = createContext<AppName>('link');
export const AppScope = Scope.Provider;

export function useApp(): AppName {
  return useContext(Scope);
}

/**
 * How each tool presents itself: its own mark, home, sign-in page and console. Passlink
 * itself - the tool chooser, account administration, the guide - is the third.
 */
export type BrandName = 'site' | 'link' | 'ticket';

export interface Brand {
  key: BrandName;
  name: string;
  logo: string;
  home: string;
  login: string;
  /** Where a sign-in lands when nothing asked to be returned to. */
  console: string;
}

const BRANDS: Record<BrandName, Brand> = {
  site: { key: 'site', name: '패스링크', logo: '/logo.svg', home: '/', login: '/login', console: '/' },
  link: { key: 'link', name: '패스링크', logo: '/logo.svg', home: '/links', login: '/links/login', console: '/links' },
  ticket: { key: 'ticket', name: '패스링크 티켓', logo: '/logo-ticket.svg', home: '/tickets', login: '/tickets/login', console: '/tickets' },
};

export function brandOf(path: string): BrandName {
  if (appOf(path) === 'ticket') return 'ticket';
  return /^\/(?:links|s)(?:[/?#]|$)/.test(path) ? 'link' : 'site';
}

export function useBrand(): Brand {
  return BRANDS[brandOf(useLocation().pathname)];
}

type AppLinkProps = Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> & { to: string };

export function AppLink({ to, ...rest }: AppLinkProps) {
  const app = useApp();
  return appOf(to) === app ? <Link to={to} {...rest} /> : <a href={to} {...rest} />;
}

/** navigate(), for a destination that may belong to the other app. */
export function useGo(): (to: string, options?: { replace?: boolean }) => void {
  const app = useApp();
  const navigate = useNavigate();
  return (to, options) => {
    if (appOf(to) === app) navigate(to, options);
    else if (options?.replace) window.location.replace(to);
    else window.location.assign(to);
  };
}

/** Where to go after signing in: a path on this site, never another origin. */
export function safeNext(value: string | null): string | null {
  // Browsers drop tabs and newlines and read a backslash as a slash, so "/\t/evil" or
  // "/\\evil" would leave the site. Resolve it the way the browser will, and keep it only
  // if it stays on this origin.
  if (!value || !value.startsWith('/')) return null;
  try {
    const url = new URL(value, window.location.origin);
    return url.origin === window.location.origin ? url.pathname + url.search + url.hash : null;
  } catch {
    return null;
  }
}
