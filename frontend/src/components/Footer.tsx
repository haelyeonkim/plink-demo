import { Link, useLocation } from 'react-router-dom';

export default function Footer() {
  // A gate terminal is a kiosk: every pixel below the camera is wasted, and there is
  // nowhere for a visitor to navigate to.
  const { pathname } = useLocation();
  if (pathname.startsWith('/tickets/gate')) return null;

  return (
    <footer className="footer">
      <Link className="logo" to="/">
        <img src="/logo.svg" alt="패스링크" height="22" />
      </Link>
      <p>Private by design. Priority by choice.</p>
      <small>&copy; 2026 Passlink. All rights reserved.</small>
    </footer>
  );
}
