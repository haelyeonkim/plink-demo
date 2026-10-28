import RequireAdmin from '@shared/components/RequireAdmin';
import { AuthProvider } from '@shared/auth';
import { AppScope } from '@shared/scope';
import Login from '@shared/components/Login';
import { BrowserRouter, Routes, Route, Navigate, useLocation, useParams, useSearchParams } from 'react-router-dom';
import Header from '@shared/components/Header';
import Home from './components/Home';
import LinkAdmin from './components/LinkAdmin';
import LinkCreate from './components/LinkCreate';
import ContentCreate from './components/ContentCreate';
import ContentList from './components/ContentList';
import AdminConsole from './components/AdminConsole';
import AdminArtworkList from './components/AdminArtworkList';
import AccessLink from './components/AccessLink';
import Guide from './components/Guide';
import Privacy from './components/Privacy';
import Footer from '@shared/components/Footer';

/** Old per-link bookmarks still open the link, now inside the console's list tab. */
function LegacyLinkDetail() {
  const { id } = useParams<{ id: string }>();
  return <Navigate to={`/links/${id}?tab=issue`} replace />;
}

/** Preserve bookmarks from the first combined list/editor screen. */
function LegacyContentCreate() {
  const [params] = useSearchParams();
  const id = Number(params.get('id')) || null;
  return <Navigate to={id ? `/links/contents/${id}/edit` : '/links/contents/new'} replace />;
}

/** Addresses from before contents moved under /links. */
function Moved({ to }: { to: (params: Record<string, string | undefined>) => string }) {
  const params = useParams();
  const { search } = useLocation();
  return <Navigate to={to(params) + search} replace />;
}

export default function App() {
  return (
    <AppScope value="link">
    <AuthProvider>
    <BrowserRouter>
      <Header />
      <main>
        <Routes>
          {/* Passlink: what it is, and what belongs to the site rather than to either tool. */}
          <Route path="/" element={<Home />} />
          <Route path="/admin" element={<RequireAdmin scope="canAccounts"><AdminConsole /></RequireAdmin>} />
          <Route path="/admin/artworks" element={<RequireAdmin scope="canAccounts"><AdminArtworkList /></RequireAdmin>} />
          <Route path="/guide" element={<Guide />} />
          <Route path="/privacy" element={<Privacy />} />
          <Route path="/login" element={<Login />} />
          {/* The link tool opens on its console, like the ticket tool under /tickets. */}
          <Route path="/links" element={<RequireAdmin scope="canLinks"><LinkAdmin /></RequireAdmin>} />
          <Route path="/links/login" element={<Login />} />
          <Route path="/links/new" element={<RequireAdmin scope="canLinks"><LinkCreate /></RequireAdmin>} />
          <Route path="/links/contents" element={<RequireAdmin scope="canLinks"><ContentList /></RequireAdmin>} />
          <Route path="/links/contents/new" element={<RequireAdmin scope="canLinks"><ContentCreate /></RequireAdmin>} />
          <Route path="/links/contents/:contentId/edit" element={<RequireAdmin scope="canLinks"><ContentCreate /></RequireAdmin>} />
          {/* One link, at its own address, so it can be linked to and come back to. */}
          <Route path="/links/:linkId" element={<RequireAdmin scope="canLinks"><LinkAdmin /></RequireAdmin>} />
          {/* What a recipient opens: issued addresses never move. /s/{issuer}/{code} is namespaced. */}
          <Route path="/s/:shortCode" element={<AccessLink />} />
          <Route path="/s/:slug/:shortCode" element={<AccessLink />} />
          {/* Older addresses. */}
          <Route path="/links/admin" element={<Moved to={() => '/links'} />} />
          <Route path="/links/admin/:linkId" element={<Moved to={p => `/links/${p.linkId}`} />} />
          <Route path="/contents" element={<Moved to={() => '/links/contents'} />} />
          <Route path="/contents/new" element={<Moved to={() => '/links/contents/new'} />} />
          <Route path="/contents/:contentId/edit" element={<Moved to={p => `/links/contents/${p.contentId}/edit`} />} />
          <Route path="/content/create" element={<LegacyContentCreate />} />
          <Route path="/accounts" element={<Navigate to="/admin" replace />} />
          <Route path="/create" element={<Navigate to="/links/new" replace />} />
          <Route path="/manage" element={<Navigate to="/links" replace />} />
          <Route path="/manage/:id" element={<LegacyLinkDetail />} />
          <Route path="/stats" element={<Navigate to="/links" replace />} />
        </Routes>
      </main>
      <Footer />
    </BrowserRouter>
    </AuthProvider>
    </AppScope>
  );
}
