/** What the data console reads from /api/admin/data, and how it is written on screen. */

export interface EventRow { id: number; name: string; startsAt: string; tiers?: string[] }

export interface Overview {
  kpis: {
    issued: number; invited: number; bound: number; opened: number; admittedPeople: number; reentries: number;
    peakInside: number; peakAt: string | null; avgStayMinutes: number | null;
    couponsIssued: number; couponsRedeemed: number; chatMessages: number; chatReports: number; adViews: number;
    invitesCancelled: number; mailSent: number; mailFailed: number;
  };
  hourly: Array<{ at: string; entries: number; exits: number }>;
  gates: Array<{ gateId: number | string; name: string; admitted: number; denied: number; faceShare: number }>;
  booths: Array<{ boothId: number | string; name: string; redeemed: number }>;
  logs: { today: number; total: number; errors: number };
}

export interface Category { key: string; label: string; count: number }

export interface LogRow {
  id: number | string; at: string; category: string; categoryLabel: string; action: string; actionLabel: string;
  ok: boolean; result: string | null; summary: string | null;
  actorType: string | null; actorId: string | number | null; actorEmail: string | null;
  ticketId: number | null; ticketRef: string | null; sessionId: number | null; gateId: number | string | null;
  boothId: number | string | null; httpMethod: string | null; path: string | null; status: number | null;
  durationMs: number | null; ip: string | null; userAgent: string | null; device: string | null;
  requestId: string | null; detail: unknown;
}
export interface LogPage { total: number; page: number; size: number; rows: LogRow[] }

export type ResultFilter = 'ALL' | 'OK' | 'FAIL';

export interface Filters {
  sessionId?: string; from?: string; to?: string; categories?: string[]; result?: ResultFilter; q?: string;
}
export interface SavedView { id: number | string; name: string; filters: Filters }

export interface DatasetColumn { key: string; label: string; pii: boolean; defaultOn: boolean }
export interface Dataset {
  key: string; label: string; description: string; rows: number; internal: boolean; columns: DatasetColumn[];
}

export type Pii = 'AGGREGATE' | 'MASKED' | 'RAW';
export const PII_LABEL: Record<Pii, string> = { AGGREGATE: '숫자만', MASKED: '가린 정보', RAW: '원본' };

export type ExportStatus = 'OPEN' | 'EXPIRED' | 'CLOSED' | 'USED_UP';
export interface ExportRow {
  id: number | string; sessionId: number; sessionTitle: string; recipientName: string; recipientEmail: string;
  datasets: Array<{ key: string; label: string; rows: number }>; pii: Pii; note: string | null;
  createdAt: string; createdBy: string; expiresAt: string; maxDownloads: number | null; downloads: number;
  requireCode: boolean; status: ExportStatus; url: string | null;
}
export interface AccessRow {
  at: string; action: 'OPEN' | 'CODE_SENT' | 'VERIFIED' | 'REFUSED' | 'DOWNLOAD';
  file: string | null; ip: string | null; userAgent: string | null;
}

export async function read<T = unknown>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error((data as { error?: string }).error || '요청을 처리하지 못했어요.');
  return data as T;
}

export const getJson = async <T>(url: string, signal?: AbortSignal) => read<T>(await fetch(url, { signal }));

/** The query a filter set becomes; empty values are left out. */
export function filterQuery(filters: Filters, extra: Record<string, string> = {}): URLSearchParams {
  const query = new URLSearchParams();
  if (filters.sessionId) query.set('sessionId', filters.sessionId);
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  if (filters.categories && filters.categories.length > 0) query.set('categories', filters.categories.join(','));
  if (filters.result && filters.result !== 'ALL') query.set('result', filters.result);
  if (filters.q) query.set('q', filters.q);
  for (const [key, value] of Object.entries(extra)) query.set(key, value);
  return query;
}

export function filtersOf(params: URLSearchParams): Filters {
  const result = params.get('result');
  return {
    sessionId: params.get('sessionId') || undefined,
    from: params.get('from') || undefined,
    to: params.get('to') || undefined,
    categories: (params.get('categories') || '').split(',').map(value => value.trim()).filter(Boolean),
    result: result === 'OK' || result === 'FAIL' ? result : 'ALL',
    q: params.get('q') || undefined,
  };
}

/* ---- Writing it down ---- */

export const num = (value: number | null | undefined) => (value ?? 0).toLocaleString('ko-KR');

export function percent(part: number, whole: number): string {
  if (!whole) return '0%';
  const value = (part / whole) * 100;
  return `${value >= 10 || value === 0 ? Math.round(value) : value.toFixed(1)}%`;
}

const pad = (value: number) => String(value).padStart(2, '0');

/** "11. 8." */
export function day(iso: string): string {
  const date = new Date(iso);
  return `${date.getMonth() + 1}. ${date.getDate()}.`;
}
/** "15:42" */
export function clock(iso: string): string {
  const date = new Date(iso);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
/** "11. 8. 15:42" */
export function dayClock(iso: string): string {
  return `${day(iso)} ${clock(iso)}`;
}
export function seconds(iso: string): string {
  const date = new Date(iso);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
export function sameDay(a: string, b: string): boolean {
  return new Date(a).toDateString() === new Date(b).toDateString();
}

/** "2시간 14분" */
export function duration(minutes: number | null): string {
  if (minutes == null) return '—';
  const total = Math.round(minutes);
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest}분`;
  return rest === 0 ? `${hours}시간` : `${hours}시간 ${rest}분`;
}

export function bytes(value: number): string {
  if (value < 1024) return `${value}B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)}KB`;
  return `${(value / 1024 / 1024).toFixed(1)}MB`;
}

/** A Date as ISO-8601 with this browser's offset: "2026-11-08T12:00:00+09:00". */
export function toOffsetIso(date: Date): string {
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const abs = Math.abs(offset);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    + `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

/** An ISO string as a datetime-local input wants it: "2026-11-08T12:00". */
export function toLocalInput(iso: string | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
export function fromLocalInput(value: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : toOffsetIso(date);
}

/** The event's own day, midnight to midnight. */
export function eventDay(startsAt: string): { from: string; to: string } {
  const start = new Date(startsAt);
  start.setHours(0, 0, 0, 0);
  const end = new Date(start);
  end.setDate(end.getDate() + 1);
  return { from: toOffsetIso(start), to: toOffsetIso(end) };
}

export function rangeText(from?: string, to?: string): string {
  if (!from && !to) return '';
  if (from && to) return sameDay(from, to) ? `${dayClock(from)}–${clock(to)}` : `${dayClock(from)} – ${dayClock(to)}`;
  return from ? `${dayClock(from)}부터` : `${dayClock(to!)}까지`;
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** Sessions, newest first, for the event pickers. */
export function sortEvents(rows: EventRow[]): EventRow[] {
  return [...rows].sort((a, b) => new Date(b.startsAt).getTime() - new Date(a.startsAt).getTime());
}
