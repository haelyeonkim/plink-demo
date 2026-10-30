import { mutate } from '@shared/auth';
import type { Grant } from './codes';

export interface TicketTransfer {
  status: string;
  toEmail: string;
  expiresAt: string;
}

/** An offer this ticket holds at a booth, and what became of it. */
export interface TicketCoupon {
  couponId: number;
  booth: string | null;
  boothNote: string | null;
  title: string;
  detail: string | null;
  status: 'ISSUED' | 'REDEEMED' | 'VOID';
  redeemedAt: string | null;
  issuedAt?: string | null;
}

export interface TicketView {
  ticketRef: string;
  claimed: boolean;
  /**
   * A registered ticket opened by a browser that has not answered its passkey: only the
   * event is filled in, and the page asks for the passkey before anything else.
   */
  locked?: boolean;
  /** HOLDER opened their own ticket; RECIPIENT opened a transfer link. */
  role: 'HOLDER' | 'RECIPIENT';
  transfer: TicketTransfer | null;
  seat: string | null;
  tier: string | null;
  holderEmailMasked: string | null;
  claimExpired: boolean;
  event: {
    name: string;
    venue: string | null;
    startsAt: string;
    gateOpensAt: string | null;
    exitScanRequired: boolean;
    reentryMode: string;
    /** When false, opening the link is the whole claim: no verification code is sent. */
    claimRequiresOtp: boolean;
  };
  presence: {
    inside: boolean;
    entryCount: number;
    reentryRemaining: number | null;
    reentryUntil: string | null;
  };
  /** Empty for most events; the screen shows nothing when there is nothing. */
  coupons: TicketCoupon[];
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

async function read(response: Response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new ApiError(data.error || '요청을 처리하지 못했어요. 다시 시도해 주세요.', response.status);
  }
  return data;
}

export function ticketBase(sessionId: string, token: string) {
  return `/api/tickets/${encodeURIComponent(sessionId)}/${encodeURIComponent(token)}`;
}

export async function fetchTicket(sessionId: string, token: string): Promise<TicketView> {
  const response = await fetch(ticketBase(sessionId, token));
  return read(response) as Promise<TicketView>;
}

export interface Crowding {
  zones: Array<{
    zone: string; inside: number; share: number; level: string;
    /** Set only where the organiser stated how many the place holds. */
    capacity: number | null;
    percent: number;
    /** CAPACITY reads against that number; RELATIVE only ranks the places. */
    basis: 'CAPACITY' | 'RELATIVE';
  }>;
  busyPercent: number;
  steadyPercent: number;
  measuredAt: string;
}

/** How busy each place is right now, as the gates have counted it. */
export async function fetchCrowding(sessionId: string, token: string): Promise<Crowding> {
  return read(await fetch(`${ticketBase(sessionId, token)}/crowding`)) as Promise<Crowding>;
}

export async function requestOtp(sessionId: string, token: string, email: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/otp`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email }),
  }));
}

export async function verifyOtp(sessionId: string, token: string, email: string, code: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/otp/verify`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, code }),
  }));
}

/**
 * Opens an entry or exit code on the browser whose passkey already opened this ticket.
 * Refused with 403 once that proof has lapsed; the page asks for the passkey again.
 */
export async function presentTicket(sessionId: string, token: string, direction: 'IN' | 'OUT',
    position: Record<string, string>): Promise<Grant> {
  return read(await mutate(`${ticketBase(sessionId, token)}/present`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ direction, ...position }),
  })) as Promise<Grant>;
}

/** One coupon's own code, to show at its booth; opened like the entry code, without a fresh passkey. */
export async function presentCoupon(sessionId: string, token: string, couponId: number): Promise<Grant> {
  return read(await mutate(`${ticketBase(sessionId, token)}/coupons/${couponId}/present`, { method: 'POST' })) as Promise<Grant>;
}

export async function cancelTransfer(sessionId: string, token: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/transfer/cancel`, { method: 'POST' }));
}

export async function reissueTicket(sessionId: string, token: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/recover`, { method: 'POST' }));
}

export interface FaceStatus {
  /** Why enrolment may be closed: already inside, or the ticket has travelled as a QR. */
  inside?: boolean;
  qrUsed?: boolean;
  consented: boolean;
  enrolled: boolean;
  consentVersion: string;
  purposes: string;
}

export async function fetchFaceStatus(sessionId: string, token: string): Promise<FaceStatus> {
  return read(await fetch(`${ticketBase(sessionId, token)}/face`)) as Promise<FaceStatus>;
}

export async function giveFaceConsent(sessionId: string, token: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/face/consent`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agreed: true }),
  }));
}

export async function enrollFace(sessionId: string, token: string, frames: string[]) {
  return read(await mutate(`${ticketBase(sessionId, token)}/face/enroll`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ frames }),
  }));
}

export async function withdrawFace(sessionId: string, token: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/face/withdraw`, { method: 'POST' }));
}

export async function gateFaceChallenge(gateId: string, gateToken: string) {
  return read(await fetch(`/api/gates/${encodeURIComponent(gateId)}/face/challenge`, {
    headers: gateHeaders(gateToken),
  }));
}

export async function gateFaceScan(gateId: string, gateToken: string, frames: string[], challenge?: string) {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/face`, {
    method: 'POST', headers: gateHeaders(gateToken, true), body: JSON.stringify({ frames, challenge }),
  });
  return read(response);
}

/**
 * This terminal's identity, kept for the life of the browser profile. A gate is held by
 * one terminal at a time, so the server needs to tell tablets apart.
 */
export function gateDeviceId(): string {
  const key = 'plink.gate.device';
  let id = localStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(key, id);
  }
  return id;
}

/** What every terminal request carries instead of a session: no cookie, so no CSRF token either. */
export function gateHeaders(gateToken: string, json = false): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Gate-Token': gateToken,
    'X-Gate-Device': gateDeviceId(),
  };
  if (json) headers['Content-Type'] = 'application/json';
  return headers;
}

export async function gateSync(gateId: string, gateToken: string, events: unknown[]) {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/sync`, {
    method: 'POST', headers: gateHeaders(gateToken, true), body: JSON.stringify({ events }),
  });
  return read(response);
}

export async function gateInfo(gateId: string, gateToken: string) {
  return read(await fetch(`/api/gates/${encodeURIComponent(gateId)}`, { headers: gateHeaders(gateToken) }));
}

/**
 * The terminal renewing its own validity. Only a terminal that is still valid and still
 * holds the gate can ask, so this is a tablet that has been working all week saying so.
 */
export async function gateRenew(gateId: string, gateToken: string) {
  return read(await fetch(`/api/gates/${encodeURIComponent(gateId)}/renew`, {
    method: 'POST', headers: gateHeaders(gateToken, true),
  }));
}

export async function gateScan(gateId: string, gateToken: string, code: string) {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/scan`, {
    method: 'POST', headers: gateHeaders(gateToken, true), body: JSON.stringify({ code }),
  });
  return read(response);
}

/** Giving mode: reads a code to give coupons, and answers with what this terminal may give. */
export async function gateGrantScan(gateId: string, gateToken: string, code: string) {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/grant`, {
    method: 'POST', headers: gateHeaders(gateToken, true), body: JSON.stringify({ code }),
  });
  return read(response);
}

/** The counter's choice after a giving-mode read: how many of each offer to add. */
export async function gateGrantConfirm(gateId: string, gateToken: string, pick: string,
    items: { offerId: number; count: number }[]) {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/grant/confirm`, {
    method: 'POST', headers: gateHeaders(gateToken, true), body: JSON.stringify({ pick, items }),
  });
  return read(response);
}

// ---- The room's chat and board, from the holder's ticket ----

export interface CommunitySummary {
  entered: boolean;
  latestChatId?: number;
  inside?: number;
  notice?: string | null;
  lastMessage?: { author: string; body: string };
  adCount?: number;
  latestAdId?: number;
  topAd?: { booth: string; title: string };
  /** An organiser notice raised on every holder's ticket, entered or not. */
  alert?: { id: number; body: string; createdAt: string };
}

export interface ChatMessage {
  id: number; author: string; staff: boolean; notice: boolean; mine: boolean;
  body: string; likes: number; liked: boolean; dislikes: number; disliked: boolean;
  createdAt: string; pinnedUntil: string | null;
  reply?: { id: number; author: string; body: string };
}

export interface ChatView {
  /** {@code banned}: the organiser put this ticket out of the chat; it reads but cannot write or press. */
  me: { nickname: string; canWrite: boolean; blocked: string | null; banned?: boolean; nextAt: string | null };
  cooldownSeconds: number; maxLength: number; hotLikes: number; inside: number;
  notice: ChatMessage | null; hot: ChatMessage | null;
  messages: ChatMessage[];
}

export interface AdPost {
  id: number; category: 'SALE' | 'PROGRAM' | 'NOTICE'; title: string; body?: string;
  booth: string; boothId: number | null; place: string | null;
  startsAt: string; endsAt: string | null; pinned: boolean; views: number; createdAt: string;
  imageId: string | null;
}

const community = (sessionId: string, token: string) => `${ticketBase(sessionId, token)}/community`;

export async function fetchCommunity(sessionId: string, token: string): Promise<CommunitySummary> {
  return read(await fetch(community(sessionId, token))) as Promise<CommunitySummary>;
}

export async function fetchChat(sessionId: string, token: string): Promise<ChatView> {
  return read(await fetch(`${community(sessionId, token)}/chat`)) as Promise<ChatView>;
}

export async function sendChat(sessionId: string, token: string, body: string, replyTo: number | null): Promise<ChatMessage> {
  return read(await mutate(`${community(sessionId, token)}/chat`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ body, replyTo }),
  })) as Promise<ChatMessage>;
}

/** Where a line stands after this holder pressed like or dislike on it. */
export interface Reaction { likes: number; dislikes: number; liked: boolean; disliked: boolean }

export async function likeChat(sessionId: string, token: string, id: number): Promise<Reaction> {
  return read(await mutate(`${community(sessionId, token)}/chat/${id}/like`, { method: 'POST' })) as Promise<Reaction>;
}

export async function dislikeChat(sessionId: string, token: string, id: number): Promise<Reaction> {
  return read(await mutate(`${community(sessionId, token)}/chat/${id}/dislike`, { method: 'POST' })) as Promise<Reaction>;
}

export async function reportChat(sessionId: string, token: string, id: number) {
  return read(await mutate(`${community(sessionId, token)}/chat/${id}/report`, { method: 'POST' }));
}

export async function fetchAds(sessionId: string, token: string): Promise<AdPost[]> {
  return read(await fetch(`${community(sessionId, token)}/ads`)) as Promise<AdPost[]>;
}

export async function fetchAd(sessionId: string, token: string, id: number): Promise<AdPost> {
  return read(await fetch(`${community(sessionId, token)}/ads/${id}`)) as Promise<AdPost>;
}

/** A notice's photo; the version in the query keeps a replaced photo from coming out of cache. */
export function adImageUrl(sessionId: string, token: string, ad: AdPost): string | null {
  return ad.imageId ? `${community(sessionId, token)}/ads/${ad.id}/image?v=${ad.imageId.slice(0, 8)}` : null;
}

/** What a board notice is about, as the page says it. */
export const AD_CATEGORY: Record<string, string> = { SALE: '할인·혜택', PROGRAM: '체험·프로그램', NOTICE: '운영 공지' };

// ---- A booth's own notices, from its terminal ----

export interface BoothAd extends AdPost { body: string; hidden: boolean; state: 'LIVE' | 'SCHEDULED' | 'ENDED' | 'HIDDEN' }

export async function gateAds(gateId: string, gateToken: string): Promise<BoothAd[]> {
  return read(await fetch(`/api/gates/${encodeURIComponent(gateId)}/ads`, { headers: gateHeaders(gateToken) })) as Promise<BoothAd[]>;
}

export async function gateAdSave(gateId: string, gateToken: string, body: Record<string, unknown>, id?: number): Promise<BoothAd> {
  const url = `/api/gates/${encodeURIComponent(gateId)}/ads${id ? `/${id}` : ''}`;
  return read(await fetch(url, {
    method: id ? 'PUT' : 'POST', headers: gateHeaders(gateToken, true), body: JSON.stringify(body),
  })) as Promise<BoothAd>;
}

export async function gateAdEnd(gateId: string, gateToken: string, id: number) {
  return read(await fetch(`/api/gates/${encodeURIComponent(gateId)}/ads/${id}/end`, {
    method: 'POST', headers: gateHeaders(gateToken),
  }));
}

/** Uploads a photo for a notice the booth is writing; it is attached when the notice is saved. */
export async function gateAdPhoto(gateId: string, gateToken: string, photo: Blob): Promise<string> {
  const form = new FormData();
  form.append('file', photo, 'photo.jpg');
  const result = await read(await fetch(`/api/gates/${encodeURIComponent(gateId)}/ads/images`, {
    method: 'POST', headers: gateHeaders(gateToken), body: form,
  })) as { imageId: string };
  return result.imageId;
}

/** A booth notice's photo, as an address the page can show: the terminal's token rides in a header. */
export async function gateAdPhotoUrl(gateId: string, gateToken: string, id: number): Promise<string> {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/ads/${id}/image`, { headers: gateHeaders(gateToken) });
  if (!response.ok) throw new Error('사진을 불러오지 못했어요.');
  return URL.createObjectURL(await response.blob());
}
