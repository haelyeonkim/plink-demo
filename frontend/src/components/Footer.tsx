import { Link } from 'react-router-dom';

export default function Footer() {
  return (
    <footer className="footer">
      <Link className="logo" to="/">
        <img src="/logo.svg" alt="P-Link" height="22" />
      </Link>
      <p>Private by design. Priority by choice.</p>
      <small>&copy; 2026 P-Link. All rights reserved.</small>
    </footer>
  );
}
