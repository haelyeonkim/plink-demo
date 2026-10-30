import { useRef, useState } from 'react';
import { AppLink as Link } from '@shared/scope';

type Tool = 'link' | 'ticket';

/** What each of the four steps shows, for one tool: the holder's screen and the console's. */
const WALK: Record<Tool, {
  steps: string[];
  captions: Array<[string, string]>;
  cta: string[];
  target: [string, string[]];
  log: string[];
  owner: string;
  message: string;
  card: string;
  app: string;
  locked: [string, string];
}> = {
  link: {
    steps: ['링크 보내기', '패스키로 받기', '언제든 열기', '친구에게 넘기기'],
    captions: [
      ['링크를 보내요', '받는 사람마다 링크를 하나씩 만들어 메일이나 메신저로 보내요.'],
      ['처음 연 사람이 패스키로 받아요', '지문이나 얼굴 잠금 한 번이면 끝나요. 이 순간 링크의 주인이 정해져요.'],
      ['그 사람은 언제든 열어요', '비밀번호 없이 패스키로 바로 열리고, 관리자는 열람 시각을 봐요.'],
      ['친구에게 넘기면 잠겨요', '주소를 그대로 보내도 친구 휴대폰엔 그 패스키가 없어서 열리지 않아요.'],
    ],
    cta: ['링크 열기', '등록하기', '자료 보기', '보낸 사람에게 문의'],
    target: ['수신자 A', ['보냄', '수신 확정', '열람 · 10:42', '열람 · 10:42']],
    log: ['10:30  수신자 A에게 링크를 보냈어요', '10:41  수신자 A 님이 패스키로 수신을 확정했어요', '10:42  수신자 A 님이 열람했어요'],
    owner: '받는 사람은 그대로 수신자 A 한 명이에요',
    message: '요청하신 자료 보내드려요.',
    card: '2026 브랜드 리뉴얼 제안서',
    app: '메시지',
    locked: ['열 수 없는 링크예요', '이미 다른 사람이 받은 링크예요. 보낸 사람에게 새 링크를 요청하세요.'],
  },
  ticket: {
    steps: ['입장권 보내기', '패스키로 등록', '게이트 입장', '친구에게 넘기기'],
    captions: [
      ['입장권을 보내요', '명단을 올리면 한 사람씩 입장권 링크가 메일로 나가요.'],
      ['처음 연 사람이 패스키로 등록해요', '이 순간 입장권의 주인이 정해져요.'],
      ['게이트에서 QR을 보여요', '본인 확인 뒤에 QR이 열리고, 게이트 태블릿이 입장을 기록해요.'],
      ['친구에게 넘기면 잠겨요', '링크를 넘겨도 친구는 QR을 열 수 없어서 대신 들어갈 수 없어요.'],
    ],
    cta: ['입장권 열기', '등록하기', '쿠폰 · 채팅', '안내 데스크 문의'],
    target: ['관람객 A', ['발급', '등록 완료', '장내 · 10:42', '장내 · 10:42']],
    log: ['10:00  입장권을 발급했어요', '10:20  관람객 A 님이 패스키로 등록했어요', '10:42  정문 게이트 · 입장 확인'],
    owner: '입장권 주인은 그대로 관람객 A 한 명이에요',
    message: '2026 가을 아트페어 입장권이 도착했어요.',
    card: '2026 가을 아트페어 입장권',
    app: '메일',
    locked: ['다른 사람의 입장권이에요', '등록한 사람의 패스키로만 열려요. 링크를 넘겨도 입장할 수 없어요.'],
  },
};

const LOCK = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </svg>
);
const TICKET = (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 8a2 2 0 0 0 0 4v4h18v-4a2 2 0 0 0 0-4V4H3z" />
  </svg>
);

/**
 * Passlink's front door. The two tools come first, for the people who came to use one;
 * below them, four steps that show what the passkey does - a link that reaches the wrong
 * person stays shut - for the people who came to find out.
 */
export default function Home() {
  return (
    <div className="front">
      <section className="front-hero">
        <div className="front-copy">
          <h1>링크를 넘겨도,<br />열리는 건 한 사람.</h1>
          <p>처음 등록한 패스키로 주인이 정해져요. 쓰려는 도구로 바로 들어가거나, 아래에서 어떻게 지켜지는지 따라가 보세요.</p>
        </div>
        <nav className="front-tools" aria-label="도구 바로가기">
          <Link className="front-tool tool-link" to="/links">
            <span className="front-tool-top"><span className="front-tool-icon">{LOCK}</span><span aria-hidden="true">→</span></span>
            <span className="front-tool-name"><b>보호 링크</b><small>문서·작품을 받는 사람에게만</small></span>
          </Link>
          <Link className="front-tool tool-ticket" to="/tickets">
            <span className="front-tool-top"><span className="front-tool-icon">{TICKET}</span><span aria-hidden="true">→</span></span>
            <span className="front-tool-name"><b>입장권</b><small>이벤트 입장과 현장 운영까지</small></span>
          </Link>
        </nav>
      </section>

      <Walkthrough />

      <section className="front-facts" aria-label="알아 둘 것">
        <div><b>앱 설치 없이</b><span>휴대폰 브라우저에서 바로 열려요.</span></div>
        <div><b>비밀번호 없이</b><span>평소 쓰는 지문·얼굴 잠금이 열쇠가 돼요.</span></div>
        <div><b>누가 열었는지 보여요</b><span>수신 확정, 열람, 입장이 관리자 화면에 바로 남아요.</span></div>
      </section>
    </div>
  );
}

function Walkthrough() {
  const [tool, setTool] = useState<Tool>('link');
  const [step, setStep] = useState(0);
  const [logOpen, setLogOpen] = useState(false);
  const touchX = useRef<number | null>(null);
  const walk = WALK[tool];
  const friend = step === 3;

  const pick = (next: Tool) => { setTool(next); setStep(0); };
  const go = (next: number) => setStep(Math.min(3, Math.max(0, next)));

  return (
    <section className={`front-walk walk-${tool}`} aria-labelledby="walk-title">
      <div className="walk-head">
        <h2 id="walk-title">어떻게 지켜지는지 따라가 보기</h2>
        <div className="walk-tabs" role="group" aria-label="따라가 볼 도구">
          <button type="button" aria-pressed={tool === 'link'} onClick={() => pick('link')}>보호 링크</button>
          <button type="button" aria-pressed={tool === 'ticket'} onClick={() => pick('ticket')}>입장권</button>
        </div>
      </div>

      <ol className="walk-steps">
        {walk.steps.map((label, i) => (
          <li key={label}>
            <button type="button" aria-current={i === step ? 'step' : undefined}
              className={i < step ? 'done' : undefined} onClick={() => go(i)}>
              <span className="walk-num">{i + 1}</span>
              <span className="walk-label">{label}</span>
            </button>
          </li>
        ))}
      </ol>

      <div className="walk-body">
        <div className="walk-caption">
          <span className="walk-of">{step + 1} / 4 · {walk.steps[step]}</span>
          <h3>{walk.captions[step][0]}</h3>
          <p>{walk.captions[step][1]}</p>
          <div className="walk-nav">
            <button type="button" className="walk-prev" disabled={step === 0} onClick={() => go(step - 1)}>← 이전</button>
            <button type="button" className="walk-next" onClick={() => setStep(friend ? 0 : step + 1)}>
              {friend ? '처음부터 다시' : '다음 →'}
            </button>
          </div>
        </div>

        <div className={`walk-device${friend ? ' friend' : ''}`} aria-live="polite"
          onTouchStart={(e) => { touchX.current = e.touches[0].clientX; }}
          onTouchEnd={(e) => {
            if (touchX.current === null) return;
            const dx = e.changedTouches[0].clientX - touchX.current;
            touchX.current = null;
            if (Math.abs(dx) > 50) go(step + (dx < 0 ? 1 : -1));
          }}>
          <div className="walk-screen">
            <div className="walk-strip">
              <span>{friend ? '친구의 휴대폰' : '받은 사람의 휴대폰'}</span>
              <span>{step === 0 ? walk.app : tool === 'link' ? '보호 링크' : '입장권'}</span>
            </div>
            <div className="walk-view">
              {step === 0 && (
                <div className="walk-message">
                  <span className="walk-bubble">{walk.message}</span>
                  <span className="walk-preview">
                    <span className="walk-preview-art">{LOCK}</span>
                    <b>{walk.card}</b>
                    <small>{window.location.host}</small>
                  </span>
                </div>
              )}
              {step === 1 && (
                <div className="walk-center">
                  <span className="walk-badge">
                    <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M12 11v4" /><path d="M8.5 9.5a4 4 0 0 1 7 2.5v2a8 8 0 0 1-1 4" /><path d="M8 13v1a8 8 0 0 0 1.2 4.2" />
                      <path d="M5.5 8a8 8 0 0 1 13 1.5" /><path d="M5 12v1.5" /><path d="M19 12.5v1" />
                    </svg>
                  </span>
                  <b>패스키로 받을까요?</b>
                  <span>이 휴대폰의 지문·얼굴 잠금으로 등록해요. 다음부터는 이 휴대폰에서만 열려요.</span>
                </div>
              )}
              {step === 2 && tool === 'link' && (
                <div className="walk-doc">
                  <span className="walk-ok">수신 확정됨</span>
                  <b>{walk.card}</b>
                  <i className="walk-doc-art" /><i /><i /><i />
                </div>
              )}
              {step === 2 && tool === 'ticket' && (
                <div className="walk-center">
                  <b>2026 가을 아트페어</b>
                  <svg className="walk-qr" viewBox="0 0 21 21" role="img" aria-label="입장 QR 예시">
                    <g fill="currentColor">
                      <path fillRule="evenodd" d="M0 0h7v7H0zM1 1v5h5V1zM2 2h3v3H2z" />
                      <path fillRule="evenodd" d="M14 0h7v7h-7zM15 1v5h5V1zM16 2h3v3h-3z" />
                      <path fillRule="evenodd" d="M0 14h7v7H0zM1 15v5h5v-5zM2 16h3v3H2z" />
                      <path d="M8 0h2v2H8zM11 1h2v2h-2zM9 3h2v2H9zM8 6h1v2H8zM10 7h3v1h-3zM12 4h1v2h-1zM0 8h2v1H0zM3 8h3v2H3zM1 10h2v2H1zM5 11h3v1H5zM8 9h2v3H8zM11 9h2v2h-2zM14 8h2v2h-2zM17 8h4v1h-4zM15 11h3v2h-3zM19 10h2v3h-2zM8 13h2v2H8zM11 12h3v1h-3zM12 14h2v3h-2zM8 16h3v1H8zM9 18h2v3H9zM15 14h2v2h-2zM18 15h3v1h-3zM14 18h3v2h-3zM18 18h2v3h-2zM12 19h1v2h-1z" />
                    </g>
                  </svg>
                  <span className="walk-ok">정문 · 입장 확인</span>
                </div>
              )}
              {friend && (
                <div className="walk-center walk-locked">
                  <span className="walk-badge">{LOCK}</span>
                  <b>{walk.locked[0]}</b>
                  <span>{walk.locked[1]}</span>
                </div>
              )}
            </div>
            <span className="walk-cta">{walk.cta[step]}</span>
          </div>
        </div>

        <aside className={`walk-console${logOpen ? ' open' : ''}`} aria-label="관리자 화면에 보이는 것">
          <button type="button" className="walk-console-head" aria-expanded={logOpen} onClick={() => setLogOpen(!logOpen)}>
            <span><small>관리자 화면에서는</small><b>{walk.target[0]} · <em>{walk.target[1][step]}</em></b></span>
            <span className="walk-console-toggle">{logOpen ? '접기' : '기록 보기'}</span>
          </button>
          <ol className="walk-log">
            {walk.log.slice(0, Math.min(step, 2) + 1).map((line, i, all) => (
              <li key={line} className={i === all.length - 1 ? 'latest' : undefined}>{line}</li>
            ))}
          </ol>
          {friend && <p className="walk-owner">{walk.owner}</p>}
        </aside>
      </div>
    </section>
  );
}
