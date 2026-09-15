import RequireAdmin from './components/RequireAdmin';
import { AuthProvider } from './auth';
import Login from './components/Login';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Header from './components/Header';
import Hero from './components/Hero';
import CreateLink from './components/CreateLink';
import LinkList from './components/LinkList';
import LinkDetail from './components/LinkDetail';
import AccessLink from './components/AccessLink';
import Placeholder from './components/Placeholder';
import Footer from './components/Footer';
import Privacy from './components/Privacy';
import Guide from './components/Guide';
import ContentCreate from './components/ContentCreate';

export default function App() {
  return (
    <AuthProvider>
    <BrowserRouter>
      <Header />
      <main>
        <Routes>
          <Route path="/" element={<Hero />} />
          <Route path="/create" element={<RequireAdmin><CreateLink /></RequireAdmin>} />
          <Route path="/content/create" element={<RequireAdmin><ContentCreate /></RequireAdmin>} />
          <Route path="/manage" element={<RequireAdmin><LinkList /></RequireAdmin>} />
          <Route path="/manage/:id" element={<RequireAdmin><LinkDetail /></RequireAdmin>} />
          <Route path="/s/:shortCode" element={<AccessLink />} />
          <Route path="/stats" element={<Placeholder title="링크 통계" desc="열람 여부와 시간을 확인해 중요한 순간을 놓치지 마세요." />} />
          <Route path="/guide" element={<Guide />} />
          <Route path="/login" element={<Login />} />
          <Route path="/privacy" element={<Privacy />} />
        </Routes>
      </main>
      <Footer />
    </BrowserRouter>
    </AuthProvider>
  );
}
