import RequireAdmin from './components/RequireAdmin';
import { AuthProvider } from './auth';
import Login from './components/Login';
import { BrowserRouter, Routes, Route, Navigate, useParams, useSearchParams } from 'react-router-dom';
import Header from './components/Header';
import Hero from './components/Hero';
import LinkAdmin from './components/LinkAdmin';
import LinkCreate from './components/LinkCreate';
import ContentCreate from './components/ContentCreate';
import ContentList from './components/ContentList';
import AdminConsole from './components/AdminConsole';
import AccessLink from './components/AccessLink';
import Guide from './components/Guide';
import Privacy from './components/Privacy';
import TicketPage from './components/TicketPage';
import GateScanner from './components/GateScanner';
import GateSetup from './components/GateSetup';
import TicketAdmin from './components/TicketAdmin';
import SessionCreate from './components/SessionCreate';
import Footer from './components/Footer';

/** Old per-link bookmarks still open the link, now inside the console's list tab. */
function LegacyLinkDetail() {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/links?tab=issue&link=${id}`} replace />;
}

/** Preserve bookmarks from the first combined list/editor screen. */
function LegacyContentCreate() {
  const [params] = useSearchParams();
  const id = Number(params.get('id')) || null;
  return <Navigate to={id ? `/contents/${id}/edit` : '/contents/new'} replace />;
}

export default function App() {
  return (
    <AuthProvider>
    <BrowserRouter>
      <Header />
      <main>
        <Routes>
          <Route path="/" element={<Hero />} />
          <Route path="/links" element={<RequireAdmin scope="canLinks"><LinkAdmin /></RequireAdmin>} />
          {/* One link, at its own address, so it can be linked to and come back to. */}
          <Route path="/links/:linkId" element={<RequireAdmin scope="canLinks"><LinkAdmin /></RequireAdmin>} />
          <Route path="/links/new" element={<RequireAdmin scope="canLinks"><LinkCreate /></RequireAdmin>} />
          <Route path="/contents" element={<RequireAdmin scope="canLinks"><ContentList /></RequireAdmin>} />
          <Route path="/contents/new" element={<RequireAdmin scope="canLinks"><ContentCreate /></RequireAdmin>} />
          <Route path="/contents/:contentId/edit" element={<RequireAdmin scope="canLinks"><ContentCreate /></RequireAdmin>} />
          <Route path="/content/create" element={<RequireAdmin scope="canLinks"><LegacyContentCreate /></RequireAdmin>} />
          {/* Administration sits on its own path, away from the product consoles. */}
          <Route path="/admin" element={<RequireAdmin scope="canAccounts"><AdminConsole /></RequireAdmin>} />
          <Route path="/accounts" element={<Navigate to="/admin" replace />} />
          <Route path="/create" element={<Navigate to="/links/new" replace />} />
          <Route path="/manage" element={<Navigate to="/links" replace />} />
          <Route path="/manage/:id" element={<LegacyLinkDetail />} />
          <Route path="/s/:shortCode" element={<AccessLink />} />
          {/* Namespaced by the issuing account: /s/{issuer}/{code}. */}
          <Route path="/s/:slug/:shortCode" element={<AccessLink />} />
          <Route path="/tickets/gate" element={<GateScanner />} />
          <Route path="/tickets/gate/:setupToken" element={<GateSetup />} />
          <Route path="/tickets/admin" element={<RequireAdmin scope="canTickets"><TicketAdmin /></RequireAdmin>} />
          <Route path="/tickets/admin/:sessionId"
            element={<RequireAdmin scope="canTickets"><TicketAdmin /></RequireAdmin>} />
          <Route path="/tickets/sessions/new" element={<RequireAdmin scope="canTickets"><SessionCreate /></RequireAdmin>} />
          <Route path="/tickets/:sessionId/:token" element={<TicketPage />} />
          <Route path="/stats" element={<Navigate to="/links" replace />} />
          <Route path="/guide" element={<Guide />} />
          <Route path="/privacy" element={<Privacy />} />
          <Route path="/login" element={<Login />} />
        </Routes>
      </main>
      <Footer />
    </BrowserRouter>
    </AuthProvider>
  );
}
