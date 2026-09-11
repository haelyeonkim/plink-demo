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
import TicketPage from './components/TicketPage';
import GateScanner from './components/GateScanner';
import TicketAdmin from './components/TicketAdmin';
import Footer from './components/Footer';

export default function App() {
  return (
    <AuthProvider>
    <BrowserRouter>
      <Header />
      <main>
        <Routes>
          <Route path="/" element={<Hero />} />
          <Route path="/create" element={<RequireAdmin><CreateLink /></RequireAdmin>} />
          <Route path="/manage" element={<RequireAdmin><LinkList /></RequireAdmin>} />
          <Route path="/manage/:id" element={<RequireAdmin><LinkDetail /></RequireAdmin>} />
          <Route path="/s/:shortCode" element={<AccessLink />} />
          <Route path="/t/:sessionId/:token" element={<TicketPage />} />
          <Route path="/gate" element={<GateScanner />} />
          <Route path="/manage/tickets" element={<RequireAdmin><TicketAdmin /></RequireAdmin>} />
          <Route path="/stats" element={<Placeholder title="링크 통계" desc="열람 여부와 시간을 확인해 중요한 순간을 놓치지 마세요." />} />
          <Route path="/guide" element={<Placeholder title="사용자 가이드" desc="P-Link의 모든 기능을 쉽고 빠르게 알아보세요." />} />
          <Route path="/login" element={<Login />} />
        </Routes>
      </main>
      <Footer />
    </BrowserRouter>
    </AuthProvider>
  );
}
