import { mutate } from '../auth';

export interface TicketView {
  ticketRef: string;
  claimed: boolean;
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
  return `/api/t/${encodeURIComponent(sessionId)}/${encodeURIComponent(token)}`;
}

export async function fetchTicket(sessionId: string, token: string): Promise<TicketView> {
  const response = await fetch(ticketBase(sessionId, token));
  return read(response) as Promise<TicketView>;
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

export async function reissueTicket(sessionId: string, token: string) {
  return read(await mutate(`${ticketBase(sessionId, token)}/recover`, { method: 'POST' }));
}

export async function gateInfo(gateId: string, gateToken: string) {
  return read(await fetch(`/api/gates/${encodeURIComponent(gateId)}`, {
    headers: { 'X-Gate-Token': gateToken },
  }));
}

export async function gateScan(gateId: string, gateToken: string, code: string) {
  const response = await fetch(`/api/gates/${encodeURIComponent(gateId)}/scan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Gate-Token': gateToken },
    body: JSON.stringify({ code }),
  });
  return read(response);
}
