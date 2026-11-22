import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { frontDoorOf } from './frontDoor';

const TICKET = '패스링크 티켓';
const LINK = '패스링크 프라이빗';

/**
 * The tab title for a page, always "first | second". A front door leads with the tool's name,
 * as a search result or a shared card would; every other page leads with what it is, so open
 * tabs tell apart.
 * A holder's or recipient's page never names the event or the material: the title shows
 * wherever the address is shared.
 */
export function titleOf(path: string): string {
  const door = frontDoorOf(path);
  if (door) return door.title;
  if (/^\/ticketguide\/?$/.test(path)) return `가이드 | ${TICKET}`;
  if (/^\/linkguide\/?$/.test(path)) return `가이드 | ${LINK}`;
  if (/^\/tickets\/login\/?$/.test(path)) return `로그인 | ${TICKET}`;
  if (/^\/tickets\/gate(?:\/|$)/.test(path)) return `게이트 | ${TICKET}`;
  if (/^\/tickets\/booth\//.test(path)) return `부스 관리 | ${TICKET}`;
  // /tickets/{sessionId}/{token}: the holder's own ticket.
  if (/^\/tickets\/(?!admin\/|sessions\/)[^/]+\/[^/]+/.test(path)) return `내 입장권 | ${TICKET}`;
  if (/^\/tickets(?:\/|$)/.test(path)) return `이벤트 관리 | ${TICKET}`;
  if (/^\/links\/login\/?$/.test(path)) return `로그인 | ${LINK}`;
  if (/^\/s\//.test(path)) return `받은 링크 | ${LINK}`;
  if (/^\/links(?:\/|$)/.test(path)) return `링크 관리 | ${LINK}`;
  return '패스링크';
}

/** Keeps the tab title in step with the page on screen. */
export function DocumentTitle() {
  const { pathname } = useLocation();
  useEffect(() => { document.title = titleOf(pathname); }, [pathname]);
  return null;
}
