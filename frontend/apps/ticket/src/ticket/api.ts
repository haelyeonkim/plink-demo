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
    /** Registering needs no email or phone typed back: the organiser chose so. */
    claimSkipCheck: boolean;
    /** The ticket went to a phone too, so the number can be typed instead of the email. */
    claimByPhone?: boolean;
    /** Whether the event admits by face; when off, the ticket says nothing about faces. */
    faceEnabled?: boolean;
    /** Opening a code is checked against the venue: only then is the phone's position asked for. */
    geoCheck: boolean;
    /** The days this ticket admits and where it stands today; null for an event of one day. */
    schedule?: TicketSchedule | null;
  };
  presence: {
    inside: boolean;
    entryCount: number;
    reentryRemaining: number | null;
    reentryUntil: string | null;
  };
  /** Empty for most events; the screen shows nothing when there is nothing. */
  coupons: TicketCoupon[];
  /** On an invitee's ticket, even before registering: who invited it (masked), and how many are coming. */
  invitation?: { by: string; groupSize: number } | null;
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

/** A fresh link to the ticket's own email; from a phone without the passkey, the email or phone is typed back. */
export async function reissueTicket(sessionId: string, token: string, contact?: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/recover`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ contact: contact ?? null }),
  }));
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
  /** Entered and inside now: the chat is the room's, and closes when they step out. */
  chatOpen?: boolean;
  /** Why the chat is shut to an entered holder: stepped out, or banned. */
  chatClosed?: string;
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

// ---- Invites, and the group they make ----

/**
 * Someone in the group as this holder may see them. The inviter sees the addresses they
 * typed in full and gets {@code ticketId} for their own invitees; everyone else is masked.
 * {@code state} is null when where they are is not shown to this holder, or not yet.
 */
export interface GroupMember {
  ticketId: number | null;
  role: 'INVITER' | 'GUEST';
  me: boolean;
  /** What the inviter calls them; null shows the address. */
  name: string | null;
  email: string;
  registered: boolean;
  revoked: boolean;
  state: 'NOT_ENTERED' | 'INSIDE' | 'OUTSIDE' | null;
  /** Inside since, or out since. */
  since: string | null;
  /** When the invite went; the inviter's own invitees only. */
  sentAt: string | null;
  canResend: boolean;
  resendsLeft: number;
}

export interface InviteView {
  quota: number; used: number; remaining: number; canInvite: boolean;
  /** Cancels are capped at the ticket's invite count, so inviting and cancelling cannot loop mail. */
  canCancel: boolean;
  cancelsLeft: number;
  /** Who in a group sees the others come in, as the event has it. */
  status: 'OFF' | 'INVITER' | 'GROUP';
  /** Set on an invitee's ticket: who invited them, masked. */
  invitedBy: string | null;
  /** Whether where everyone is is shown to this holder now. */
  statesShown: boolean;
  /** The event's first venue day, YYYY-MM-DD: states show from then. */
  statesFrom: string;
  beforeDay: boolean;
  updatedAt: string;
  /** For the inviter: themselves, then their invitees oldest first. */
  members: GroupMember[];
}

const inviteBase = (sessionId: string, token: string) => `${ticketBase(sessionId, token)}/invites`;
const JSON_BODY = { 'Content-Type': 'application/json' };

export async function fetchInvites(sessionId: string, token: string): Promise<InviteView> {
  return read(await fetch(inviteBase(sessionId, token))) as Promise<InviteView>;
}

export async function sendInvite(sessionId: string, token: string, email: string, name: string | null): Promise<GroupMember> {
  return read(await mutate(inviteBase(sessionId, token), {
    method: 'POST', headers: JSON_BODY, body: JSON.stringify({ email, name }),
  })) as Promise<GroupMember>;
}

/** What the inviter calls one of their invitees; null goes back to the masked address. */
export async function renameInvite(sessionId: string, token: string, ticketId: number,
    name: string | null): Promise<GroupMember> {
  return read(await mutate(`${inviteBase(sessionId, token)}/${ticketId}/name`, {
    method: 'PUT', headers: JSON_BODY, body: JSON.stringify({ name }),
  })) as Promise<GroupMember>;
}

/** Mails the same link again. Refused with 429, saying when, if asked too soon or too often. */
export async function resendInvite(sessionId: string, token: string,
    ticketId: number): Promise<GroupMember & { mailed: boolean }> {
  return read(await mutate(`${inviteBase(sessionId, token)}/${ticketId}/resend`, { method: 'POST' })) as
    Promise<GroupMember & { mailed: boolean }>;
}

export async function cancelInvite(sessionId: string, token: string, ticketId: number) {
  return read(await mutate(`${inviteBase(sessionId, token)}/${ticketId}`, { method: 'DELETE' }));
}

// ---- The venue tab ----

export type VenuePoint = [number, number];
/** What the organiser drew on a floor, in units of a plan 1000 wide. */
export type VenueShape =
  | { id: string; type: 'area'; pts: VenuePoint[]; name: string; color: string; desc: string; showName: boolean }
  | { id: string; type: 'line'; pts: VenuePoint[]; name: string }
  | { id: string; type: 'label'; x: number; y: number; name: string };
/** One floor: a plan image, drawn shapes, or both. Height is in the same units as the shapes. */
export interface VenueFloor { id: string; name: string; imageId: string | null; height?: number | null; shapes?: VenueShape[] }
export interface VenuePlace {
  /** The floor a pin is on, by the floor's id: positions move when a floor is taken out. */
  name: string; where: string; floorId: string | null; x: number | null; y: number | null; tags: string[];
}
export interface VenueDay { label: string; date: string | null }
export interface VenueItem {
  day: number; start: string; end: string | null; stage: number | null; title: string; note: string;
}

/** One section of the venue tab; what `content` holds depends on its kind. */
export type VenueSection = { id: number; title: string } & (
  | { kind: 'MAP'; content: { floors: VenueFloor[] } }
  | { kind: 'PLACES'; content: { mapId: number | null; tags: string[]; places: VenuePlace[] } }
  | { kind: 'TEXT'; content: { body: string } }
  | { kind: 'TIMETABLE'; content: { days: VenueDay[]; stages: string[]; items: VenueItem[] } }
  | { kind: 'LINK'; content: { url: string; description: string } }
  | { kind: 'IMAGE'; content: { images: VenueImage[] } }
);
/** A picture as it is - a poster, a seating chart - with an optional line under it. */
export interface VenueImage { imageId: string; caption: string }

export interface VenueView { venue: string | null; sections: VenueSection[] }

const venueBase = (sessionId: string, token: string) => `${ticketBase(sessionId, token)}/venue`;

export async function fetchVenue(sessionId: string, token: string): Promise<VenueView> {
  return read(await fetch(venueBase(sessionId, token))) as Promise<VenueView>;
}

export function venueImageUrl(sessionId: string, token: string, imageId: string): string {
  return `${venueBase(sessionId, token)}/images/${imageId}`;
}

// ---- An event over several days ----

export interface TicketDay {
  date: string; label: string; weekday: string; opensAt: string | null; closesAt: string | null;
  closed: boolean; today: boolean; admits: boolean;
  /** Before today, in the venue's calendar. */
  past: boolean;
}
export interface TicketSchedule {
  /** The ticket's pass by name; null admits every day. */
  pass: string | null;
  days: TicketDay[];
  /** OPEN: may come in now. CLOSED: not today or not at this hour. ENDED: the event is over. */
  state: 'OPEN' | 'CLOSED' | 'ENDED';
  message: string | null;
  endsAt: string | null;
}
