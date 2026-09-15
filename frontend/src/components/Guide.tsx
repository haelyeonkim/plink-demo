import { Link } from 'react-router-dom';

export default function Guide() {
  return (
    <section className="page-section guide-page">
      <div className="section-header">
        <p className="eyebrow"><span /> GUIDE</p>
        <h2>사용자 가이드</h2>
        <p className="section-desc">P-Link로 중요한 링크를 만들고, 필요한 수신자에게 안전하게 전달하는 방법입니다.</p>
      </div>

      <h3 className="guide-role-title">관리자</h3>
      <div className="guide-grid guide-admin-grid">
        <article className="guide-card"><b>01 · 링크 생성</b><h3>수신자마다 별도 링크 생성</h3><p>원본 URL, 제목, 만료 기간, 최대 열람 수를 입력하고 이메일 또는 전화번호를 추가하세요. 수신자 N명에게는 서로 다른 보호 링크 N개가 생성됩니다.</p><Link to="/create">보호 링크 만들기 →</Link></article>
        <article className="guide-card"><b>02 · 전달</b><h3>생성된 링크를 수신자에게 전달</h3><p>결과 화면에서 각 수신자에 맞는 링크를 복사하세요. 이메일이나 문자를 P-Link가 자동 발송하지는 않습니다.</p></article>
        <article className="guide-card"><b>03 · 관리하기</b><h3>열람 현황과 상태 확인</h3><p>링크 관리에서 수신 확정 여부, 만료일, 열람 횟수를 확인하고 링크를 삭제할 수 있습니다. 상세 화면에서는 최초 열람과 패스키 인증 시점을 구분해 보여줍니다.</p><Link to="/manage">링크 관리로 이동 →</Link></article>
      </div>

      <h3 className="guide-role-title">수신자</h3>
      <div className="guide-grid guide-recipient-grid">
        <article className="guide-card"><b>01 · 초기 인증</b><h3>연락처 확인 후 패스키 등록</h3><p>처음 링크를 열 때 링크를 전달받은 이메일 또는 전화번호를 입력합니다. 확인이 끝나면 지문·얼굴 인식 또는 기기 잠금번호로 패스키를 등록합니다.</p></article>
        <article className="guide-card"><b>02 · 다시 열기</b><h3>등록한 패스키로만 열기</h3><p>패스키 인증이 완료된 뒤에는 같은 링크를 등록된 패스키로만 열 수 있습니다. 다른 기기에서 수신자 등록을 다시 진행할 수 없습니다.</p></article>
      </div>

      <div className="guide-note"><strong>보안 안내</strong><p>패스키 개인키·생체정보는 서버에 저장되지 않습니다. 링크가 만료되거나 열람 횟수를 초과하면 원본 링크를 열 수 없습니다. 잘못된 공유 코드는 홈 화면에서 유효성 확인 후 다시 입력할 수 있습니다.</p></div>
    </section>
  );
}
