import { createContext, useContext, type AnchorHTMLAttributes } from 'react';
import { Link, useNavigate } from 'react-router-dom';

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
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\') ? value : null;
}
