import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { gateDeviceId } from '../ticket/api';

interface SetupInfo {
  gateId: string;
  label: string | null;
  zone: string | null;
  direction: string;
  expiresAt: string;
  deviceBound: boolean;
}

const STORAGE = 'plink.gate.credentials';

/**
 * Enrols this tablet as a gate terminal from a setup link and a code.
 *
 * The long terminal token is never typed by a person: the code exchanges it, and the
 * gate is claimed for this device in the same step.
 */
export default function GateSetup() {
  const { setupToken = '' } = useParams<{ setupToken: string }>();
  const navigate = useNavigate();
  const [info, setInfo] = useState<SetupInfo | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await fetch(`/api/gates/setup/${encodeURIComponent(setupToken)}`);
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || '설정 링크를 확인하지 못했어요.');
      setInfo(data as SetupInfo);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '설정 링크를 확인하지 못했어요.');
    }
  }, [setupToken]);

  useEffect(() => { void load(); }, [load]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const response = await fetch(`/api/gates/setup/${encodeURIComponent(setupToken)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, deviceId: gateDeviceId() }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || '인증하지 못했어요.');
      localStorage.setItem(STORAGE, JSON.stringify({ gateId: data.gateId, gateToken: data.gateToken }));
      navigate('/tickets/gate', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '인증하지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  if (loadError) {
    return (
      <section className="page-section page-tight">
        <div className="access-card">
          <h2>설정 링크를 열 수 없어요</h2>
          <p className="error-text" role="alert">{loadError}</p>
          <p className="hint-text">관리 화면에서 설정 링크를 새로 발급받아 주세요.</p>
        </div>
      </section>
    );
  }
  if (!info) {
    return <section className="page-section page-tight"><p className="loading" role="status">확인하고 있어요.</p></section>;
  }

  return (
    <section className="page-section page-tight">
      <div className="access-card">
        <p className="eyebrow center"><span></span> GATE SETUP</p>
        <h2>{info.label || info.gateId}</h2>
        <dl className="ticket-meta">
          <dt>게이트</dt><dd>{info.gateId}</dd>
          <dt>방향</dt><dd>{info.direction}</dd>
          {info.zone && <><dt>구역</dt><dd>{info.zone}</dd></>}
        </dl>

        {info.deviceBound && (
          <p className="warn-text" role="alert">
            이미 다른 단말이 연결되어 있어요. 관리 화면에서 연결을 해제한 뒤 진행해 주세요.
          </p>
        )}
        {error && <p className="error-text" role="alert">{error}</p>}

        <form onSubmit={submit}>
          <div className="field">
            <label htmlFor="setup-code">인증번호 6자리</label>
            <input id="setup-code" inputMode="numeric" maxLength={6} required autoFocus
              value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} />
          </div>
          <button className="btn-primary" type="submit" disabled={busy}>
            {busy ? '연결 중…' : '이 단말 연결하기'}
          </button>
        </form>
        <p className="hint-text">
          연결하면 이 브라우저에만 단말 자격이 저장됩니다. 게이트 하나에는 단말 한 대만 연결할 수 있어요.
        </p>
      </div>
    </section>
  );
}
