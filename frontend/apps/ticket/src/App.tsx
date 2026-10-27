import RequireAdmin from '@shared/components/RequireAdmin';
import { AuthProvider } from '@shared/auth';
import { AppScope } from '@shared/scope';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Header from '@shared/components/Header';
import Footer from '@shared/components/Footer';
import TicketPage from './components/TicketPage';
import GateScanner from './components/GateScanner';
import GateSetup from './components/GateSetup';
import TicketAdmin from './components/TicketAdmin';
import SessionCreate from './components/SessionCreate';

/** Everything under /tickets: the holder's ticket, the gate terminal and the organiser console. */
export default function App() {
  return (
    <AppScope value="ticket">
    <AuthProvider>
    <BrowserRouter>
      <Header />
      <main>
        <Routes>
          <Route path="/tickets/gate" element={<GateScanner />} />
          <Route path="/tickets/gate/:setupToken" element={<GateSetup />} />
          <Route path="/tickets/admin" element={<RequireAdmin scope="canTickets"><TicketAdmin /></RequireAdmin>} />
          <Route path="/tickets/admin/:sessionId"
            element={<RequireAdmin scope="canTickets"><TicketAdmin /></RequireAdmin>} />
          <Route path="/tickets/sessions/new" element={<RequireAdmin scope="canTickets"><SessionCreate /></RequireAdmin>} />
          <Route path="/tickets/:sessionId/:token" element={<TicketPage />} />
        </Routes>
      </main>
      <Footer />
    </BrowserRouter>
    </AuthProvider>
    </AppScope>
  );
}
