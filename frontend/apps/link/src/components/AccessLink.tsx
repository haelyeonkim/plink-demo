import { ArtworkRequestsProvider } from './ArtworkRequests';
import Icon from '@shared/components/Icon';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { accessLink } from '../api';
import { openWithPasskey, PasskeyCeremonyError, passkeyError, supportsPasskeys, type Opened } from '../passkey';
import ClaimConsent from '@shared/components/ClaimConsent';
import { alreadyOnDevice, preferredChoice, rememberPasskey, type ClaimChoice } from '@shared/passkeyClaim';
import ContentView from './ContentView';
import type { AccessInfo } from '../types';

/** The phone had no passkey to answer with, or the sheet was closed: the platform cannot tell them apart. */
function lockedOut(err: unknown): boolean {
  const cause = err instanceof PasskeyCeremonyError ? err.reason : err;
  return cause instanceof DOMException && (cause.name === 'NotAllowedError' || cause.name === 'AbortError');
}

export default function AccessLink() {
  const { shortCode = '', slug } = useParams<{ shortCode: string; slug?: string }>();
  const navigate = useNavigate();
  const [info, setInfo] = useState<AccessInfo | null>(null);
  const [password, setPassword] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState('');
  const [opened, setOpened] = useState<Opened | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [statusNotice, setStatusNotice] = useState('');
  const currentCode = useRef(shortCode);
  currentCode.current = shortCode;
  useEffect(() => {
    let active = true;
    setInfo(null); setOpened(null); setError(''); setLoading(true); setPassword(''); setAgreed(false);
    accessLink(shortCode, slug).then(data => {
      if (!active) return;
      setInfo(data);
      // An address handed out before issuers had their own segment still works; move
      // the visitor onto the canonical one so what they see is what they were sent.
      if (!slug && data.issuer && data.path) navigate(data.path, { replace: true });
    })
      .catch(() => { if (active) setError('링크를 찾을 수 없어요. 전달받은 주소를 확인해 주세요.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [shortCode, slug, navigate]);

  useEffect(() => {
    if (!opened?.statusEventsUrl) return;
    const stream = new EventSource(opened.statusEventsUrl);
    setStatusNotice('');
    stream.addEventListener('statuses', event => {
      try {
        const updates: Array<{ position: number; saleStatus: 'hold' | 'sold' | null; linked: boolean; fields?: Record<string, string> }> = JSON.parse(event.data);
        const byPosition = new Map(updates.map(row => [row.position, row]));
        setOpened(current => current?.content ? { ...current, content: { ...current.content,
          artworks: current.content.artworks?.map((row, index) => {
            const state = byPosition.get(index);
            return state ? { ...row, ...state.fields, saleStatus: state.saleStatus || '', saleStatusLinked: String(state.linked) } : row;
          }),
        } } : current);
        setStatusNotice('');
      } catch { setStatusNotice('작품 정보를 확인하지 못했어요. 링크를 다시 열어 주세요.'); }
    });
    stream.addEventListener('unavailable', () => {
      stream.close(); setOpened(null);
      setError('작품 정보 확인 권한이 만료되었습니다. 패스키로 링크를 다시 열어 주세요.');
    });
    stream.onopen = () => setStatusNotice('');
    stream.onerror = () => setStatusNotice('작품 정보 연결을 다시 시도하고 있어요. 표시된 상태가 최신이 아닐 수 있습니다.');
    return () => stream.close();
  }, [opened?.statusEventsUrl]);

  /** Opens a claimed link, or claims an unclaimed one the way the visitor chose. */
  async function open(choice?: ClaimChoice) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      let result: Opened;
      try {
        result = await openWithPasskey(shortCode, password, slug, choice);
      } catch (err) {
        // The phone already holds this person's passkey and would not make a second one:
        // answer that one instead.
        if (choice !== 'new' || !alreadyOnDevice(err)) throw err;
        result = await openWithPasskey(shortCode, password, slug, 'existing');
      }
      if (choice) rememberPasskey();
      if (currentCode.current === shortCode) setOpened(result);
    } catch (err) {
      if (currentCode.current === shortCode) {
        setError(info?.claimed && !choice && lockedOut(err)
          // A claimed link only ever asks for the one passkey that claimed it.
          ? '이 링크에 등록된 패스키가 이 기기에 없어요. 패스키를 지웠거나 휴대폰을 바꿨다면, 보낸 분에게 다시 보내 달라고 요청해 주세요.'
          : passkeyError(err));
        try { const data = await accessLink(shortCode, slug); if (currentCode.current === shortCode) setInfo(data); } catch { /* keep original error */ }
      }
    } finally { setBusy(false); }
  }

  if (loading) return <section className="page-section"><p className="loading" role="status">링크를 확인하고 있어요.</p></section>;
  if (!info) return <section className="page-section"><div className="access-card"><h2>링크를 열 수 없어요</h2><p role="alert">{error}</p><Link to="/">홈으로</Link></div></section>;
  // A document written by the sender is shown here rather than handed to another site:
  // it exists only behind this ceremony, so there is nowhere else to send the reader.
  if (opened?.content) return (
    <section className="page-section">
      {statusNotice && <p className="hint-text" role="status">{statusNotice}</p>}
      <ArtworkRequestsProvider key={shortCode} code={shortCode}><ContentView title={opened.contentTitle ?? info.title ?? '공유된 콘텐츠'} body={opened.content} recipientCode={shortCode} /></ArtworkRequestsProvider>
    </section>
  );
  if (opened) return (
    <section className="page-section"><div className="access-card">
      <div className="access-icon success-icon"><Icon name="check" /></div><h2>패스키 확인이 완료됐어요</h2>
      <p>다음에도 같은 패스키로 이 공유 링크를 열어 주세요.</p>
      {opened.originalUrl && (
        <a className="btn-primary" href={opened.originalUrl} target="_blank" rel="noopener noreferrer">
          원본 링크 열기
        </a>
      )}
    </div></section>
  );
  const unavailable = info.expired || info.exhausted;
  return (
    <section className="page-section"><div className="access-card passkey-card">
      <div className="access-lock"><img src="/logo-small.svg" alt="패스링크" width="36" height="36" /></div>
      <p className="secure-label">PROTECTED LINK</p><h2>{info.title || '보호된 링크'}</h2>
      {unavailable ? <p className="error-text" role="alert">{info.expired ? '이 링크는 만료되었어요. 기존 요청은 자동 취소되지 않습니다. 변경이 필요하면 링크를 전달한 담당자에게 연락해 주세요.' : '열람 가능한 횟수를 모두 사용했어요.'}</p> : (
        <>
          <p className="passkey-status">
            {info.claimed ? '수신이 확정된 링크예요' : '지금 수신할 수 있는 링크예요'}
            {info.issuedTo && <span className="issued-to"> · {info.issuedTo} 님에게 발급</span>}
          </p>
          {info.claimed && (
            <div className="passkey-notice" id="passkey-notice">
              <h3>처음 등록한 패스키로 열어 주세요.</h3>
              <p>이 링크는 처음 등록한 휴대폰에서만 열려요. 본인이 등록했는데 패스키를 지웠거나 휴대폰을 바꿨다면, 보낸 분에게 다시 보내 달라고 요청해 주세요.</p>
              <small>같은 패스키가 동기화된 내 다른 기기에서도 열 수 있어요.</small>
            </div>
          )}
          {!supportsPasskeys() ? <p className="error-text" role="alert">패스키를 지원하는 브라우저에서 열어 주세요. Safari 또는 Chrome의 최신 버전을 사용할 수 있어요.</p> : (
            <form onSubmit={e => {
              e.preventDefault();
              // Enter in the password field: a claimed link opens; an unclaimed one goes the
              // way the page offers first, once the visitor has said the link is theirs.
              if (info.claimed) void open();
              else if (agreed) void open(preferredChoice(info.suggest));
              else setError('먼저 받은 링크가 맞는지 확인해 주세요.');
            }} aria-busy={busy}>
              {info.hasPassword && <div className="field"><label htmlFor="link-password">전달받은 비밀번호</label><input id="link-password" type="password" required value={password} onChange={e => setPassword(e.target.value)} disabled={busy} /></div>}
              {error && <p className="error-text" role="alert">{error}</p>}
              {info.claimed
                ? <button className="btn-primary" type="submit" disabled={busy} aria-describedby="passkey-notice">{busy ? '기기에서 패스키 확인을 완료해 주세요…' : '내 패스키로 링크 열기'}</button>
                : <ClaimConsent what="링크" preferred={preferredChoice(info.suggest)} agreed={agreed} onAgreed={setAgreed}
                    busy={busy} disabled={info.hasPassword && !password} onClaim={choice => void open(choice)} />}
            </form>
          )}
        </>
      )}
    </div></section>
  );
}
