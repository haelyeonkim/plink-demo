import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, gateDeviceId } from '../../ticket/api';
import { claimTerminal, type BoothClient, type BoothDashboard } from '../../booth/api';

/** Where the terminal screen finds its credentials; the setup page saves them the same way. */
const STORAGE = 'plink.gate.credentials';

/**
 * The terminal this manager answers for, and the way to make the device in hand that
 * terminal - what a setup link and code would do, without either: the organiser already
 * put this person in charge of it, and the passkey says it is them.
 */
export default function BoothTerminal({ client, terminal, fail }: {
  client: BoothClient;
  terminal: NonNullable<BoothDashboard['terminal']>;
  /** A lapsed sign-in or a revoked manager changes the page rather than this card. */
  fail: (error: unknown) => void;
}) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const name = terminal.label || terminal.gateId;

  async function connect() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const result = await claimTerminal(client, gateDeviceId());
      localStorage.setItem(STORAGE, JSON.stringify({ gateId: result.gateId, gateToken: result.gateToken }));
      navigate('/tickets/gate', { replace: true });
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError && [401, 403, 410].includes(err.status)) { fail(err); return; }
      // A 409 is the terminal held by another device: the server's sentence says what to do.
      setError(err instanceof Error && err.message ? err.message : '단말을 연결하지 못했어요. 다시 시도해 주세요.');
    }
  }

  return (
    <section className="console-card bm-terminal">
      <div className="console-card-head">
        <h3>담당 단말</h3>
        <span className={`pill pill-${terminal.connected ? 'ok' : 'wait'}`}>
          {terminal.connected ? '연결됨' : '연결 안 됨'}
        </span>
      </div>
      <p className="bm-terminal-name"><b>{name}</b></p>
      <p className="hint-text">
        이 화면에서 처리한 쿠폰은 '{name}' 단말 이름으로 기록돼요. 이 기기를 단말로 쓰면 설정 링크나
        인증번호 없이 바로 단말 화면으로 넘어가요.
      </p>
      {error && <p className="error-text" role="alert">{error}</p>}
      <button type="button" className="btn-secondary" disabled={busy} onClick={() => void connect()}>
        {busy ? '연결 중…' : `이 기기를 '${name}' 단말로 쓰기`}
      </button>
    </section>
  );
}
