import type { Verdict } from '../components/GateVerdict';

/**
 * What a booth's read answers with, for the booth's tablet and for a booth manager's
 * phone alike. The headline is the offer itself, because the person behind the counter
 * needs to know what to hand over, not that a check passed.
 */
export function couponVerdict(result: Record<string, unknown>): Omit<Verdict, 'at' | 'ledger'> {
  const title = String(result.title ?? '쿠폰');
  // Seat first, reference second: at a counter the seat is what gets said out loud.
  const ticket = [result.seat, result.ticketRef].filter(Boolean).join(' · ');
  if (result.outcome === 'ALREADY') {
    return { tone: 'repeat', headline: '이미 사용함', mark: title, detail: ticket };
  }
  // Whether this person still has something coming decides what is said next, so it is
  // a badge of its own rather than the tail of a line of small print.
  const remaining = typeof result.remaining === 'number' ? result.remaining : 0;
  const left = (result.left as { title: string; available: number }[] | undefined) ?? [];
  return {
    tone: 'admit', headline: title, detail: ticket,
    mark: remaining === 0 ? '이 부스 쿠폰은 이게 마지막'
      : `남은 쿠폰 ${left.map(choice => `${choice.title} ${choice.available}장`).join(' · ')
        || `${remaining}장`}`,
  };
}

/** What a giving-mode answer says, in the words the counter reads out. */
export function grantVerdict(result: Record<string, unknown>): Omit<Verdict, 'at' | 'ledger'> {
  return {
    tone: 'admit', headline: String(result.title ?? '쿠폰'), mark: '쿠폰 부여',
    detail: [result.seat, result.ticketRef].filter(Boolean).join(' · '),
  };
}
