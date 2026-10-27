import SealMark, { type SealTone } from './SealMark';
import { clockText, useTyped } from '../motion';

/**
 * What the holder's phone shows the moment the gate reads them.
 *
 * <p>It arrives on the live channel, so the phone is not guessing: the screen changes
 * because the ledger changed. That is the whole reassurance - the code the holder was
 * pointing at the camera is gone, and in its place is the row that was written about
 * them, down to the second and the gate.
 */
export interface Movement {
  outcome: string;
  direction: string;
  gateLabel?: string | null;
  zone?: string | null;
  at: string;
}

const VERDICTS: Record<string, { tone: SealTone; headline: string }> = {
  ADMITTED: { tone: 'admit', headline: '입장이 확인됐어요' },
  EXITED: { tone: 'exit', headline: '퇴장이 확인됐어요' },
  DUPLICATE: { tone: 'repeat', headline: '이미 처리된 스캔이에요' },
  DENIED: { tone: 'deny', headline: '입장이 거부됐어요' },
};

export default function ScanFlash({ movement, spent, inside }: {
  movement: Movement;
  /** True when a QR was on screen: that code is now used up, and says so. */
  spent: boolean;
  inside: boolean;
}) {
  const verdict = VERDICTS[movement.outcome] ?? VERDICTS.ADMITTED;
  const at = clockText(movement.at);
  const ledger = useTyped(`기록됨 ${at}${movement.gateLabel ? ` · ${movement.gateLabel}` : ''}`);
  return (
    <div className={`scan-flash verdict-${verdict.tone}${spent ? ' flash-spent' : ''}`}
      role="status" aria-live="assertive">
      <SealMark tone={verdict.tone} className="flash-seal" />
      <strong className="flash-headline">{verdict.headline}</strong>
      {spent && <p className="flash-note">이 코드는 방금 사용되어 폐기됐어요.</p>}
      <dl className="flash-state">
        <dt>상태</dt><dd>{inside ? '장내' : '장외'}</dd>
        <dt>시각</dt><dd>{at}</dd>
        {movement.gateLabel && <><dt>게이트</dt><dd>{movement.gateLabel}</dd></>}
        {movement.zone && <><dt>구역</dt><dd>{movement.zone}</dd></>}
      </dl>
      <code className="flash-ledger">{ledger}</code>
    </div>
  );
}
