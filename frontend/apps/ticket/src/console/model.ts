/** What the ticket console reads from the server, and how it says it. */

export interface SessionRow {
  id: number; name: string; venue: string | null; startsAt: string; gateOpensAt: string | null;
  reentryMode: string; reentryMax: number; reentryGraceMinutes: number; reentryCooldownSeconds: number;
  exitScanRequired: boolean; unmatchedExit: string; autoExitAfterMinutes: number;
  claimRequiresOtp: boolean;
  /** A rehearsal: recipient emails are shown in full in the console's lists. */
  testEvent: boolean;
  /** Close open entries once the venue's day turns over, at this hour (venue time). */
  dayCloseExit: boolean; dayCloseHour: number;
  seats: string[]; tiers: string[];
  /** Issued, registered and inside right now; the list of events says them without opening each one. */
  ticketCount?: number; boundCount?: number; insideCount?: number;
  crowdBusyPercent: number; crowdSteadyPercent: number;
  /** What a ticket in this event carries, as the organiser defined it. */
  fields: TicketFieldRow[];
  /** Place to capacity, for the places the organiser has measured. */
  zoneCapacity: Record<string, number>;
}
export interface TicketFieldRow { id: number; label: string; kind: 'SEAT' | 'TIER' | 'CUSTOM'; values: string[] }

export interface TicketRow {
  ticketId: number; ticketRef: string; seat: string | null; status: string;
  issuedToEmail: string; holderEmail: string | null; reissueCount: number;
  tier: string | null;
  inside: boolean; entryCount: number; reentryCount: number;
  lastEventAt: string | null; lastExitAt: string | null; insideSince: string | null;
  phone: string | null; deliveredVia: string | null;
  attributes: Record<string, string>;
}

/** Whether an event is still ahead, is today, or has been, by the calendar day. */
export function eventWhen(startsAt: string): 'upcoming' | 'today' | 'past' {
  const start = new Date(startsAt);
  const day = (d: Date) => d.getFullYear() * 10000 + d.getMonth() * 100 + d.getDate();
  const today = day(new Date());
  return day(start) === today ? 'today' : day(start) < today ? 'past' : 'upcoming';
}

/** What the ticket itself is: issued, claimed by a person, or switched off. */
export function ticketState(row: TicketRow): { label: string; tone: string } {
  if (row.status === 'REVOKED') return { label: '비활성', tone: 'off' };
  if (row.status === 'TRANSFER_PENDING') return { label: '양도 중', tone: 'warn' };
  if (row.holderEmail) return { label: '등록 완료', tone: 'ok' };
  return { label: '미등록', tone: 'wait' };
}

/** Where the holder is, which is a different question from what the ticket is. */
export function presenceState(row: TicketRow): { label: string; tone: string; since: string | null } {
  if (row.inside) return { label: '장내', tone: 'in', since: row.insideSince };
  if (row.entryCount > 0) return { label: '퇴장', tone: 'out', since: row.lastExitAt ?? row.lastEventAt };
  return { label: '미입장', tone: 'wait', since: null };
}

export function shortTime(value: string | null): string {
  if (!value) return '';
  return new Date(value).toLocaleString('ko-KR', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

export interface IssuedTicket {
  ticketId: number; ticketRef: string; url: string; deliveredVia: string;
  /** Where the link should appear: under the issue form, or in the row it came from. */
  from: 'issue' | 'row';
}

export interface GateRow {
  gateId: string; label: string | null; zone: string | null; direction: string;
  role: string; boothId: number | null;
  boundDevice: string | null; lastSeenAt: string | null;
  setupUrl: string | null; setupCode: string | null; setupExpiresAt: string | null;
  /** Null for a terminal registered before tokens had an end date. */
  tokenExpiresAt: string | null; tokenExpired: boolean;
  /** Offers this terminal may give, and whether it gives them by itself on entry. */
  grants: { offerId: number; auto: boolean }[];
}

/** Whether a terminal admits anybody, which is what giving on entry hangs on. */
export const admitsPeople = (gate: GateRow) => gate.role !== 'BOOTH' && gate.direction !== 'OUT';

/** How a terminal's remaining time reads: the number of days, and how alarming it is. */
export function validity(gate: GateRow): { label: string; tone: string } {
  if (gate.tokenExpiresAt === null) return { label: '유효기간 없음', tone: 'wait' };
  const days = Math.ceil((new Date(gate.tokenExpiresAt).getTime() - Date.now()) / 86400000);
  if (gate.tokenExpired || days <= 0) return { label: '유효기간 지남', tone: 'off' };
  return { label: `유효기간 ${days}일 남음`, tone: days <= 3 ? 'warn' : 'ok' };
}

/** Something a booth may give, and how it has gone. */
export interface OfferRow {
  offerId: number; title: string; detail: string | null; active: boolean;
  issued: number; redeemed: number; voided: number; waiting: number;
}

export interface BoothRow {
  boothId: number; name: string; note: string | null;
  offers: OfferRow[];
}

/** What the console needs from the tickets without loading the tickets. */
export interface TicketSummary {
  counts: { all: number; unclaimed: number; bound: number; inside: number; revoked: number; live: number };
  seats: string[]; tiers: string[]; attributes: Record<string, string[]>;
}

export type TicketFilter = 'ALL' | 'UNCLAIMED' | 'BOUND' | 'INSIDE' | 'REVOKED';
/** What happens to somebody who left without scanning out. */
export const UNMATCHED_LABEL: Record<string, string> = {
  STRICT: '안내 데스크에서만 정리',
  LENIENT: '다시 들어올 때 자동 보정',
  AUTO_EXIT: '시간이 지나면 자동 퇴장',
};
/** 0 -> "자정", 5 -> "새벽 5시", 14 -> "오후 2시": how the hour is said, not written. */
export function hourLabel(hour: number): string {
  if (hour === 0) return '자정';
  if (hour < 6) return `새벽 ${hour}시`;
  if (hour < 12) return `오전 ${hour}시`;
  if (hour === 12) return '정오';
  return `오후 ${hour - 12}시`;
}

/** What a gate does, in the words on the screen rather than the ones in the database. */
export const DIRECTION_LABEL: Record<string, string> = { IN: '입장 전용', OUT: '퇴장 전용', BIDIRECTIONAL: '입·퇴장 겸용' };
export type CouponFilter = 'ALL' | 'ISSUED' | 'REDEEMED' | 'VOID';
/** The most of one offer a ticket can hold; the server says the same. */
export const MAX_PER_TICKET = 20;
export const PAGE_SIZE = 50;

export interface CouponRow {
  couponId: number; boothId: number | null; booth: string | null; offerId: number | null;
  title: string; detail: string | null;
  status: string; ticketId: number; ticketRef: string | null; seat: string | null;
  issuedToEmail: string | null; redeemedAt: string | null;
}

export interface Occupancy {
  inside: number; everEntered: number; tickets: number; bound: number;
  stillInside: Array<Record<string, unknown>>;
  recent: Array<Record<string, unknown>>;
  byGate: Array<Record<string, unknown>>;
  byZone: Array<Record<string, unknown>>;
}

export function text(value: unknown): string { return value == null ? '' : String(value); }
export function count(value: unknown): number { return Number(value ?? 0); }

/** The ledger stores outcomes in the wire vocabulary; the console reads them aloud. */
export const OUTCOMES: Record<string, string> = {
  ADMITTED: '입장', EXITED: '퇴장', DENIED: '거부', DUPLICATE: '중복', TRANSFERRED: '양도',
};

/** "yyyy-MM-ddTHH:mm" in the operator's own zone, which is what the input speaks. */
export function localInput(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export async function read(response: Response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || '요청을 처리하지 못했어요.');
  return data;
}

/** "11.08 (일) 10:00": a date as a table cell reads it, with the weekday. */
export function dayTime(value: string | null): string {
  if (!value) return '';
  const d = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}.${pad(d.getDate())} (${'일월화수목금토'[d.getDay()]}) `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** "09:30": the time alone, for a day the row has already named. */
export function clock(value: string | null): string {
  if (!value) return '';
  return new Date(value).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false });
}

/** A share of a whole, rounded, and 0 when there is no whole. */
export function percent(part: number, whole: number): number {
  return whole > 0 ? Math.round((part / whole) * 100) : 0;
}

/** One ticket's movements and coupons, for the detail beside the ticket list. */
export interface TicketHistory {
  ticketId: number; boundAt: string | null;
  movements: Array<{ direction: string | null; gateId: string | null; gateLabel: string | null;
    method: string | null; result: string; reason: string | null; occurredAt: string | null }>;
  coupons: Array<{ couponId: number; booth: string | null; title: string; status: string; redeemedAt: string | null }>;
}

/** How a link reached its holder, as the console says it. */
export const DELIVERY: Record<string, string> = {
  EMAIL: '메일', SMS: '문자', 'EMAIL+SMS': '메일·문자', LINK: '직접 전달',
};
