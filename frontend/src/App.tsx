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

export default function App() {
  return (
    <AuthProvider>
    <BrowserRouter>
      <Header />
      <main>
        <Routes>
          <Route path="/" element={<Hero />} />
          <Route path="/create" element={<CreateLink />} />
          <Route path="/manage" element={<LinkList />} />
          <Route path="/manage/:id" element={<LinkDetail />} />
          <Route path="/s/:shortCode" element={<AccessLink />} />
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
