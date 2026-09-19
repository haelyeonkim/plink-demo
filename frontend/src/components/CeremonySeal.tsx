import SealMark from './SealMark';
import type { CeremonyStage } from '../ticket/passkey';

/**
 * The passkey ceremony, shown while it runs.
 *
 * <p>Registering a ticket and opening one are the two moments where the holder is asked
 * to trust something invisible: a key is created on this phone, or a signature is made
 * with it. The screen narrates the three real steps as they happen and then closes the
 * seal, so "등록됐어요" is the end of something the holder watched rather than a word
 * that appeared after a pause.
 */
export type SealState = CeremonyStage | 'done' | 'failed';

export interface Ceremony {
  kind: 'claim' | 'open';
  state: SealState;
}

const STEPS: Record<Ceremony['kind'], [string, string, string]> = {
  claim: ['인증 요청을 받는 중', '이 기기에서 지문·얼굴 확인', '이 기기에만 입장권 봉인'],
  open: ['입장 요청을 만드는 중', '이 기기에서 지문·얼굴 확인', '서버가 서명을 검증'],
};

const TITLES: Record<Ceremony['kind'], Record<'working' | 'done' | 'failed', string>> = {
  claim: {
    working: '이 휴대폰에 입장권을 묶는 중',
    done: '이 휴대폰에서만 열리는 입장권이 됐어요',
    failed: '등록을 마치지 못했어요',
  },
  open: {
    working: '본인 확인 중',
    done: '본인 확인 완료',
    failed: '확인하지 못했어요',
  },
};

const ORDER: CeremonyStage[] = ['preparing', 'signing', 'verifying'];

export default function CeremonySeal({ ceremony }: { ceremony: Ceremony }) {
  const { kind, state } = ceremony;
  const done = state === 'done';
  const failed = state === 'failed';
  // Everything before the current step has already happened; after it, nothing yet has.
  const reached = done ? STEPS[kind].length : ORDER.indexOf(state as CeremonyStage);

  return (
    <div className="ceremony-seal" role="status" aria-live="polite">
      <SealMark tone={failed ? 'deny' : done ? 'admit' : 'working'} className="ceremony-mark" />
      <strong>{TITLES[kind][failed ? 'failed' : done ? 'done' : 'working']}</strong>
      <ol className="ceremony-steps">
        {STEPS[kind].map((step, index) => (
          <li key={step} className={
            index < reached ? 'step-done' : index === reached && !failed ? 'step-now' : 'step-wait'}>
            {step}
          </li>
        ))}
      </ol>
      <p className="hint-text">
        {failed
          ? '다시 시도하면 처음부터 안전하게 진행돼요.'
          : done
            ? '키는 이 기기를 떠나지 않아요. 서버는 서명만 확인합니다.'
            : '비밀번호는 오가지 않아요. 이 기기 안의 키가 서명만 만듭니다.'}
      </p>
    </div>
  );
}
