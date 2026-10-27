/**
 * The mark every screen uses to say a check actually ran.
 *
 * <p>It is one drawing in two halves: a lock that holds while the work is in flight, and
 * the verdict that replaces it when the answer comes back. Keeping the lock in the
 * picture is the point - a tick on its own only says "fine", while a lock that visibly
 * had to open says something was locked to begin with.
 *
 * <p>Colour never carries the verdict alone: each tone has its own glyph, so the screen
 * reads the same to somebody who cannot tell the green from the red.
 */
export type SealTone = 'working' | 'admit' | 'exit' | 'repeat' | 'deny';

/** Stroked with pathLength="1", so the draw animation needs no measuring. */
const GLYPH: Record<SealTone, string> = {
  working: '',
  admit: 'M21 33 L29 41 L44 23',
  exit: 'M20 32 H41 M34 25 L41 32 L34 39',
  repeat: 'M23 23 L31 32 L23 41 M33 23 L41 32 L33 41',
  deny: 'M23 23 L41 41 M41 23 L23 41',
};

export default function SealMark({ tone, className = '' }: { tone: SealTone; className?: string }) {
  const settled = tone !== 'working';
  return (
    <svg className={`seal-mark seal-${tone} ${className}`} viewBox="0 0 64 64" aria-hidden="true">
      <circle className="seal-ring" cx="32" cy="32" r="28" pathLength="1" />
      <g className="seal-lock">
        {/* The shackle lifts off the body rather than fading: an opening, not a swap. */}
        <path className="seal-shackle" d="M24 31 V25 a8 8 0 0 1 16 0 v6" />
        <rect className="seal-body" x="19" y="31" width="26" height="20" rx="5" />
      </g>
      {settled && <path className="seal-glyph" d={GLYPH[tone]} pathLength="1" />}
    </svg>
  );
}
