import { AppLink as Link } from '@shared/scope';
import Hero from './Hero';
import TicketIntro from './TicketIntro';

/**
 * Passlink's front door, and the one page that explains it: the two tools side by side,
 * then each in turn. The tools themselves open straight on their consoles.
 */
export default function Home() {
  return (
    <>
    <section className="tools-home">
      <div className="tools-intro">
        <p className="eyebrow center"><span></span> PASSLINK</p>
        <h1>한 사람에게만 열리는<br /><strong>링크와 입장권</strong></h1>
        <p className="tools-lead">
          처음 등록한 패스키로 받는 사람이 정해집니다. 쓰려는 도구를 고르세요.
        </p>
      </div>

      <div className="tools-grid">
        <article className="tool-card tool-link">
          <img src="/logo-private.svg" alt="패스링크 프라이빗" height="30" />
          <h2>보호 링크</h2>
          <p>문서와 작품을 수신자에게만 열리는 링크로 보내고, 누가 언제 열었는지 확인하세요.</p>
          <ul>
            <li>수신자별 링크 발급</li>
            <li>패스키 수신 확정</li>
            <li>열람 기록</li>
          </ul>
          <div className="tool-actions">
            <Link className="tool-primary" to="/links">링크 관리 열기</Link>
            <Link className="tool-secondary" to="/links/new">새 링크 만들기</Link>
          </div>
        </article>

        <article className="tool-card tool-ticket">
          <img src="/logo-ticket.svg" alt="패스링크 티켓" height="30" />
          <h2>입장권</h2>
          <p>이벤트 입장권을 한 사람씩 발급하고, 게이트에서 입장을 확인하세요.</p>
          <ul>
            <li>본인만 열리는 입장권</li>
            <li>게이트 QR·얼굴 확인</li>
            <li>쿠폰과 혼잡도</li>
          </ul>
          <div className="tool-actions">
            <Link className="tool-primary" to="/tickets">입장권 관리 열기</Link>
            <Link className="tool-secondary" to="/tickets/sessions/new">이벤트 만들기</Link>
          </div>
        </article>
      </div>
    </section>

    <div className="intro-part" id="links-intro">
      <p className="intro-label">보호 링크</p>
      <Hero />
    </div>
    <div className="intro-part ticket-theme" id="tickets-intro">
      <p className="intro-label">입장권</p>
      <TicketIntro />
    </div>
    </>
  );
}
