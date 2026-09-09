import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth';

export default function RequireAdmin({ children }: { children: ReactNode }) {
  const { session, loading, error, refresh } = useAuth();
  if (loading) return <p className="loading">로그인 상태를 확인하고 있어요.</p>;
  if (error) return <section className="page-section"><p role="alert">{error}</p><button className="btn-primary" onClick={refresh}>다시 시도</button></section>;
  return session?.user ? <>{children}</> : <Navigate to="/login" replace />;
}
