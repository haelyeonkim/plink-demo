import SealMark, { type SealTone } from './SealMark';
import { useTyped } from '../motion';

/**
 * What the terminal shows the moment a read is decided.
 *
 * <p>The staff member is a metre away and looking at the visitor, not the tablet, so the
 * verdict takes the whole frame for a couple of seconds: one colour, one glyph, one word.
 * Underneath it the ledger line types itself out, because the reassuring part of a gate
 * is not that it beeped - it is that the passage was written down.
 */
export interface Verdict {
  tone: SealTone;
  headline: string;
  detail: string;
  /** A badge under the headline: at a stand, whether anything is still owed. */
  mark?: string;
  ledger: string;
  /** When it was decided; also the remount key, so a repeat read replays the animation. */
  at: number;
}

export default function GateVerdict({ verdict, rest }: { verdict: Verdict; rest?: number }) {
  const ledger = useTyped(verdict.ledger);
  return (
    <div className={`gate-verdict verdict-${verdict.tone}`} role="status" aria-live="assertive">
      <span className="verdict-wash" aria-hidden="true" />
      <SealMark tone={verdict.tone} className="verdict-seal" />
      <strong className="verdict-headline">{verdict.headline}</strong>
      {verdict.mark && <span className="verdict-mark">{verdict.mark}</span>}
      <span className="verdict-detail">{verdict.detail}</span>
      <code className="verdict-ledger">{ledger}</code>
      {/* The terminal is not reading while this is up. The bar draining is the answer
          to the only question the next person in the queue has. */}
      {rest ? (
        <span className="verdict-rest" aria-hidden="true"
          style={{ animationDuration: `${rest}ms` }} />
      ) : null}
    </div>
  );
}
