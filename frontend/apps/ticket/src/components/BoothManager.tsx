import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import Toast from '@shared/components/Toast';
import { ApiError } from '../ticket/api';
import { passkeyError, supportsPasskeys, type CeremonyStage } from '../ticket/passkey';
import {
  boothPasskey, fetchInvite, managerClient, requestBoothOtp, verifyBoothOtp, type BoothInvite,
} from '../booth/api';
import BoothConsole, { MANAGER_TABS, type BoothTab } from './booth/BoothConsole';

type Phase =
  | { kind: 'loading' }
  | { kind: 'missing'; message: string }
  | { kind: 'revoked'; message: string }
  | { kind: 'claim' }
  | { kind: 'signin' }
  | { kind: 'console' };

const STAGE_TEXT: Record<CeremonyStage, string> = {
  preparing: '요청을 만드는 중…',
  signing: '이 기기에서 지문·얼굴을 확인해 주세요…',
  verifying: '서버가 확인하는 중…',
};

/**
 * A booth manager's page: the link the organiser sent, opened on the manager's phone.
 *
 * <p>The first open proves the mailbox with a code and binds a passkey, the way a ticket
 * holder's first open does; every later open only asks for the passkey. Once signed in
 * the page is the booth's own console, and nothing else of the event.
 */
export default function BoothManager() {
  const { token = '' } = useParams<{ token: string }>();
  const [invite, setInvite] = useState<BoothInvite | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  // Kept here rather than in the console, so signing in again lands where the manager was.
  const [tab, setTab] = useState<BoothTab>('overview');
  const client = useMemo(() => managerClient(token), [token]);

  const load = useCallback(async () => {
    try {
      const next = await fetchInvite(token);
      setInvite(next);
      setPhase(next.signedIn ? { kind: 'console' } : next.status === 'INVITED' ? { kind: 'claim' } : { kind: 'signin' });
    } catch (err) {
      const message = err instanceof Error ? err.message : '';
      if (err instanceof ApiError && err.status === 410) {
        setPhase({ kind: 'revoked', message });
      } else {
        setPhase({ kind: 'missing', message: err instanceof ApiError && err.status === 404
          ? '열 수 없는 링크예요. 주최 측에 새 링크를 요청해 주세요.'
          : message || '부스 정보를 불러오지 못했어요. 잠시 후 다시 열어 주세요.' });
      }
    }
  }, [token]);
  useEffect(() => { void load(); }, [load]);

  /** A console request that came back saying this person may no longer be here. */
  const lost = useCallback((err: ApiError) => {
    if (err.status === 410) { setPhase({ kind: 'revoked', message: err.message }); return; }
    if (err.status === 401) {
      setError(err.message || '패스키로 다시 로그인해 주세요.');
      setPhase({ kind: 'signin' });
      return;
    }
    // A 403 can mean the manager was just switched off or the booth is gone; the link
    // itself says which, so the page asks it again rather than guessing.
    setError(err.message);
    void load();
  }, [load]);

  const toast = <Toast notice={notice} error={error} onNotice={setNotice} onError={setError} />;

  if (phase.kind === 'loading') {
    return <section className="page-section page-tight"><p className="hint-text center">불러오는 중…</p></section>;
  }
  if (phase.kind === 'missing' || phase.kind === 'revoked') {
    return (
      <section className="page-section page-tight ticket-page">
        <div className="access-card">
          <h2>{phase.kind === 'revoked' ? '관리자 권한이 해지됐어요' : '링크를 열 수 없어요'}</h2>
          <p className="hint-text">
            {phase.kind === 'revoked'
              ? '이 부스를 더 이상 관리할 수 없어요. 다시 맡게 되면 주최 측에 새 링크를 요청해 주세요.'
              : phase.message}
          </p>
        </div>
      </section>
    );
  }
  if (phase.kind === 'console') {
    return <>
      <BoothConsole client={client} tabs={MANAGER_TABS} tab={tab} onTab={setTab}
        onLost={lost} onNotice={setNotice} onError={setError} />
      {toast}
    </>;
  }
  return <>
    <BoothSignIn token={token} invite={invite!} claim={phase.kind === 'claim'}
      onSignedIn={() => { setError(''); setNotice('로그인했어요.'); setPhase({ kind: 'console' }); }}
      onError={setError} onNotice={setNotice} onLost={lost} />
    {toast}
  </>;
}

/** The link's first open (code, then a passkey) or a later one (just the passkey). */
function BoothSignIn({ token, invite, claim, onSignedIn, onError, onNotice, onLost }: {
  token: string; invite: BoothInvite; claim: boolean; onSignedIn: () => void;
  onError: (text: string) => void; onNotice: (text: string) => void; onLost: (err: ApiError) => void;
}) {
  const [step, setStep] = useState<'email' | 'code' | 'passkey'>('email');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [stage, setStage] = useState<CeremonyStage | null>(null);
  const passkeys = supportsPasskeys();

  async function guard(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); onError(''); onNotice('');
    try { await action(); }
    catch (err) {
      if (err instanceof ApiError && err.status === 410) { onLost(err); return; }
      onError(passkeyError(err) || '요청을 처리하지 못했어요. 다시 시도해 주세요.');
    } finally { setBusy(false); setStage(null); }
  }

  const sendCode = () => guard(async () => {
    await requestBoothOtp(token, email.trim());
    setStep('code');
    onNotice('인증번호를 보냈어요. 메일함을 확인해 주세요.');
  });
  const checkCode = () => guard(async () => {
    await verifyBoothOtp(token, email.trim(), code);
    setStep('passkey');
    onNotice('이메일 확인이 끝났어요. 이제 이 기기에 패스키를 등록해 주세요.');
  });
  const ceremony = () => guard(async () => {
    await boothPasskey(token, setStage);
    onSignedIn();
  });

  return (
    <section className="page-section page-tight ticket-page booth-manager">
      <div className="access-card">
        <p className="eyebrow center"><span></span> {invite.eventName}</p>
        <h2>{invite.boothName}</h2>
        <p className="muted bm-sub">
          부스 관리자{invite.boothNote ? ` · ${invite.boothNote}` : ''}
        </p>
        {!passkeys && (
          <p className="error-text" role="alert">
            이 브라우저에서는 패스키를 쓸 수 없어요. 최신 Safari나 Chrome에서 링크를 열어 주세요.
          </p>
        )}
        {claim ? (<>
          <p className="hint-text">
            초대받은 이메일({invite.emailMasked})로 본인 확인을 한 뒤, 이 휴대폰에 패스키를 등록해요.
            다음부터는 이 링크를 열고 패스키로 바로 들어와요.
          </p>
          {step === 'email' && (
            <form onSubmit={e => { e.preventDefault(); void sendCode(); }}>
              <div className="field">
                <label htmlFor="bm-email">초대받은 이메일</label>
                <input id="bm-email" type="email" autoComplete="email" required value={email}
                  onChange={e => setEmail(e.target.value)} placeholder="you@example.com" />
              </div>
              <button className="btn-primary" type="submit" disabled={busy || !email.trim()}>인증번호 받기</button>
            </form>
          )}
          {step === 'code' && (
            <form onSubmit={e => { e.preventDefault(); void checkCode(); }}>
              <div className="field">
                <label htmlFor="bm-code">인증번호 6자리</label>
                <input id="bm-code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} required
                  value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} />
              </div>
              <button className="btn-primary" type="submit" disabled={busy || code.length !== 6}>확인</button>
              <button className="btn-secondary" type="button" disabled={busy}
                onClick={() => { setStep('email'); setCode(''); }}>이메일 다시 입력</button>
            </form>
          )}
          {step === 'passkey' && (
            <button className="btn-primary" type="button" onClick={() => void ceremony()} disabled={busy || !passkeys}>
              {stage ? STAGE_TEXT[stage] : '이 휴대폰에 패스키 등록하기'}
            </button>
          )}
        </>) : (<>
          <p className="hint-text">이 부스를 관리하려면 등록한 패스키로 로그인해 주세요.</p>
          <button className="btn-primary" type="button" onClick={() => void ceremony()} disabled={busy || !passkeys}>
            {stage ? STAGE_TEXT[stage] : '패스키로 로그인'}
          </button>
        </>)}
      </div>
    </section>
  );
}
