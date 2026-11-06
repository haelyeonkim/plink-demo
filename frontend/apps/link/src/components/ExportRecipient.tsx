import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { mutate } from '@shared/auth';
import { bytes, dayClock, num } from './data/dataApi';

interface Delivery {
  eventTitle: string; recipientName: string; expiresAt: string; downloadsLeft: number | null;
  requireCode: boolean; maskedEmail: string; unlocked: boolean;
  files: Array<{ name: string; description: string | null; rows: number | null; bytes: number | null }>;
}

async function answer<T>(response: Response): Promise<T> {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error((data as { error?: string }).error || '요청을 처리하지 못했어요.'), { status: response.status });
  return data as T;
}

function DownloadMark() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
    </svg>
  );
}

/**
 * What an organiser's contact opens from the link they were sent: no account, a code to
 * their mailbox when the sender asked for one, then the files.
 */
export default function ExportRecipient() {
  const { token = '' } = useParams<{ token: string }>();
  const base = `/api/x/${encodeURIComponent(token)}`;
  const [info, setInfo] = useState<Delivery | null>(null);
  const [gone, setGone] = useState('');
  const [error, setError] = useState('');
  const [sent, setSent] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const refresh = useRef<number | undefined>(undefined);

  const load = useCallback(async () => {
    try {
      setInfo(await answer<Delivery>(await fetch(base, { cache: 'no-store' })));
    } catch (err) {
      const status = (err as { status?: number }).status;
      if (status === 404 || status === 410 || status === 403) setGone(err instanceof Error ? err.message : '');
      else setError(err instanceof Error ? err.message : '링크를 확인하지 못했어요. 잠시 후 다시 열어 주세요.');
    }
  }, [base]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => () => window.clearTimeout(refresh.current), []);

  async function requestCode() {
    setBusy(true); setError('');
    try {
      const result = await answer<{ sent: boolean; maskedEmail: string }>(await mutate(`${base}/code`, { method: 'POST' }));
      setSent(result.maskedEmail || info?.maskedEmail || '');
      setCode('');
    } catch (err) { setError(err instanceof Error ? err.message : '코드를 보내지 못했어요.'); }
    finally { setBusy(false); }
  }

  async function verify(event: FormEvent) {
    event.preventDefault();
    if (!code.trim()) return;
    setBusy(true); setError('');
    try {
      setInfo(await answer<Delivery>(await mutate(`${base}/verify`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: code.trim() }),
      })));
    } catch (err) { setError(err instanceof Error ? err.message : '코드가 맞지 않아요. 다시 확인해 주세요.'); }
    finally { setBusy(false); }
  }

  // The count left only changes on the server once the download has started.
  const downloaded = () => {
    window.clearTimeout(refresh.current);
    refresh.current = window.setTimeout(() => void load(), 1500);
  };

  if (gone) {
    return (
      <section className="inbox">
        <div className="inbox-head">
          <h1>이 링크는 열 수 없어요</h1>
          <p>{gone || '기간이 지났거나 보낸 쪽에서 닫은 링크예요.'}</p>
        </div>
        <p className="inbox-foot">다시 받아야 한다면 링크를 보낸 분께 새로 요청해 주세요.</p>
      </section>
    );
  }
  if (!info) {
    return (
      <section className="inbox">
        {error ? <p className="error-text" role="alert">{error}</p> : <p className="loading">링크를 확인하고 있어요.</p>}
      </section>
    );
  }

  const locked = info.requireCode && !info.unlocked;
  const usedUp = info.downloadsLeft != null && info.downloadsLeft <= 0;
  const until = `${dayClock(info.expiresAt)}까지`;

  return (
    <section className="inbox">
      <div className="inbox-head">
        <span className="inbox-to">{info.recipientName} 앞</span>
        {locked ? <h1>{info.eventTitle}<br />데이터가 도착했어요</h1> : <h1>{info.eventTitle}</h1>}
        <p>
          {locked ? `${until} 열 수 있어요.` : `${until} · ${info.downloadsLeft == null
            ? '내려받기 횟수 제한 없음' : `내려받기 ${num(info.downloadsLeft)}회 남음`}`}
        </p>
      </div>

      {locked ? (
        sent == null ? (
          <div className="inbox-card">
            <p>{info.maskedEmail}로 6자리 확인 코드를 보내 드릴게요. 받은 코드를 넣으면 파일이 열려요.</p>
            {error && <p className="error-text" role="alert">{error}</p>}
            <button type="button" className="inbox-primary" disabled={busy} onClick={() => void requestCode()}>
              {busy ? '보내는 중…' : '코드 받기'}
            </button>
          </div>
        ) : (
          <form className="inbox-card" onSubmit={verify}>
            <p role="status">코드를 보냈어요. {sent || info.maskedEmail}로 보낸 6자리 코드를 넣어 주세요.</p>
            <div className="inbox-field">
              <label htmlFor="recipient-code">확인 코드</label>
              <input id="recipient-code" className="inbox-code" inputMode="numeric" autoComplete="one-time-code"
                placeholder="000000" maxLength={6} value={code} autoFocus
                onChange={e => setCode(e.target.value.replace(/\D/g, ''))} />
            </div>
            {error && <p className="error-text" role="alert">{error}</p>}
            <button type="submit" className="inbox-primary" disabled={busy || code.length < 6}>
              {busy ? '확인 중…' : '확인'}
            </button>
            <button type="button" className="inbox-plain" disabled={busy} onClick={() => void requestCode()}>코드 다시 받기</button>
          </form>
        )
      ) : (
        <>
          {usedUp && <p className="warn-text" role="status">내려받기 횟수를 다 썼어요. 더 필요하면 보낸 분께 요청해 주세요.</p>}
          {info.files.length === 0 ? <p className="inbox-card">담긴 파일이 없어요.</p> : (
            <ul className="inbox-files" aria-label="파일">
              {info.files.map(file => {
                const meta = [file.description, file.rows != null ? `${num(file.rows)}행` : null,
                  file.bytes != null ? bytes(file.bytes) : null].filter(Boolean).join(' · ');
                return (
                  <li key={file.name}>
                    <span><b>{file.name}</b>{meta && <small>{meta}</small>}</span>
                    {!usedUp && (
                      <a className="inbox-download" href={`${base}/files/${encodeURIComponent(file.name)}`}
                        aria-label={`${file.name} 내려받기`} onClick={downloaded}><DownloadMark /></a>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {!usedUp && info.files.length > 1 && (
            <a className="inbox-primary" href={`${base}/zip`} onClick={downloaded}><DownloadMark />모두 받기 (zip)</a>
          )}
          {error && <p className="error-text" role="alert">{error}</p>}
        </>
      )}

      <p className="inbox-foot">
        {locked
          ? '이 링크는 받는 분만 쓰도록 만들었어요. 다른 사람에게 전달하지 말아 주세요. 여는 기록은 보낸 쪽에 남아요.'
          : '엑셀에서 바로 열 수 있어요. 기간이 지나면 이 페이지와 파일은 사라져요.'}
      </p>
    </section>
  );
}
