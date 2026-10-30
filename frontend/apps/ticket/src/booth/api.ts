import { mutate } from '@shared/auth';
import { ApiError } from '../ticket/api';
import { passkeyCeremony, type CeremonyStage } from '../ticket/passkey';
import type { Paged } from '../components/ListTools';

/*
 * Everything a booth manager's link talks to, and the shapes it answers with, in one
 * place: the server side was written alongside this, so a field that turns out named
 * differently is changed here and nowhere else.
 */

/** What the link says about itself before anybody has signed in. */
export interface BoothInvite {
  boothId: number;
  boothName: string;
  boothNote: string | null;
  eventName: string;
  eventStartsAt: string | null;
  venue: string | null;
  /** The invited address, masked: a hint of which mailbox the code goes to. */
  emailMasked: string;
  /** INVITED until the first passkey is bound; ACTIVE afterwards. */
  status: 'INVITED' | 'ACTIVE';
  signedIn: boolean;
}

export interface BoothOffer {
  offerId: number;
  title: string;
  detail: string | null;
  active: boolean;
  issued: number;
  redeemed: number;
  voided: number;
  /** Tickets holding at least one of it. */
  holders: number;
}

export type CouponStatus = 'ISSUED' | 'REDEEMED' | 'VOID';

export interface BoothDashboard {
  booth: { boothId: number; name: string; note: string | null };
  event: { name: string; startsAt: string | null; venue: string | null };
  liveTickets: number;
  inside: number;
  offers: BoothOffer[];
  recent: { couponId: number; title: string; ticketRef: string; status: CouponStatus; at: string | null }[];
}

export interface BoothTicket {
  ticketId: number;
  ticketRef: string;
  emailMasked: string | null;
  inside: boolean;
  /** How many of the offer asked about this ticket already holds. */
  held: number;
}

export interface IssueResult { given: number; tickets: number; already: number; title: string; quantity: number }

export interface BoothCoupon {
  couponId: number;
  title: string;
  ticketRef: string;
  emailMasked: string | null;
  status: CouponStatus;
  issuedAt: string | null;
  redeemedAt: string | null;
  /** Where it was used: a terminal's name, or a booth manager. */
  redeemedBy: string | null;
}

export interface CouponCounts { all: number; issued: number; redeemed: number; void: number }

export type IssueTarget = 'ALL' | 'INSIDE' | 'PICKED';
export type ScanAction = 'GIVE' | 'REDEEM';

async function read<T>(response: Response): Promise<T> {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new ApiError(data.error || '요청을 처리하지 못했어요. 다시 시도해 주세요.', response.status);
  }
  return data as T;
}

export function boothBase(token: string) {
  return `/api/booth/${encodeURIComponent(token)}`;
}

const send = <T>(token: string, path: string, method: string, body?: unknown) =>
  mutate(`${boothBase(token)}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then(response => read<T>(response));

const get = <T>(token: string, path: string) =>
  fetch(`${boothBase(token)}${path}`, { cache: 'no-store' }).then(response => read<T>(response));

/** The list endpoints say pageSize; the console's pager says size. */
function paged<T>(data: { items: T[]; total: number; page: number; pageSize: number }): Paged<T> {
  return { items: data.items ?? [], total: data.total ?? 0, page: data.page ?? 0, size: data.pageSize || 50 };
}

export const fetchInvite = (token: string) => get<BoothInvite>(token, '');

export const requestBoothOtp = (token: string, email: string) =>
  send<{ sent: boolean }>(token, '/otp', 'POST', { email });

export const verifyBoothOtp = (token: string, email: string, code: string) =>
  send<{ verified: boolean }>(token, '/otp/verify', 'POST', { email, code });

/** Registers a passkey on first use, or signs in with the one already bound. */
export async function boothPasskey(token: string, onStage?: (stage: CeremonyStage) => void) {
  return passkeyCeremony(boothBase(token), {}, async stage => { onStage?.(stage); });
}

export const fetchDashboard = (token: string) => get<BoothDashboard>(token, '/dashboard');

export const createOffer = (token: string, title: string, detail: string) =>
  send<BoothOffer>(token, '/offers', 'POST', { title, detail });
export const updateOffer = (token: string, offerId: number, title: string, detail: string) =>
  send<BoothOffer>(token, `/offers/${offerId}`, 'PUT', { title, detail });
export const setOfferActive = (token: string, offerId: number, active: boolean) =>
  send<unknown>(token, `/offers/${offerId}/active`, 'POST', { active });
export const deleteOffer = (token: string, offerId: number) =>
  send<unknown>(token, `/offers/${offerId}`, 'DELETE');

export async function fetchTickets(token: string, query: { q: string; page: number; inside: boolean; offerId: number | null }) {
  const params = new URLSearchParams({ page: String(query.page) });
  if (query.q) params.set('q', query.q);
  if (query.inside) params.set('inside', 'true');
  if (query.offerId !== null) params.set('offerId', String(query.offerId));
  return paged(await get<{ items: BoothTicket[]; total: number; page: number; pageSize: number }>(token, `/tickets?${params}`));
}

export const issueCoupons = (token: string, body: { offerId: number; target: IssueTarget; ticketIds?: number[]; quantity: number }) =>
  send<IssueResult>(token, '/issue', 'POST', body);

/** Shaped like the booth terminal's answers, plus a sentence to show as it is. */
export const scanCode = (token: string, code: string, action: ScanAction, offerId?: number) =>
  send<Record<string, unknown>>(token, '/scan', 'POST', { code, action, ...(offerId ? { offerId } : {}) });

export async function fetchCoupons(token: string, query: { status: CouponStatus | 'ALL'; q: string; page: number }) {
  const params = new URLSearchParams({ page: String(query.page) });
  if (query.q) params.set('q', query.q);
  if (query.status !== 'ALL') params.set('status', query.status);
  const data = await get<{ items: BoothCoupon[]; total: number; page: number; pageSize: number; counts: CouponCounts }>(
    token, `/coupons?${params}`);
  return { ...paged(data), counts: data.counts };
}

export const setCouponStatus = (token: string, ids: number[], status: 'VOID' | 'ISSUED') =>
  send<unknown>(token, '/coupons/status', 'POST', { ids, status });

/** One manager as the organiser's console lists them. */
export interface BoothManagerRow {
  managerId: number;
  email: string;
  status: 'INVITED' | 'ACTIVE' | 'REVOKED';
  invitedAt: string | null;
  claimedAt: string | null;
  lastSeenAt: string | null;
}

/** Answered once, when a link is made: only a hash of it is kept. */
export interface ManagerInvited { managerId: number; email: string; status: string; link: string }
