import { useLocation } from 'react-router-dom';
import { AppLink as Link, useBrand } from '../scope';
import { isStandalone } from './Header';

export default function Footer() {
  // A gate terminal is a kiosk: every pixel below the camera is wasted, and there is
  // nowhere for a visitor to navigate to.
  const { pathname } = useLocation();
  const brand = useBrand();
  if (pathname.startsWith('/tickets/gate')) return null;

  // A holder's ticket is one screen held in a queue: nothing below it to scroll to.
  if (isStandalone(pathname)) return null;

  return (
    <footer className="footer">
      <Link className="logo" to={brand.home}>
        <img src={brand.logo} alt={brand.name} height="22" />
      </Link>
      <p>Private by design. Priority by choice.</p>
      <small>&copy; 2026 Passlink. All rights reserved.</small>
    </footer>
  );
}
