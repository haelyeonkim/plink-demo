import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { mutate } from '@shared/auth';
import { useEvents } from './useEvents';
import {
  day, eventDay, fromLocalInput, getJson, num, PII_LABEL, read, toLocalInput, type Dataset, type Pii,
} from './dataApi';

/** Which file a log category ends up in, for "이 조건으로 내보내기". */
const DATASET_OF: Record<string, string> = {
  ADMISSION: 'ADMISSION', FACE: 'ADMISSION', BOOTH: 'COUPON', TICKET: 'TICKETS', DELIVERY: 'TICKETS', VIEW: 'TICKETS',
  INVITE: 'INVITES', CHAT: 'CHAT', ERROR: 'ERRORS', LINK: 'ACTIVITY', ADMIN: 'ACTIVITY', AUTH: 'ACTIVITY',
  REQUEST: 'ACTIVITY', CLIENT: 'ACTIVITY',
};

type Period = 'all' | 'day' | 'custom';
type Pick = { on: boolean; columns: Set<string> };

const PII_HELP: Record<Pii, string> = {
  AGGREGATE: '집계표만 담고 행 단위 기록은 빼요.',
  MASKED: '입장권 번호만, 이메일은 mi***@ex***.com처럼 가려요.',
  RAW: '이메일 · 휴대폰 · IP · 기기까지 기록 그대로.',
};
const DAYS = [1, 7, 14, 30];
const MAX_DOWNLOADS: Array<number | null> = [3, 5, 10, null];

/**
 * What goes to the organiser: chosen data only, as files behind a link that opens for a
 * set time and a set number of downloads.
 */
export default function DataExportNew() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { events, eventsError } = useEvents();
  const asked = (params.get('categories') || '').split(',').map(value => value.trim()).filter(Boolean);

  const [sessionId, setSessionId] = useState(params.get('sessionId') || '');
  const [period, setPeriod] = useState<Period>(params.get('from') || params.get('to') ? 'custom' : 'all');
  const [from, setFrom] = useState(params.get('from') || '');
  const [to, setTo] = useState(params.get('to') || '');
  const [tier, setTier] = useState('');
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [picks, setPicks] = useState<Record<string, Pick>>({});
  const [pii, setPii] = useState<Pii>('RAW');
  const [recipientName, setRecipientName] = useState('');
  const [recipientEmail, setRecipientEmail] = useState('');
  const [days, setDays] = useState(7);
  const [maxDownloads, setMaxDownloads] = useState<number | null>(5);
  const [requireCode, setRequireCode] = useState(true);
  const [note, setNote] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // With nothing asked for, the newest event.
  useEffect(() => {
    if (!sessionId && events.length > 0) setSessionId(String(events[0].id));
  }, [events, sessionId]);
  const event = events.find(row => String(row.id) === sessionId);

  useEffect(() => {
    if (!sessionId) return;
    const controller = new AbortController();
    setLoading(true); setError('');
    getJson<Dataset[]>(`/api/admin/data/datasets?sessionId=${encodeURIComponent(sessionId)}`, controller.signal)
      .then(rows => {
        setDatasets(rows);
        // A choice already made survives another event's numbers; a new dataset starts
        // as the server suggests, or as the log view asked for.
        setPicks(current => {
          const next: Record<string, Pick> = {};
          for (const set of rows) {
            const wanted = asked.length > 0
              ? set.key === 'SUMMARY' || asked.some(category => DATASET_OF[category] === set.key)
              : !set.internal;
            next[set.key] = current[set.key] ?? {
              on: wanted,
              columns: new Set(set.columns.filter(column => column.defaultOn).map(column => column.key)),
            };
          }
          return next;
        });
      })
      .catch(err => { if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '담을 데이터를 불러오지 못했어요.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [sessionId]);

  const rowOnly = (set: Dataset) => pii === 'AGGREGATE' && set.key !== 'SUMMARY';
  const included = datasets.filter(set => picks[set.key]?.on && !rowOnly(set));

  const range = useMemo((): { from?: string; to?: string } => {
    if (period === 'day') return event ? eventDay(event.startsAt) : {};
    if (period === 'custom') return { from: from || undefined, to: to || undefined };
    return {};
  }, [period, event, from, to]);

  const expiry = new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString();
  const noteNeeded = pii === 'RAW';
  const ready = !!sessionId && included.length > 0 && recipientName.trim() !== '' && recipientEmail.trim() !== ''
    && (!noteNeeded || note.trim() !== '');

  function setPick(key: string, patch: Partial<Pick>) {
    setPicks(current => ({ ...current, [key]: { ...current[key], ...patch } }));
  }
  function toggleColumn(key: string, column: string, on: boolean) {
    const columns = new Set(picks[key]?.columns);
    if (on) columns.add(column); else columns.delete(column);
    setPick(key, { columns });
  }

  async function submit(eventForm: FormEvent) {
    eventForm.preventDefault();
    if (!ready) {
      setError(noteNeeded && !note.trim() ? '원본을 담을 땐 요청 내용을 꼭 적어 주세요.' : '빠진 칸을 채워 주세요.');
      return;
    }
    setBusy(true); setError('');
    try {
      const created = await read<{ id: number | string; url: string; expiresAt: string }>(await mutate('/api/admin/data/exports', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: Number(sessionId),
          from: range.from, to: range.to,
          tier: tier || undefined,
          datasets: included.map(set => ({ key: set.key, columns: [...(picks[set.key]?.columns ?? [])] })),
          pii,
          recipientName: recipientName.trim(),
          recipientEmail: recipientEmail.trim(),
          days, maxDownloads, requireCode,
          note: note.trim(),
        }),
      }));
      navigate(`/admin/data/exports?created=${encodeURIComponent(String(created.id))}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '파일을 만들지 못했어요.');
    } finally { setBusy(false); }
  }

  return (
    <section className="page-section page-wide data-page">
      <Link className="data-back" to="/admin/data/exports">‹ 내보내기 목록</Link>
      <h2>주최측에 보낼 데이터 만들기</h2>
      <p className="section-desc">고른 데이터만 파일로 만들고, 정해진 기간 동안만 열리는 링크로 전해요.</p>
      {eventsError && <p className="error-text" role="alert">{eventsError}</p>}

      <form className="export-form" onSubmit={submit}>
        <div className="export-steps">
          <section aria-labelledby="export-what" className="data-card">
            <h3 id="export-what">1. 무엇을 담을까요</h3>
            {loading && datasets.length === 0 && <p className="hint-text">담을 수 있는 데이터를 살펴보고 있어요.</p>}
            {!loading && datasets.length === 0 && <p className="data-empty">{sessionId ? '담을 데이터가 없어요.' : '이벤트를 먼저 골라 주세요.'}</p>}
            {datasets.length > 0 && (
              <div className="dataset-list">
                {datasets.map(set => {
                  const pick = picks[set.key];
                  const disabled = rowOnly(set);
                  const on = !!pick?.on && !disabled;
                  const chosen = set.columns.filter(column => pick?.columns.has(column.key)).length;
                  return (
                    <div key={set.key} className={`dataset${disabled ? ' is-disabled' : ''}`}>
                      <label className="dataset-head">
                        <input type="checkbox" checked={on} disabled={disabled}
                          onChange={e => setPick(set.key, { on: e.target.checked })} />
                        <b>{set.label}</b>
                        <span>
                          {set.key === 'SUMMARY' ? set.description : `${num(set.rows)}행`}
                          {set.key !== 'SUMMARY' && set.columns.length > 0 && ` · 열 ${chosen}/${set.columns.length}`}
                        </span>
                      </label>
                      {set.key !== 'SUMMARY' && set.description && <p className="dataset-desc">{set.description}</p>}
                      {set.internal && <p className="dataset-desc">내부 기록이에요. 필요할 때만 담아요.</p>}
                      {disabled && <p className="dataset-desc">숫자만 담을 땐 행 단위 기록은 빠져요.</p>}
                      {on && set.columns.length > 0 && (
                        <div role="group" aria-label={`${set.label}에 담을 열`} className="column-chips">
                          {set.columns.map(column => (
                            <label key={column.key} className={`column-chip${pick?.columns.has(column.key) ? ' on' : ''}`}>
                              <input type="checkbox" checked={!!pick?.columns.has(column.key)}
                                onChange={e => toggleColumn(set.key, column.key, e.target.checked)} />
                              {column.label}
                              {column.pii && <span className="pii-badge" title="개인정보가 담긴 열이에요">개인정보</span>}
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          <section aria-labelledby="export-range" className="data-card">
            <h3 id="export-range">2. 범위</h3>
            <div className="data-grid">
              <div className="data-field">
                <label htmlFor="export-event">이벤트</label>
                <select id="export-event" value={sessionId} required onChange={e => { setSessionId(e.target.value); setTier(''); }}>
                  {!sessionId && <option value="">이벤트를 골라 주세요</option>}
                  {events.map(row => <option key={row.id} value={row.id}>{row.name}</option>)}
                </select>
              </div>
              <div className="data-field">
                <label htmlFor="export-period">기간</label>
                <select id="export-period" value={period} onChange={e => setPeriod(e.target.value as Period)}>
                  <option value="all">이벤트 전체</option>
                  <option value="day" disabled={!event}>행사 당일만</option>
                  <option value="custom">직접 고르기</option>
                </select>
              </div>
              <div className="data-field">
                <label htmlFor="export-tier">등급</label>
                <select id="export-tier" value={tier} onChange={e => setTier(e.target.value)} disabled={!event?.tiers?.length}>
                  <option value="">{event?.tiers?.length ? '모든 등급' : '등급 없음'}</option>
                  {(event?.tiers ?? []).map(name => <option key={name} value={name}>{name}</option>)}
                </select>
              </div>
            </div>
            {period === 'custom' && (
              <div className="data-grid">
                <div className="data-field">
                  <label htmlFor="export-from">시작</label>
                  <input id="export-from" type="datetime-local" value={toLocalInput(from)}
                    onChange={e => setFrom(fromLocalInput(e.target.value) ?? '')} />
                </div>
                <div className="data-field">
                  <label htmlFor="export-to">끝</label>
                  <input id="export-to" type="datetime-local" value={toLocalInput(to)}
                    onChange={e => setTo(fromLocalInput(e.target.value) ?? '')} />
                </div>
              </div>
            )}
          </section>

          <section aria-labelledby="export-pii" className="data-card">
            <h3 id="export-pii">3. 개인정보</h3>
            <fieldset className="pii-cards">
              <legend className="visually-hidden">개인정보 수준</legend>
              {(Object.keys(PII_LABEL) as Pii[]).map(key => (
                <label key={key} className={`pii-card${pii === key ? ' chosen' : ''}`}>
                  <span><input type="radio" name="pii" value={key} checked={pii === key} onChange={() => setPii(key)} />{PII_LABEL[key]}</span>
                  {PII_HELP[key]}
                </label>
              ))}
            </fieldset>
          </section>

          <section aria-labelledby="export-send" className="data-card">
            <h3 id="export-send">4. 누구에게, 얼마 동안</h3>
            <div className="data-grid">
              <div className="data-field">
                <label htmlFor="export-org">받는 곳</label>
                <input id="export-org" value={recipientName} required maxLength={100} placeholder="예: 아트페어 운영사무국"
                  onChange={e => setRecipientName(e.target.value)} />
              </div>
              <div className="data-field">
                <label htmlFor="export-mail">받는 사람 이메일</label>
                <input id="export-mail" type="email" value={recipientEmail} required placeholder="name@example.com"
                  onChange={e => setRecipientEmail(e.target.value)} />
              </div>
              <div className="data-field">
                <label htmlFor="export-days">링크 유효</label>
                <select id="export-days" value={days} onChange={e => setDays(Number(e.target.value))}>
                  {DAYS.map(value => <option key={value} value={value}>{value}일</option>)}
                </select>
              </div>
              <div className="data-field">
                <label htmlFor="export-max">내려받기 횟수</label>
                <select id="export-max" value={maxDownloads ?? ''} onChange={e => setMaxDownloads(e.target.value ? Number(e.target.value) : null)}>
                  {MAX_DOWNLOADS.map(value => <option key={value ?? 'none'} value={value ?? ''}>{value ? `${value}회` : '제한 없음'}</option>)}
                </select>
              </div>
            </div>
            <label className="data-check">
              <input type="checkbox" checked={requireCode} onChange={e => setRequireCode(e.target.checked)} />
              열 때 받는 사람 이메일로 확인 코드 받기
            </label>
            <div className="data-field">
              <label htmlFor="export-note">요청 내용 <span className="data-label-note">
                {noteNeeded ? '원본을 담을 땐 꼭 적어요 · 기록에 남아요' : '기록에 남아요'}</span></label>
              <textarea id="export-note" rows={2} value={note} required={noteNeeded} maxLength={500}
                placeholder="예: 11/10 운영사무국 결과 보고용 요청 (메일)" onChange={e => setNote(e.target.value)} />
            </div>
          </section>
        </div>

        <aside aria-labelledby="export-summary" className="data-card export-summary">
          <h3 id="export-summary">이렇게 만들어요</h3>
          {included.length === 0 ? <p className="data-empty">담을 데이터를 골라 주세요.</p> : (
            <ul className="export-files">
              {included.map(set => (
                <li key={set.key}>
                  <span>{set.label}</span>
                  <span>{set.key === 'SUMMARY' ? '요약표' : `${num(set.rows)}행 · 열 ${picks[set.key]?.columns.size ?? 0}`}</span>
                </li>
              ))}
            </ul>
          )}
          <div className="export-settings">
            <span>개인정보 · {PII_LABEL[pii]}</span>
            {event && <span>이벤트 · {event.name}{tier ? ` · ${tier}` : ''}</span>}
            <span>링크 · {day(expiry)}까지 · {maxDownloads ? `${maxDownloads}회` : '횟수 제한 없음'}</span>
            <span>열 때 · {requireCode ? `${recipientEmail.trim() || '받는 사람 이메일'} 확인 코드` : '확인 코드 없이 열어요'}</span>
          </div>
          {error && <p className="error-text" role="alert">{error}</p>}
          <button className="btn-primary export-submit" type="submit" disabled={busy}>
            {busy ? '파일을 만들고 있어요…' : '파일 만들고 링크 받기'}
          </button>
          <p className="hint-text">링크는 복사해서 직접 보내거나, 목록에서 메일로 보낼 수 있어요. 기간이 지나면 파일도 지워져요.</p>
        </aside>
      </form>
    </section>
  );
}
