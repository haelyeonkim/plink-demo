import Icon from './Icon';
import { Link } from 'react-router-dom';

export default function Hero() {
  return (
    <>
      {/* Hero */}
      <section className="hero" id="top">
        <div className="hero-copy">
          <p className="eyebrow"><span></span> PRIVATE LINK SHARING</p>
          <h1>
            먼저 보여야 할 사람에게,<br />
            <strong>안전하게 링크하세요.</strong>
          </h1>
          <p className="lead">
            관리자를 위한 비공개 링크 공유.<br className="desktop" />
            수신자에게 링크를 전달하고, 패스키 수신 확정과 열람을 관리하세요.
          </p>
          <div className="actions">
            <Link className="primary" to="/links/new">보호 링크 만들기 <b><Icon name="arrow-right" /></b></Link>
            <Link className="secondary" to="/guide">사용 방법 보기</Link>
          </div>
          <div className="trust">
            <span className="avatars"><i>J</i><i>S</i><i>M</i></span>
            <span>
              <b>중요한 공유의 새로운 기준</b>
              <small>필요한 사람에게, 필요한 순간에</small>
            </span>
          </div>
        </div>
        <div className="hero-art" aria-label="보호된 링크 공유 예시">
          <div className="orb orb-one"></div>
          <div className="orb orb-two"></div>
          <div className="link-card">
            <div className="card-top">
              <img className="card-logo" src="/logo-small.svg" alt="P" width="28" height="28" />
              <span className="dots"><Icon name="more" /></span>
            </div>
            <div className="lock"><span><Icon name="lock" /></span></div>
            <p className="secure">PROTECTED LINK</p>
            <h2>2026 브랜드 리뉴얼<br />최종 제안서</h2>
            <div className="recipient">
              <span>JS</span>
              <div><small>공유 대상</small><b>김지수 님</b></div>
              <em>인증됨</em>
            </div>
            <div className="expires">
              <span><Icon name="clock" /></span>
              <div><small>링크 만료까지</small><b>2일 14시간</b></div>
            </div>
            <button>안전하게 링크 열기 <span><Icon name="arrow-right" /></span></button>
            <p className="notice">이 링크는 수신을 확정한 패스키로 열 수 있어요.</p>
          </div>
          <div className="float-card check">
            <span><Icon name="check" /></span>
            <div><small>열람 확인</small><b>방금 링크를 확인했어요</b></div>
          </div>
          <div className="float-card shield">
            <span><Icon name="shield" /></span>
            <div><small>P-LINK SECURITY</small><b>보호 중</b></div>
          </div>
        </div>
      </section>

      {/* Why Passlink */}
      <section className="meaning" id="guide">
        <p className="eyebrow center"><span></span> WHY P-LINK?</p>
        <h2>P에 담긴 세 가지 약속</h2>
        <p className="section-lead">링크 하나에도 배려와 안전, 우선순위를 담았습니다.</p>
        <div className="feature-grid">
          <article className="violet">
            <div className="feature-icon"><img src="/icons/private.svg" alt="" width="26" height="26" /></div>
            <p>P for</p>
            <h3>Private<span>.</span></h3>
            <h4>보여줄 사람만</h4>
            <p className="feature-text">받을 사람에게 링크를 전달하면, 처음 등록한 패스키에 접근 권한이 연결돼요.</p>
          </article>
          <article className="blue">
            <div className="feature-icon"><img src="/icons/protected.svg" alt="" width="26" height="26" /></div>
            <p>P for</p>
            <h3>Protected<span>.</span></h3>
            <h4>안전하게 보호</h4>
            <p className="feature-text">비밀번호와 만료일을 설정해 중요한 콘텐츠를 지켜요.</p>
          </article>
          <article className="mint">
            <div className="feature-icon"><img src="/icons/priority.svg" alt="" width="26" height="26" /></div>
            <p>P for</p>
            <h3>Priority<span>.</span></h3>
            <h4>중요한 순간 먼저</h4>
            <p className="feature-text">누가, 언제 확인했는지 알고 꼭 필요한 후속 행동을 이어가요.</p>
          </article>
        </div>
      </section>

      {/* How it works */}
      <section className="how" id="create-guide">
        <div>
          <p className="eyebrow"><span></span> SIMPLE &amp; SECURE</p>
          <h2>링크만 넣으면,<br />보호 준비 끝.</h2>
          <p>복잡한 설정 없이 1분이면 충분해요.</p>
        </div>
        <ol>
          <li><b>01</b><span>원본 링크 입력</span></li>
          <li><b>02</b><span>보호 옵션 설정</span></li>
          <li><b>03</b><span>패스링크 공유</span></li>
        </ol>
      </section>

      {/* Mini sections */}
      <section className="mini-sections">
        <Link className="mini-section-link" to="/links" id="manage-intro">
          <b>링크 관리</b>
          <p>문서마다 수신자를 발급하고, 누가 열었는지 확인하세요.</p>
        </Link>
        <Link className="mini-section-link" to="/tickets/admin" id="stats">
          <b>입장권 관리</b>
          <p>행사를 만들고 입장권을 발급해 게이트에서 확인하세요.</p>
        </Link>
      </section>

    </>
  );
}
