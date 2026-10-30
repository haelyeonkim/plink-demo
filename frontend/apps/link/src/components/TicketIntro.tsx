import { AppLink as Link } from '@shared/scope';

/** The ticket tool, as the front page explains it. Styled under .ticket-theme in its own colour. */
export default function TicketIntro() {
  return (
    <>
      <section className="th-hero">
        <div className="th-copy">
          <p className="eyebrow"><span></span> PASSKEY TICKETING</p>
          <h2 className="th-title">
            입장권은 그 사람에게만,<br />
            <strong>입장은 한 번에.</strong>
          </h2>
          <p className="th-lead">
            처음 등록한 패스키에 주인이 정해지는 입장권.<br className="th-wide" />
            게이트에서 바로 확인하고, 쿠폰과 혼잡도까지 한 곳에서 운영하세요.
          </p>
          <div className="th-actions">
            <Link className="th-primary" to="/tickets/sessions/new">이벤트 만들기</Link>
            <Link className="th-secondary" to="/tickets">입장권 관리</Link>
          </div>
        </div>

        <div className="th-art" aria-hidden="true">
          <div className="th-ticket">
            <div className="th-ticket-head">
              <small>ADMISSION</small>
              <b>2026 가을 아트페어</b>
              <span>11월 8일 (일) · 10:00 – 18:00</span>
            </div>
            <div className="th-ticket-body">
              <div className="th-qr">
                {Array.from({ length: 49 }, (_, i) => (
                  <i key={i} className={(i * 7 + (i >> 2)) % 3 === 0 || [0, 1, 7, 8, 5, 6, 12, 13, 35, 36, 42, 43].includes(i) ? 'on' : ''} />
                ))}
              </div>
              <dl>
                <div><dt>입장권 주인</dt><dd>김지수 님</dd></div>
                <div><dt>좌석</dt><dd>일반 · A-12</dd></div>
                <div><dt>본인 확인</dt><dd className="ok">패스키 등록됨</dd></div>
              </dl>
            </div>
          </div>
          <div className="th-float th-float-gate">
            <span>✓</span>
            <div><small>게이트 A</small><b>입장 확인</b></div>
          </div>
          <div className="th-float th-float-coupon">
            <span>+1</span>
            <div><small>부스 쿠폰</small><b>음료 1잔 지급</b></div>
          </div>
        </div>
      </section>

      <section className="th-features">
        <p className="eyebrow center"><span></span> WHY PASSLINK TICKET?</p>
        <h2>링크가 전달돼도, 입장권은 주인에게만</h2>
        <div className="th-grid">
          <article>
            <h3>본인만 열리는 입장권</h3>
            <p>처음 등록한 패스키에 묶여, 링크가 다른 사람에게 가도 그 사람은 입장권을 열 수 없어요.</p>
          </article>
          <article>
            <h3>게이트에서 바로 확인</h3>
            <p>태블릿 단말로 QR이나 얼굴을 확인하고, 입장과 퇴장을 실시간으로 기록해요.</p>
          </article>
          <article>
            <h3>쿠폰과 혼잡도</h3>
            <p>부스에서 쿠폰을 지급하고 사용하며, 현장 혼잡도를 관람객 화면에 바로 알려요.</p>
          </article>
        </div>
      </section>

      <section className="th-steps">
        <h2>이벤트 하나에 세 단계</h2>
        <ol>
          <li><b>01</b><span>이벤트 만들기</span><small>일시·장소·입장 정책을 정해요.</small></li>
          <li><b>02</b><span>입장권 발급</span><small>명단을 올리면 한 사람씩 링크가 나가요.</small></li>
          <li><b>03</b><span>게이트 확인</span><small>단말을 연결하고 입장을 확인해요.</small></li>
        </ol>
        <Link className="th-primary" to="/tickets">입장권 관리로 이동</Link>
      </section>
    </>
  );
}
