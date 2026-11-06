import type { ReactNode } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';

/** The administration header, with 데이터 as the tab in view, around the data screens. */
export default function DataLayout() {
  return (
    <section className="page-section page-wide data-page">
      <p className="eyebrow"><span></span> ADMIN</p>
      <h2>관리자</h2>
      <nav className="tabs" aria-label="관리 메뉴">
        <Link to="/admin">계정</Link>
        <Link to="/admin?tab=mail">메일 발송</Link>
        <Link to="/admin/data" className="active" aria-current="page">데이터</Link>
      </nav>
      <Outlet />
    </section>
  );
}

const SCREENS: Array<[string, string]> = [
  ['/admin/data', '개요'],
  ['/admin/data/logs', '로그 살펴보기'],
  ['/admin/data/exports', '내보내기'],
];

/** Which data screen is open, and that screen's own controls beside it. */
export function DataBar({ children }: { children?: ReactNode }) {
  return (
    <div className="data-bar">
      <nav className="data-switch" aria-label="데이터 화면">
        {SCREENS.map(([to, label]) => (
          <NavLink key={to} to={to} end>{label}</NavLink>
        ))}
      </nav>
      {children && <div className="data-bar-actions">{children}</div>}
    </div>
  );
}
