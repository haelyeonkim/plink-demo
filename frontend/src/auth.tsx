import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

export interface Session {
  googleEnabled: boolean;
  csrfToken: string;
  csrfHeader: string;
  user: {
    name: string | null;
    email: string | null;
    /** Which consoles this account may open. The server enforces the same thing. */
    canLinks: boolean;
    canTickets: boolean;
    canAccounts: boolean;
  } | null;
}

export async function fetchSession(): Promise<Session> {
  const res = await fetch('/api/auth/session', { cache: 'no-store' });
  if (!res.ok) throw new Error('로그인 상태를 확인하지 못했습니다.');
  return res.json();
}

/** Local administrator sign-in, for deployments without a Google tenancy. */
export async function signIn(email: string, password: string): Promise<void> {
  const res = await mutate('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || '로그인하지 못했습니다.');
  }
}

export async function mutate(url: string, init: RequestInit): Promise<Response> {
  const session = await fetchSession();
  const headers = new Headers(init.headers);
  headers.set(session.csrfHeader, session.csrfToken);
  return fetch(url, { ...init, headers });
}

const AuthContext = createContext<{
  session: Session | null;
  loading: boolean;
  error: string;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
} | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  async function refresh() {
    setLoading(true);
    setError('');
    try { setSession(await fetchSession()); }
    catch { setError('서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'); }
    finally { setLoading(false); }
  }
  async function logout() {
    const res = await mutate('/api/auth/logout', { method: 'POST' });
    if (!res.ok) throw new Error('로그아웃하지 못했습니다. 다시 시도해 주세요.');
    setSession(null);
    await refresh();
  }
  useEffect(() => { void refresh(); }, []);
  return <AuthContext.Provider value={{ session, loading, error, refresh, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('AuthProvider is required');
  return value;
}
