import { mutate } from '../auth';

export interface TicketTransfer {
  status: string;
  toEmail: string;
  expiresAt: string;
}

export interface TicketView {
  ticketRef: string;
  claimed: boolean;
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

function gateHeaders(gateToken: string, json = false): Record<string, string> {
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
