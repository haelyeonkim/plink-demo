import { Link } from 'react-router-dom';

/**
 * What the two products do, in the order somebody meets them. Written against how
 * Passlink works now: a link holds a document and issues one address per person, an
 * event holds tickets, and a passkey belongs to the person rather than to either.
 */
export default function Guide() {
  return (
    <section className="page-section guide-page">
      <div className="section-header">
        <p className="eyebrow"><span></span> GUIDE</p>
        <h2>사용자 가이드</h2>
        <p className="section-desc">
          보호 링크와 입장권은 같은 원리로 움직입니다 — 받는 사람마다 주소를 하나씩 발급하고,
          그 사람의 패스키에만 열립니다.
        </p>
      </div>

      <h3 className="guide-role-title">보호 링크 · 관리자</h3>
      <div className="guide-grid">
        <article className="guide-card">
          <b>01 · 링크 만들기</b>
          <h3>문서 하나를 등록합니다</h3>
          <p>
            원본 주소와 제목, 필요하면 비밀번호·만료 일시·최대 열람 수를 정합니다. 이 단계에서는
            아직 아무에게도 열리지 않습니다.
          </p>
          <Link to="/links/new">링크 추가 →</Link>
        </article>
        <article className="guide-card">
          <b>02 · 수신자마다 발급</b>
          <h3>한 사람에 주소 하나</h3>
          <p>
            받는 사람의 이메일로 개인 주소를 발급합니다. 메일로 바로 보내거나 주소만 복사해 직접
            전달할 수 있고, 명단이 있으면 CSV로 한 번에 올릴 수 있어요.
          </p>
          <Link to="/links">링크 관리로 이동 →</Link>
        </article>
        <article className="guide-card">
          <b>03 · 확인하고 끊기</b>
          <h3>누가 열었는지, 누구를 막을지</h3>
          <p>
            열람 기록은 <b>링크를 열어본 시점</b>과 <b>패스키로 본인 확인을 마친 시점</b>을 나눠
            보여줍니다. 특정 수신자만 비활성화하면 나머지는 그대로 열립니다.
          </p>
        </article>
      </div>

      <h3 className="guide-role-title">보호 링크 · 받는 사람</h3>
      <div className="guide-grid">
        <article className="guide-card">
          <b>01 · 본인 확인</b>
          <h3>받은 주소를 입력하고 패스키 등록</h3>
          <p>
            처음 열 때 링크를 받은 이메일을 입력한 뒤 지문·얼굴 인식으로 패스키를 등록합니다.
            이미 패스링크 패스키가 있다면 새로 만들지 않고 그대로 씁니다.
          </p>
        </article>
        <article className="guide-card">
          <b>02 · 다시 열기</b>
          <h3>내 패스키로만 열립니다</h3>
          <p>
            확정된 뒤에는 등록한 패스키로만 열립니다. 주소를 다른 사람에게 전달해도 그 사람은
            열 수 없어요.
          </p>
        </article>
      </div>

      <h3 className="guide-role-title">입장권</h3>
      <div className="guide-grid">
        <article className="guide-card">
          <b>01 · 행사와 발급</b>
          <h3>좌석·등급은 직접 정합니다</h3>
          <p>
            행사를 만들고 좌석·등급 목록을 추가한 뒤 이메일로 입장권을 발급합니다. 명단은 CSV로
            한 번에 올릴 수 있어요.
          </p>
          <Link to="/tickets/admin">입장권 관리로 이동 →</Link>
        </article>
        <article className="guide-card">
          <b>02 · 입장</b>
          <h3>회전 QR 또는 얼굴</h3>
          <p>
            보유자는 패스키로 본인 확인을 하면 10초마다 바뀌는 QR이 열립니다. 화면이 바뀐 이전
            QR은 즉시 무효라 캡처를 넘겨도 통하지 않습니다. 얼굴을 등록했다면 QR 없이 지나갑니다.
          </p>
        </article>
        <article className="guide-card">
          <b>03 · 게이트</b>
          <h3>단말은 링크와 인증번호로 연결</h3>
          <p>
            태블릿에서 게이트 링크를 열고 인증번호를 입력하면 그 기기 하나에만 묶입니다. 장소를
            지정해 두면 장소별 혼잡도를 볼 수 있어요.
          </p>
        </article>
      </div>

      <div className="guide-note">
        <strong>보안 안내</strong>
        <p>
          패스키의 개인키와 생체정보는 기기를 떠나지 않습니다. 서버가 보관하는 것은 공개키와
          얼굴 특징값(암호화)뿐이고, 얼굴 사진 원본은 저장하지 않습니다. 자세한 내용은{' '}
          <Link to="/privacy">개인정보처리방침</Link>에 있습니다.
        </p>
      </div>
    </section>
  );
}
