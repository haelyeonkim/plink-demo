import { mutate } from '@shared/auth';
import { ApiError, gateHeaders } from '../ticket/api';
import { passkeyCeremony, type CeremonyStage } from '../ticket/passkey';
import type { Paged } from '../components/ListTools';

/*
 * Everything a booth console talks to, and the shapes it answers with, in one
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

/**
 * How a booth console reaches its booth. The manager's phone and the booth's own terminal
 * ask the same questions and get the same answers; only who is asking differs - a signed-in
 * manager behind a session (and so CSRF), or a terminal carrying its own token.
 */
export interface BoothClient {
  get<T>(path: string): Promise<T>;
  send<T>(path: string, method: string, body?: unknown): Promise<T>;
}

const jsonBody = (body: unknown) => (body === undefined ? undefined : JSON.stringify(body));

/** A manager's link: the session cookie, and the CSRF token that goes with it. */
export function managerClient(token: string): BoothClient {
  const base = boothBase(token);
  return {
    get: <T>(path: string) => fetch(`${base}${path}`, { cache: 'no-store' }).then(response => read<T>(response)),
    send: <T>(path: string, method: string, body?: unknown) => mutate(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: jsonBody(body),
    }).then(response => read<T>(response)),
  };
}

/** A booth terminal: the same headers as every other terminal request, and no session. */
export function terminalClient(gateId: string, gateToken: string): BoothClient {
  const base = `/api/gates/${encodeURIComponent(gateId)}/booth`;
  return {
    get: <T>(path: string) => fetch(`${base}${path}`, { cache: 'no-store', headers: gateHeaders(gateToken) })
      .then(response => read<T>(response)),
    send: <T>(path: string, method: string, body?: unknown) => fetch(`${base}${path}`, {
      method, headers: gateHeaders(gateToken, body !== undefined), body: jsonBody(body),
    }).then(response => read<T>(response)),
  };
}

/** The list endpoints say pageSize; the console's pager says size. */
function paged<T>(data: { items: T[]; total: number; page: number; pageSize: number }): Paged<T> {
  return { items: data.items ?? [], total: data.total ?? 0, page: data.page ?? 0, size: data.pageSize || 50 };
}

// Signing in belongs to the manager's link alone: a terminal already is who it says.
export const fetchInvite = (token: string) => managerClient(token).get<BoothInvite>('');

export const requestBoothOtp = (token: string, email: string) =>
  managerClient(token).send<{ sent: boolean }>('/otp', 'POST', { email });

export const verifyBoothOtp = (token: string, email: string, code: string) =>
  managerClient(token).send<{ verified: boolean }>('/otp/verify', 'POST', { email, code });

/** Registers a passkey on first use, or signs in with the one already bound. */
export async function boothPasskey(token: string, onStage?: (stage: CeremonyStage) => void) {
  return passkeyCeremony(boothBase(token), {}, async stage => { onStage?.(stage); });
}

export const fetchDashboard = (client: BoothClient) => client.get<BoothDashboard>('/dashboard');

export const createOffer = (client: BoothClient, title: string, detail: string) =>
  client.send<BoothOffer>('/offers', 'POST', { title, detail });
export const updateOffer = (client: BoothClient, offerId: number, title: string, detail: string) =>
  client.send<BoothOffer>(`/offers/${offerId}`, 'PUT', { title, detail });
export const setOfferActive = (client: BoothClient, offerId: number, active: boolean) =>
  client.send<unknown>(`/offers/${offerId}/active`, 'POST', { active });
export const deleteOffer = (client: BoothClient, offerId: number) =>
  client.send<unknown>(`/offers/${offerId}`, 'DELETE');

export async function fetchTickets(client: BoothClient, query: { q: string; page: number; inside: boolean; offerId: number | null }) {
  const params = new URLSearchParams({ page: String(query.page) });
  if (query.q) params.set('q', query.q);
  if (query.inside) params.set('inside', 'true');
  if (query.offerId !== null) params.set('offerId', String(query.offerId));
  return paged(await client.get<{ items: BoothTicket[]; total: number; page: number; pageSize: number }>(`/tickets?${params}`));
}

export const issueCoupons = (client: BoothClient, body: { offerId: number; target: IssueTarget; ticketIds?: number[]; quantity: number }) =>
  client.send<IssueResult>('/issue', 'POST', body);

/**
 * Shaped like the booth terminal's answers, plus a sentence to show as it is. Only a
 * manager's link has this route: a terminal reads codes through its own gate routes.
 */
export const scanCode = (client: BoothClient, code: string, action: ScanAction, offerId?: number) =>
  client.send<Record<string, unknown>>('/scan', 'POST', { code, action, ...(offerId ? { offerId } : {}) });

export async function fetchCoupons(client: BoothClient, query: { status: CouponStatus | 'ALL'; q: string; page: number }) {
  const params = new URLSearchParams({ page: String(query.page) });
  if (query.q) params.set('q', query.q);
  if (query.status !== 'ALL') params.set('status', query.status);
  const data = await client.get<{ items: BoothCoupon[]; total: number; page: number; pageSize: number; counts: CouponCounts }>(
    `/coupons?${params}`);
  return { ...paged(data), counts: data.counts };
}

export const setCouponStatus = (client: BoothClient, ids: number[], status: 'VOID' | 'ISSUED') =>
  client.send<unknown>('/coupons/status', 'POST', { ids, status });

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
