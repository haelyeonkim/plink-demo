import { useMemo } from 'react';

/**
 * The fine-line rosette printed on banknotes, passports and ID cards. Here it is the
 * ticket's own mark of authenticity: it is drawn from the ticket's reference, so no two
 * tickets carry the same pattern, and it is plain SVG paths so it can be animated later
 * (turn a group, or move `d`) without redrawing anything.
 *
 * Each curve is a hypotrochoid; stacking a few with slightly different pen offsets is what
 * produces the moire that makes the pattern hard to copy.
 */
function hash(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i += 1) { h ^= text.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

/** Wheel sizes that close after a few turns, so a curve stays a few hundred points. */
const WHEELS = [
  { big: 45, small: 20 },   // 9 lobes
  { big: 40, small: 15 },   // 8 lobes
  { big: 42, small: 18 },   // 7 lobes
  { big: 48, small: 20 },   // 12 lobes
];

function curve(big: number, small: number, pen: number, phase: number): string {
  const gcd = (a: number, b: number): number => (b === 0 ? a : gcd(b, a % b));
  const turns = small / gcd(big, small);
  const steps = turns * 64;
  const ratio = (big - small) / small;
  let path = '';
  for (let i = 0; i <= steps; i += 1) {
    const t = (i / 64) * Math.PI * 2;
    const x = (big - small) * Math.cos(t) + pen * Math.cos(ratio * t + phase);
    const y = (big - small) * Math.sin(t) - pen * Math.sin(ratio * t + phase);
    path += `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`;
  }
  return `${path}Z`;
}

export default function Guilloche({ seed }: { seed: string }) {
  const rosettes = useMemo(() => {
    const h = hash(seed);
    const wheel = WHEELS[h % WHEELS.length];
    const turn = ((h >>> 3) % 360);
    const make = (offset: number, phase: number) => {
      const out: string[] = [];
      for (let k = 0; k < 11; k += 1) out.push(curve(wheel.big, wheel.small, 5 + k * 2.4 + offset, phase));
      return out;
    };
    return { turn, a: make(0, 0), b: make(1.2, 0.5) };
  }, [seed]);

  return (
    <svg className="guilloche" viewBox="-62 -62 124 124" aria-hidden="true" focusable="false">
      <g className="guilloche-a" transform={`rotate(${rosettes.turn})`}>
        {rosettes.a.map((d, i) => <path key={i} d={d} />)}
      </g>
      <g className="guilloche-b" transform={`rotate(${-rosettes.turn})`}>
        {rosettes.b.map((d, i) => <path key={i} d={d} />)}
      </g>
    </svg>
  );
}

/** The reference as it is read off a document: in fours, so it can be spoken and checked. */
export function Serial({ value }: { value: string }) {
  const groups = value.replace(/\s+/g, '').match(/.{1,4}/g) ?? [value];
  return (
    <p className="ticket-serial">
      <span>증서 번호</span>
      <b>{groups.join(' ')}</b>
    </p>
  );
}
