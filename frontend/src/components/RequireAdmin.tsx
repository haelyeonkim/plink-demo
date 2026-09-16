import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth';

/** The scope a screen needs, as the session reports it. */
type Scope = 'canLinks' | 'canTickets' | 'canAccounts';

export default function RequireAdmin({ children, scope }: { children: ReactNode; scope?: Scope }) {
  const { session, loading, error, refresh } = useAuth();
  if (loading) return <p className="loading">로그인 상태를 확인하고 있어요.</p>;
  if (error) return <section className="page-section"><p role="alert">{error}</p><button className="btn-primary" onClick={refresh}>다시 시도</button></section>;
  if (!session?.user) return <Navigate to="/login" replace />;
  // Every menu entry stays visible; the screen behind it is what says no. The API
  // refuses the same request anyway - this turns a wall of 403s into one sentence.
  if (scope && !session.user[scope]) {
    return (
      <section className="page-section">
        <p className="eyebrow"><span></span> NO ACCESS</p>
        <h2>권한이 없어요</h2>
        <div className="tab-panel">
          <p className="section-desc">
            이 화면을 열 권한이 계정에 없습니다. 계정 관리 권한이 있는 관리자에게 요청해 주세요.
          </p>
          <p className="hint-text">로그인 계정: {session.user.email}</p>
        </div>
      </section>
    );
  }
  return <>{children}</>;
}
