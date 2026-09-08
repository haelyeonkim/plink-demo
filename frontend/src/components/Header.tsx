import { Link } from 'react-router-dom';

export default function Header() {
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
      <Link className="login" to="/login">로그인</Link>
    </header>
  );
}
