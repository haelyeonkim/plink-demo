import { useLocation } from 'react-router-dom';
import { AppLink as Link, useBrand } from '../scope';
import { isStandalone } from './Header';

export default function Footer() {
  // A gate terminal is a kiosk: every pixel below the camera is wasted, and there is
  // nowhere for a visitor to navigate to.
  const { pathname } = useLocation();
  const brand = useBrand();
  if (pathname.startsWith('/tickets/gate')) return null;

  // A holder's ticket is one screen held in a queue. It keeps the one link that has to
  // stay reachable - what happens to a face, and to an address - and drops the rest so
  // the ticket itself does not need scrolling to.
  if (isStandalone(pathname)) {
    return (
      <footer className="footer footer-bare">
        <Link className="footer-link" to="/privacy">개인정보처리방침</Link>
      </footer>
    );
  }

  return (
    <footer className="footer">
      <Link className="logo" to={brand.home}>
        <img src={brand.logo} alt={brand.name} height="22" />
      </Link>
      <p>Private by design. Priority by choice.</p>
      <Link className="footer-link" to="/privacy">개인정보처리방침</Link>
      <small>&copy; 2026 Passlink. All rights reserved.</small>
    </footer>
  );
}
