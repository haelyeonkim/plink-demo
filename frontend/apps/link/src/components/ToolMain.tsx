import { AppLink as Link, useBrand } from '@shared/scope';
import Icon from '@shared/components/Icon';
import { WALK, type Tool } from './Walkthrough';

/** Each tool's front door: what it promises, the way in, and what to know. */
const MAIN: Record<Tool, {
  title: [string, string];
  lead: string;
  start: string;
  guide: string;
  facts: Array<[string, string]>;
}> = {
  link: {
    title: ['보낸 링크는', '받은 사람만 열 수 있어요.'],
    lead: '받는 사람마다 링크를 하나씩 보내요. 주소를 넘겨받은 사람은 열 수 없고, 누가 언제 열었는지 바로 보여요.',
    start: '링크 관리 시작하기',
    guide: '/linkguide',
    facts: [
      ['앱 설치 없이', '받는 사람은 휴대폰 브라우저에서 바로 열어요.'],
      ['비밀번호 없이', '평소 쓰는 지문·얼굴 잠금이 열쇠가 돼요.'],
      ['누가 열었는지 보여요', '수신 확정과 열람 시각이 관리 화면에 바로 남아요.'],
    ],
  },
  ticket: {
    title: ['입장권은', '받은 사람만 열 수 있어요.'],
    lead: '명단을 올리면 한 사람씩 입장권이 나가요. 넘겨받은 사람은 열 수 없어서 대신 들어갈 수 없어요.',
    start: '입장권 관리 시작하기',
    guide: '/ticketguide',
    facts: [
      ['앱 설치 없이', '관람객은 휴대폰 브라우저에서 입장권을 열어요.'],
      ['비밀번호 없이', '평소 쓰는 지문·얼굴 잠금이 열쇠가 돼요.'],
      ['누가 들어왔는지 보여요', '등록과 게이트 입장이 관리 화면에 바로 남아요.'],
    ],
  },
};

/**
 * One tool's front door, for the people who came to find out about it: what it promises,
 * the way into its console, the four steps at a glance - the guide walks through them on
 * screen - and what to know. Each tool has its own, so each can later move to a domain of
 * its own.
 */
export default function ToolMain({ tool }: { tool: Tool }) {
  const main = MAIN[tool];
  const walk = WALK[tool];
  // The front door wears its tool's brand, so the brand knows where the console is.
  const brand = useBrand();
  return (
    <div className={`front front-${tool}`}>
      <section className="front-hero">
        <div className="front-copy">
          <h1>{main.title[0]}<br />{main.title[1]}</h1>
          <p>{main.lead}</p>
          <div className="front-actions">
            <Link className="front-start" to={brand.console}>{main.start}<Icon name="arrow-right" /></Link>
          </div>
        </div>
      </section>

      <section className="front-steps" aria-labelledby="steps-title">
        <div className="front-steps-head">
          <h2 id="steps-title">어떻게 지켜지는지</h2>
          <Link className="front-steps-more" to={main.guide}>화면으로 따라가 보기<Icon name="arrow-right" /></Link>
        </div>
        <ol>
          {walk.steps.map((label, i) => (
            <li key={label} className={i === 3 ? 'locked' : undefined}>
              <span className="front-step-icon"><Icon name={walk.icons[i]} /></span>
              <b><span className="front-step-num">{i + 1}</span>{label}</b>
              <p>{walk.captions[i][1]}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="front-facts" aria-label="알아 둘 것">
        {main.facts.map(([title, text]) => <div key={title}><b>{title}</b><span>{text}</span></div>)}
      </section>
    </div>
  );
}

