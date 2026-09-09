import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { accessLink } from '../api';
import { openWithPasskey, passkeyError, supportsPasskeys } from '../passkey';
import type { AccessInfo } from '../types';

export default function AccessLink() {
  const { shortCode = '' } = useParams<{ shortCode: string }>();
  const [info, setInfo] = useState<AccessInfo | null>(null);
  const [password, setPassword] = useState('');
  const [viewerName, setViewerName] = useState('');
  const [error, setError] = useState('');
  const [redirectUrl, setRedirectUrl] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const currentCode = useRef(shortCode);
  currentCode.current = shortCode;
  useEffect(() => {
    let active = true;
    setInfo(null); setRedirectUrl(''); setError(''); setLoading(true); setPassword(''); setViewerName('');
    accessLink(shortCode).then(data => { if (active) setInfo(data); })
      .catch(() => { if (active) setError('링크를 찾을 수 없어요. 전달받은 주소를 확인해 주세요.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [shortCode]);

  async function handleOpen(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true); setError('');
    try {
      const url = await openWithPasskey(shortCode, password, viewerName);
      if (currentCode.current === shortCode) setRedirectUrl(url);
    } catch (err) {
      if (currentCode.current === shortCode) {
        setError(passkeyError(err));
        try { const data = await accessLink(shortCode); if (currentCode.current === shortCode) setInfo(data); } catch { /* keep original error */ }
      }
    } finally { setBusy(false); }
  }

  if (loading) return <section className="page-section"><p className="loading" role="status">링크를 확인하고 있어요.</p></section>;
  if (!info) return <section className="page-section"><div className="access-card"><h2>링크를 열 수 없어요</h2><p role="alert">{error}</p><Link to="/">홈으로</Link></div></section>;
  if (redirectUrl) return (
    <section className="page-section"><div className="access-card">
      <div className="access-icon success-icon">✓</div><h2>패스키 확인이 완료됐어요</h2>
      <p>다음에도 같은 패스키로 이 공유 링크를 열어 주세요.</p>
      <a className="btn-primary" href={redirectUrl} target="_blank" rel="noopener noreferrer">원본 링크 열기 →</a>
    </div></section>
  );
  const unavailable = info.expired || info.exhausted;
  return (
    <section className="page-section"><div className="access-card passkey-card">
      <div className="access-lock"><img src="/logo-small.svg" alt="P-Link" width="36" height="36" /></div>
      <p className="secure-label">PROTECTED LINK</p><h2>{info.title || '보호된 링크'}</h2>
      {unavailable ? <p className="error-text" role="alert">{info.expired ? '이 링크는 만료되었어요.' : '열람 가능한 횟수를 모두 사용했어요.'}</p> : (
        <>
          <p className="passkey-status">{info.claimed ? '수신이 확정된 링크예요' : '지금 수신할 수 있는 링크예요'}</p>
          <div className="passkey-notice" id="passkey-notice">
            <h3>{info.claimed ? '처음 등록한 패스키로 열어 주세요.' : '수신을 확정하면 이 링크는 내 패스키로만 열 수 있어요.'}</h3>
            <p>{info.claimed ? '다른 패스키로는 열 수 없어요. 패스키를 잃어버렸다면 공유한 분에게 새 링크를 요청해 주세요.' : '다음에도 지금 등록한 패스키가 필요해요. 다른 사람에게 링크를 전달해도 그 사람은 열 수 없어요.'}</p>
            <small>같은 패스키가 동기화된 내 다른 기기에서도 열 수 있어요.</small>
          </div>
          {!supportsPasskeys() ? <p className="error-text" role="alert">패스키를 지원하는 브라우저에서 열어 주세요. Safari 또는 Chrome의 최신 버전을 사용할 수 있어요.</p> : (
            <form onSubmit={handleOpen} aria-describedby="passkey-notice" aria-busy={busy}>
              {!info.claimed && <div className="field"><label htmlFor="viewer-name">이름 (선택)</label><input id="viewer-name" maxLength={100} value={viewerName} onChange={e => setViewerName(e.target.value)} placeholder="공유한 분이 확인할 이름" disabled={busy} /></div>}
              {info.hasPassword && <div className="field"><label htmlFor="link-password">전달받은 비밀번호</label><input id="link-password" type="password" required value={password} onChange={e => setPassword(e.target.value)} disabled={busy} /></div>}
              {error && <p className="error-text" role="alert">{error}</p>}
              <button className="btn-primary" type="submit" disabled={busy}>{busy ? '기기에서 패스키 확인을 완료해 주세요…' : info.claimed ? '내 패스키로 링크 열기' : '패스키 등록하고 수신 확정'}</button>
              {!info.claimed && <p className="passkey-footnote">별도 회원가입 없이, 지문·얼굴 인식 또는 기기 잠금번호로 등록할 수 있어요. 먼저 등록을 완료한 사람이 이 링크를 받게 됩니다.</p>}
            </form>
          )}
        </>
      )}
    </div></section>
  );
}
