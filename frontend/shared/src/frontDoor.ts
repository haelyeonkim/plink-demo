/**
 * What each tool's front door says about itself before any script runs: the tab title, the
 * search snippet and the card a messenger draws when the address is shared. Kept free of
 * React so the server that writes it into the page (apps/link/vite.config.ts) can read it too.
 */
export interface FrontDoor {
  siteName: string;
  title: string;
  description: string;
}

const FRONT_DOORS: Record<string, FrontDoor> = {
  '/ticketmain': {
    siteName: '패스링크 티켓',
    title: '패스링크 티켓 | 받은 사람만 입장하는 입장권',
    description: '받은 사람의 휴대폰에서만 열려서 대리 입장과 암표 걱정이 없고, 게이트 입장도 바로 확인할 수 있어요.',
  },
  '/linkmain': {
    siteName: '패스링크 프라이빗',
    title: '패스링크 프라이빗 | 받은 사람만 여는 링크',
    description: '자료를 받는 사람마다 전용 링크로 보내세요. 주소를 넘겨받은 사람은 열 수 없고, 누가 언제 열었는지 바로 보여요. 앱 설치도 비밀번호도 필요 없어요.',
  },
};

const pathOf = (url: string) => url.split(/[?#]/)[0].replace(/\/+$/, '');

/** The front door at this address, if it is one; a query string or trailing slash is ignored. */
export function frontDoorOf(url: string): FrontDoor | undefined {
  return FRONT_DOORS[pathOf(url)];
}

/**
 * The pages a search engine may list: each tool's front door and guide. Everything else is
 * a sign-in page, an operator's console, or a holder's or recipient's own address.
 */
const SEARCHABLE = new Set([...Object.keys(FRONT_DOORS), '/linkguide', '/ticketguide']);

export function isSearchable(url: string): boolean {
  return SEARCHABLE.has(pathOf(url));
}

const escape = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');

/** The page with its <title> replaced by the front door's title, description and Open Graph card. */
export function withFrontDoorHead(html: string, url: string): string {
  const door = frontDoorOf(url);
  if (!door) return html;
  const title = escape(door.title);
  const description = escape(door.description);
  const head = [
    `<title>${title}</title>`,
    `<meta name="description" content="${description}" />`,
    `<meta property="og:type" content="website" />`,
    `<meta property="og:locale" content="ko_KR" />`,
    `<meta property="og:site_name" content="${escape(door.siteName)}" />`,
    `<meta property="og:title" content="${title}" />`,
    `<meta property="og:description" content="${description}" />`,
    `<meta name="twitter:card" content="summary" />`,
  ].join('\n    ');
  return html.replace(/<title>[\s\S]*?<\/title>/, head);
}
