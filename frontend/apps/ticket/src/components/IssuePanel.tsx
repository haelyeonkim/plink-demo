import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import QRCode from 'qrcode';
import InfoTip from '@shared/components/InfoTip';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import {
  downloadCsv, downloadSample, useImportRows, type ImportField, type ImportResult, type RowProblem,
} from '@shared/components/BulkImport';
import { parseTable } from '@shared/csv';
import { DELIVERY, type IssuedTicket, type SessionRow, type TicketFieldRow } from '../console/model';

export type IssueMode = 'single' | 'bulk';

export interface IssueInput {
  email: string; phone: string; values: Record<string, string>; notify: boolean;
}

/**
 * The 입장권 발급 screen: one person at a time, or a list in three steps. Which of the two
 * is open lives in the address, so a reload or a shared link lands on the same one.
 */
export default function IssuePanel({
  session, fields, takenSeats, counts, busy, mode, onMode, issued, onIssue, onContinue, onBulk, onMail, checkTaken, onShowTickets,
}: {
  session: SessionRow;
  fields: TicketFieldRow[];
  takenSeats: Set<string>;
  counts: { all: number; bound: number };
  busy: boolean;
  mode: IssueMode;
  onMode: (mode: IssueMode) => void;
  /** The ticket this screen just issued, shown in place of the form. */
  issued: IssuedTicket | null;
  onIssue: (input: IssueInput) => Promise<boolean>;
  onContinue: () => void;
  onBulk: (rows: Array<Record<string, string>>, notify: boolean) => Promise<ImportResult>;
  onMail: (ticketIds: number[]) => Promise<MailedResult>;
  checkTaken: (emails: string[]) => Promise<Set<string>>;
  onShowTickets: (ticket?: { ticketId: number; ticketRef: string }) => void;
}) {
  const seatsLeft = session.seats.length > 0 ? `${(session.seats.length - takenSeats.size).toLocaleString()}석` : '제한 없음';
  const tabs: Array<[IssueMode, ReactNode]> = [
    ['single', <><PersonIcon />한 명씩</>],
    ['bulk', <><PeopleIcon />여러 명<span className="issue-tab-wide"> 한꺼번에</span> (CSV)</>],
  ];
  const tabRefs = useRef<Record<IssueMode, HTMLButtonElement | null>>({ single: null, bulk: null });

  // Arrow keys move between the two, as in any tab list; the choice follows the focus.
  function onTabKey(event: KeyboardEvent<HTMLDivElement>) {
    const order: IssueMode[] = ['single', 'bulk'];
    // From the tab that has the focus, which a quick second press may reach before the
    // first one's choice has been drawn.
    const focused = order.findIndex(key => tabRefs.current[key] === document.activeElement);
    const at = focused >= 0 ? focused : order.indexOf(mode);
    let next: IssueMode | null = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = order[(at + 1) % order.length];
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = order[(at + order.length - 1) % order.length];
    else if (event.key === 'Home') next = order[0];
    else if (event.key === 'End') next = order[order.length - 1];
    if (!next) return;
    event.preventDefault();
    onMode(next);
    tabRefs.current[next]?.focus();
  }

  return (
    <div className="issue-page">
      <p className="issue-stats">
        <span>발급 <b>{counts.all.toLocaleString()}</b>장</span>
        <span className="issue-stats-sep" aria-hidden="true">|</span>
        <span>등록 <b>{counts.bound.toLocaleString()}</b>장</span>
        <span className="issue-stats-sep" aria-hidden="true">|</span>
        <span className="issue-stats-seats">남은 좌석 {seatsLeft}</span>
        <Link to="?tab=tickets">발급 현황 보기 ›</Link>
      </p>

      <div className="issue-tabs" role="tablist" aria-label="발급 방식" onKeyDown={onTabKey}>
        {tabs.map(([key, label]) => (
          <button key={key} type="button" role="tab" id={`issue-tab-${key}`} aria-controls={`issue-panel-${key}`}
            aria-selected={mode === key} tabIndex={mode === key ? 0 : -1}
            ref={node => { tabRefs.current[key] = node; }} onClick={() => onMode(key)}>
            {label}
          </button>
        ))}
      </div>

      {/* Both stay mounted, so a list pasted under 여러 명 is still there after a look at 한 명씩. */}
      <div role="tabpanel" id="issue-panel-single" aria-labelledby="issue-tab-single" hidden={mode !== 'single'}>
        {issued
          ? <IssueDone issued={issued} fields={fields} onContinue={onContinue} onShowTickets={onShowTickets} />
          : <SingleForm fields={fields} takenSeats={takenSeats} busy={busy} onIssue={onIssue} />}
      </div>
      <div role="tabpanel" id="issue-panel-bulk" aria-labelledby="issue-tab-bulk" hidden={mode !== 'bulk'}>
        <BulkIssue fields={fields} onBulk={onBulk} onMail={onMail} checkTaken={checkTaken} onShowTickets={() => onShowTickets()} />
      </div>
    </div>
  );
}

function PersonIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" />
    </svg>
  );
}

function PeopleIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="9" cy="8" r="3.5" /><path d="M2.5 20a6.5 6.5 0 0 1 13 0" /><circle cx="17" cy="9" r="2.5" /><path d="M16 14.5a5 5 0 0 1 6 5" />
    </svg>
  );
}

function DownloadIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
    </svg>
  );
}

/** One ticket: the event's own fields, and whether the link goes out by mail. */
function SingleForm({ fields, takenSeats, busy, onIssue }: {
  fields: TicketFieldRow[]; takenSeats: Set<string>; busy: boolean; onIssue: (input: IssueInput) => Promise<boolean>;
}) {
  const [notify, setNotify] = useState(true);
  const [hasPhone, setHasPhone] = useState(false);

  function submit(form: HTMLFormElement) {
    const data = new FormData(form);
    // Every field the organiser defined arrives under its own label.
    const values: Record<string, string> = {};
    for (const [key, value] of data.entries()) {
      if (key.startsWith('field:') && String(value).trim()) values[key.slice(6)] = String(value).trim();
    }
    void onIssue({ email: String(data.get('email') ?? ''), phone: String(data.get('phone') ?? ''), values, notify });
  }

  return (
    <>
      <form className="console-card issue-form" onSubmit={event => { event.preventDefault(); submit(event.currentTarget); }}
        onChange={event => {
          const target = event.target as unknown as HTMLInputElement;
          if (target.name === 'phone') setHasPhone(target.value.trim().length > 0);
        }}>
        <div className="issue-grid">
          <div className="field issue-wide">
            <label htmlFor="ticket-to">받는 사람 이메일</label>
            <input id="ticket-to" name="email" type="email" required autoComplete="off" placeholder="name@example.com" />
          </div>
          {/* One input per field the organiser defined. A field with a list is a choice;
              one without takes whatever is typed. */}
          {fields.map(field => (
            <div className="field" key={field.id}>
              <label htmlFor={`field-${field.id}`}>{field.label}</label>
              {field.values.length > 0 ? (
                <select id={`field-${field.id}`} name={`field:${field.label}`} defaultValue="">
                  <option value="">선택 안 함</option>
                  {field.values.map(value => {
                    const taken = field.kind === 'SEAT' && takenSeats.has(value);
                    return (
                      <option key={value} value={value} disabled={taken}>{value}{taken ? ' · 발급됨' : ''}</option>
                    );
                  })}
                </select>
              ) : (
                <input id={`field-${field.id}`} name={`field:${field.label}`}
                  placeholder={field.kind === 'SEAT' ? '예: A-12' : '직접 입력'} />
              )}
            </div>
          ))}
          <div className="field issue-phone">
            <label htmlFor="ticket-phone">휴대폰 <span className="issue-optional">선택 · 문자로도 보내요</span></label>
            <input id="ticket-phone" name="phone" type="tel" inputMode="tel" autoComplete="off" placeholder="01012345678" />
          </div>
        </div>
        <div className="issue-submit">
          <label className="issue-notify">
            <input type="checkbox" checked={notify} onChange={event => setNotify(event.target.checked)} />
            {hasPhone ? '입장권 링크를 메일·문자로 보내기' : '입장권 링크를 메일로 보내기'}
          </label>
          <button className="btn-primary" type="submit" disabled={busy}>{busy ? '발급 중…' : '발급하기'}</button>
        </div>
      </form>
      <p className="issue-foot">
        {fields.length === 0 ? '아직 발급 항목이 없어요. 좌석·등급 같은 칸을 더하려면 ' : '입력 칸은 이벤트 설정의 발급 항목을 따라요. 항목을 바꾸려면 '}
        <Link to="?tab=settings&sec=fields">이벤트 설정 › 발급 항목</Link>.
      </p>
    </>
  );
}

function deadline(value: string): string {
  const at = new Date(value);
  const hours = Math.max(1, Math.round((at.getTime() - Date.now()) / 3_600_000));
  const time = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  return `${at.getMonth() + 1}. ${at.getDate()}. ${time}까지 (${hours}시간)`;
}

/** What was just issued: where it went, its link to copy, and the same link as a QR code. */
function IssueDone({ issued, fields, onContinue, onShowTickets }: {
  issued: IssuedTicket; fields: TicketFieldRow[]; onContinue: () => void;
  onShowTickets: (ticket: { ticketId: number; ticketRef: string }) => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const [qr, setQr] = useState('');
  const [copied, setCopied] = useState(false);

  useEffect(() => { heading.current?.focus(); }, [issued.ticketId]);
  useEffect(() => {
    let current = true;
    QRCode.toDataURL(issued.url, { width: 360, margin: 1, errorCorrectionLevel: 'M' })
      .then(data => { if (current) setQr(data); })
      .catch(() => { if (current) setQr(''); });
    return () => { current = false; };
  }, [issued.url]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(issued.url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard blocked; the link is selectable */ }
  }

  const via = issued.deliveredVia;
  const mailed = via === 'EMAIL' || via === 'EMAIL+SMS';
  const title = mailed ? `${issued.issuedToEmail ?? '받는 사람'} 에게 보냈어요`
    : via === 'SMS' ? '문자로 보냈어요' : '링크를 만들었어요';
  const facts = fields
    .map(field => [field.label, issued.values?.[field.label]] as const)
    .filter((pair): pair is readonly [string, string] => !!pair[1]);

  return (
    <section className="console-card issue-done" aria-labelledby="issue-done-title">
      <div className="issue-done-main">
        <div className="issue-done-head">
          <span className="issue-done-mark" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="m5 12 5 5 9-10" /></svg>
          </span>
          <h3 id="issue-done-title" ref={heading} tabIndex={-1}>{title}</h3>
        </div>
        {issued.notify && !mailed && (
          <p className="issue-done-warn" role="status">메일을 보내지 못했어요. 아래 링크나 QR로 직접 전해 주세요.</p>
        )}
        <dl className="issue-done-facts">
          <dt>입장권</dt><dd className="mono">{issued.ticketRef}</dd>
          {facts.map(([label, value]) => <Fact key={label} label={label} value={value} />)}
          <dt>전달</dt><dd>{DELIVERY[via] ?? via}</dd>
          {issued.claimExpiresAt && <><dt>등록 기한</dt><dd>{deadline(issued.claimExpiresAt)}</dd></>}
        </dl>
        <div className="issue-done-link">
          <code title={issued.url}>{issued.url}</code>
          <button type="button" className="btn-tiny" onClick={() => void copy()}>{copied ? '복사했어요' : '링크 복사'}</button>
          <span className="visually-hidden" role="status">{copied ? '링크를 복사했어요' : ''}</span>
        </div>
        <div className="issue-done-actions">
          <button type="button" className="btn-primary" onClick={onContinue}>이어서 발급</button>
          <button type="button" className="btn-secondary"
            onClick={() => onShowTickets({ ticketId: issued.ticketId, ticketRef: issued.ticketRef })}>발급 현황에서 보기</button>
        </div>
      </div>
      <figure className="issue-done-qr">
        <div className="issue-done-qr-box">
          {qr ? <img src={qr} alt={`${issued.ticketRef} 입장권 링크 QR 코드`} width="150" height="150" />
            : <span className="console-none">QR을 만들고 있어요.</span>}
        </div>
        <figcaption>현장에서 바로 건넬 때<br />휴대폰 카메라로 찍어요</figcaption>
      </figure>
    </section>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return <><dt>{label}</dt><dd>{value}</dd></>;
}

/** Problems the operator can fix in the file are red; ones that only mean "already done" are amber. */
const PROBLEM_TONE: Record<RowProblem, 'warn' | 'bad'> = {
  '필수 값 없음': 'bad', '이메일 형식 확인': 'bad', '파일 안에서 중복': 'warn', '이미 있는 주소': 'warn',
};

type Step = 1 | 2 | 3;
const STEPS: Array<[Step, string]> = [[1, '명단 넣기'], [2, '확인하고 발급'], [3, '결과']];

function Steps({ step }: { step: Step }) {
  return (
    <>
      <ol className="issue-steps" aria-label="단계">
        {STEPS.map(([n, label], index) => (
          <li key={n} className={n < step ? 'done' : n === step ? 'on' : ''} aria-current={n === step ? 'step' : undefined}>
            {index > 0 && <span className="issue-steps-gap" aria-hidden="true">—</span>}
            <span className="issue-steps-n" aria-hidden="true">{n < step ? '✓' : n}</span>
            {label}{n < step && <span className="visually-hidden"> (완료)</span>}
          </li>
        ))}
      </ol>
      <p className="issue-steps-short" aria-hidden="true"><b>{step} / 3</b> {STEPS[step - 1][1]}</p>
    </>
  );
}

interface NotDone { index: number; row: Record<string, string>; reason: string; tone: 'warn' | 'bad'; failed: boolean }
interface BulkOutcome { issued: number; issuedIds: number[]; skipped: number; failed: number; notDone: NotDone[] }
/** What mailing a list issued without mail came to. */
export interface MailedResult { sent: number; skipped: Array<{ ticketId: number; ticketRef: string | null; reason: string }> }

/**
 * A list in three steps: put it in, see how it was read and what will be set aside, and
 * then what came of it - with the rows that did not go out ready to fix and upload again.
 * The validation and the request are BulkImport's; only the screens are arranged here.
 */
function BulkIssue({ fields, onBulk, onMail, checkTaken, onShowTickets }: {
  fields: TicketFieldRow[];
  onBulk: (rows: Array<Record<string, string>>, notify: boolean) => Promise<ImportResult>;
  onMail: (ticketIds: number[]) => Promise<MailedResult>;
  checkTaken: (emails: string[]) => Promise<Set<string>>;
  onShowTickets: () => void;
}) {
  const importFields: ImportField[] = [
    { key: 'email', label: '이메일', match: ['email', '이메일', '메일'], required: true },
    ...fields.map(field => ({
      key: field.label,
      label: field.label,
      match: [field.label, ...(field.kind === 'SEAT' ? ['seat', '좌석'] : field.kind === 'TIER' ? ['tier', '등급', '권종'] : [])],
    })),
    { key: 'phone', label: '휴대폰', match: ['phone', '휴대폰', '전화', '연락처'] },
  ];
  const [step, setStep] = useState<Step>(1);
  const [text, setText] = useState('');
  const [fileName, setFileName] = useState('');
  const [dragging, setDragging] = useState(false);
  // A list is issued without mail; it is sent from the result, once looked over, so a
  // wrong file never reaches hundreds of inboxes.
  const [confirmMail, setConfirmMail] = useState(false);
  const [mailed, setMailed] = useState<MailedResult | null>(null);
  const [filter, setFilter] = useState<'auto' | 'skip' | 'all'>('auto');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [outcome, setOutcome] = useState<BulkOutcome | null>(null);
  const stepHeading = useRef<HTMLHeadingElement>(null);
  const doneHeading = useRef<HTMLHeadingElement>(null);

  const { rows, problems, invalid, tally } = useImportRows(text, importFields, checkTaken);
  const sendable = rows.length - invalid.length;
  const shown = filter === 'auto' ? (invalid.length > 0 ? 'skip' : 'all') : filter;

  useEffect(() => {
    if (step === 2) stepHeading.current?.focus();
    if (step === 3) doneHeading.current?.focus();
  }, [step]);

  async function read(files: FileList | null) {
    const chosen = files?.[0];
    if (!chosen) return;
    setError('');
    try {
      const content = await chosen.text();
      setFileName(chosen.name);
      setText(content);
      // A chosen file is the whole list, so it goes straight to being checked.
      if (parseTable(content).rows.length > 0) { setFilter('auto'); setStep(2); }
      else setError('이 파일에서 읽을 행을 찾지 못했어요. 첫 줄은 열 이름, 그 아래로 한 줄에 한 명씩이에요.');
    } catch {
      setError('파일을 읽지 못했어요.');
    }
  }

  async function submit() {
    const sent = problems.flatMap((problem, index) => (problem === null ? [index] : []));
    setBusy(true); setError('');
    try {
      const result = await onBulk(sent.map(index => rows[index]), false);
      // The server numbers the rows it was sent; the operator knows them by the file's.
      const failures: NotDone[] = result.results.filter(row => !row.ok).map(row => {
        const index = sent[row.row - 1] ?? -1;
        return { index, row: rows[index] ?? { email: row.email }, reason: row.error ?? '발급하지 못했어요.', tone: 'bad', failed: true };
      });
      const skipped: NotDone[] = problems.flatMap((problem, index) => (problem === null ? []
        : [{ index, row: rows[index], reason: problem, tone: PROBLEM_TONE[problem], failed: false }]));
      setOutcome({
        issued: result.issued, skipped: skipped.length, failed: failures.length,
        issuedIds: result.results.flatMap(row => (row.ok && row.ticketId != null ? [row.ticketId] : [])),
        notDone: [...skipped, ...failures].sort((a, b) => a.index - b.index),
      });
      setStep(3);
    } catch (err) {
      setError(err instanceof Error ? err.message : '일괄 발급에 실패했어요.');
    } finally {
      setBusy(false);
    }
  }

  async function sendMail() {
    if (!outcome) return;
    setBusy(true); setError('');
    try {
      setMailed(await onMail(outcome.issuedIds));
      setConfirmMail(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : '메일을 보내지 못했어요.');
      setConfirmMail(false);
    } finally {
      setBusy(false);
    }
  }

  function startOver() {
    setText(''); setFileName(''); setOutcome(null); setMailed(null); setError(''); setFilter('auto'); setStep(1);
  }

  function downloadNotDone() {
    if (!outcome) return;
    downloadCsv(`발급-안-된-${outcome.notDone.length}행.csv`, [...importFields.map(f => f.label), '발급 안 된 이유'],
      outcome.notDone.map(item => [...importFields.map(f => item.row[f.key] ?? ''), item.reason]));
  }

  const tierField = fields.find(field => field.kind === 'TIER' && field.values.length > 0);

  if (step === 1) {
    return (
      <div className="issue-bulk-step">
        <Steps step={1} />
        <div className="issue-start">
          <div className="issue-start-main">
            <label className={`issue-drop${dragging ? ' is-over' : ''}`}
              onDragOver={event => { event.preventDefault(); setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={event => { event.preventDefault(); setDragging(false); void read(event.dataTransfer.files); }}>
              <input type="file" className="visually-hidden" accept=".csv,.tsv,.txt,text/csv"
                onChange={event => void read(event.target.files)} />
              <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5M12 18v-6M9 15l3-3 3 3" />
              </svg>
              <b>CSV 파일을 끌어다 놓거나 눌러서 고르세요</b>
              <span>엑셀에서 'CSV로 저장'한 파일 · 첫 줄은 열 이름</span>
            </label>
            <div className="issue-or" aria-hidden="true"><span>또는 붙여넣기</span></div>
            <label htmlFor="issue-bulk-text" className="visually-hidden">명단 붙여넣기</label>
            <textarea id="issue-bulk-text" className="bulk-text" rows={5} value={text}
              placeholder={`${importFields.map(f => f.label).join(',')}\nname@example.com`}
              onChange={event => { setText(event.target.value); setFileName(''); setError(''); }} />
            {error && <p className="error-text" role="alert">{error}</p>}
            <div className="issue-start-next">
              {rows.length > 0 && <span className="muted">{rows.length.toLocaleString()}행 읽었어요</span>}
              <button type="button" className="btn-primary" disabled={rows.length === 0}
                onClick={() => { setFilter('auto'); setStep(2); }}>다음: 확인하기</button>
            </div>
          </div>
          <aside className="console-card issue-columns" aria-labelledby="issue-columns-title">
            <h3 id="issue-columns-title">이 이벤트의 열
              <InfoTip label="명단 넣는 법">
                엑셀에서 CSV로 저장하거나, 시트에서 셀을 복사해 붙여넣으세요. 첫 줄에 열 이름이 있으면 순서가 달라도
                알아서 맞춰요. 열 이름이 없으면 아래 순서대로 읽어요.
              </InfoTip></h3>
            <ul className="issue-chips">
              {importFields.map(field => (
                <li key={field.key} className={field.required ? 'on' : ''}>{field.label}{field.required ? ' · 필수' : ''}</li>
              ))}
            </ul>
            <p className="issue-columns-hint">
              {tierField ? `${tierField.label}은 ${tierField.values.join(', ')} 중 하나. ` : ''}열 순서는 상관없어요.
            </p>
            <button type="button" className="btn-secondary issue-sample"
              onClick={() => downloadSample('입장권-발급-양식.csv', importFields)}><DownloadIcon />양식 내려받기</button>
          </aside>
        </div>
      </div>
    );
  }

  if (step === 3 && outcome) {
    const notDone = outcome.notDone.length;
    return (
      <div className="issue-bulk-step">
        <Steps step={3} />
        <section className="console-card issue-result" aria-labelledby="issue-result-title">
          <h3 id="issue-result-title" ref={doneHeading} tabIndex={-1}>
            {outcome.issued > 0 ? `${outcome.issued.toLocaleString()}장 발급했어요` : '한 장도 발급하지 못했어요'}</h3>
          <div className="issue-numbers">
            <div className="ok"><span>{mailed ? '발급 · 메일 보냄' : '발급 · 아직 안 보냄'}</span><b>{outcome.issued.toLocaleString()}</b></div>
            <div className="warn"><span>미리 건너뜀</span><b>{outcome.skipped.toLocaleString()}</b></div>
            <div className="bad"><span>발급 중 실패</span><b>{outcome.failed.toLocaleString()}</b></div>
          </div>
          {outcome.issuedIds.length > 0 && (
            <div className={`issue-mail${mailed ? ' is-done' : ''}`} role="status">
              {mailed ? (<>
                <b>{mailed.sent.toLocaleString()}명에게 메일을 보냈어요{mailed.skipped.length > 0 ? ` · ${mailed.skipped.length.toLocaleString()}명은 못 보냈어요` : ''}</b>
                {mailed.skipped.length > 0 && (
                  <ul>{mailed.skipped.map(row => <li key={row.ticketId}>{row.ticketRef ?? row.ticketId} - {row.reason}</li>)}</ul>
                )}
              </>) : (<>
                <span><b>아직 메일을 보내지 않았어요.</b> 명단이 맞는지 본 뒤 보내세요. 발급 현황에서 나중에 보낼 수도 있어요.</span>
                <button type="button" className="btn-primary" disabled={busy} onClick={() => setConfirmMail(true)}>
                  {busy ? '보내는 중…' : `발급한 ${outcome.issuedIds.length.toLocaleString()}명에게 메일 보내기`}</button>
              </>)}
            </div>
          )}
          {error && <p className="error-text" role="alert">{error}</p>}
          {notDone > 0 && (
            <div className="issue-notdone">
              <b id="issue-notdone-title">발급되지 않은 행</b>
              <ul aria-labelledby="issue-notdone-title">
                {outcome.notDone.map(item => (
                  <li key={`${item.failed ? 'f' : 's'}${item.index}`}>
                    <span className="issue-row-n">{item.index >= 0 ? `${item.index + 1}행` : '-'}</span>
                    <span className="issue-row-email">{item.row.email || '(이메일 없음)'}</span>
                    <span className={`issue-row-why ${item.tone}`}>{item.reason}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="issue-result-actions">
            {notDone > 0 && (
              <button type="button" className="btn-secondary" onClick={downloadNotDone}>
                <DownloadIcon />안 된 {notDone.toLocaleString()}행 CSV로 받기</button>
            )}
            <button type="button" className="btn-secondary" onClick={startOver}>다른 명단 넣기</button>
            <button type="button" className="btn-primary" onClick={onShowTickets}>발급 현황에서 보기</button>
          </div>
        </section>
        <ConfirmDialog
          open={confirmMail}
          tone="primary"
          title={`${outcome.issuedIds.length.toLocaleString()}명에게 메일을 보낼까요?`}
          message="방금 발급한 입장권마다 받는 사람에게 링크를 보내요. 보낸 메일은 되돌릴 수 없어요."
          confirmLabel="메일 보내기"
          busy={busy}
          onCancel={() => setConfirmMail(false)}
          onConfirm={() => void sendMail()}
        />
      </div>
    );
  }

  // Step 2: the list the way it was read, and what will be set aside.
  const visible = rows.map((row, index) => ({ row, index, problem: problems[index] }))
    .filter(item => shown === 'all' || item.problem !== null);
  return (
    <div className="issue-bulk-step issue-check">
      <Steps step={2} />
      <div className="issue-check-head">
        <h3 ref={stepHeading} tabIndex={-1}>{fileName || '붙여넣은 명단'} · {rows.length.toLocaleString()}행</h3>
        <ul className="issue-tally" aria-label="확인 결과">
          <li className="ok">발급 {sendable.toLocaleString()}</li>
          {(Object.entries(tally) as Array<[RowProblem, number]>).map(([problem, n]) => (
            <li key={problem} className={PROBLEM_TONE[problem]}>{problem} {n.toLocaleString()} · 건너뜀</li>
          ))}
        </ul>
        <button type="button" className="link-button" onClick={startOver}>다른 파일로 바꾸기</button>
      </div>

      <div className="console-card issue-rows">
        <div className="issue-filter" role="group" aria-label="보기">
          <button type="button" aria-pressed={shown === 'skip'} onClick={() => setFilter('skip')}>
            건너뛸 행만 ({invalid.length.toLocaleString()})</button>
          <button type="button" aria-pressed={shown === 'all'} onClick={() => setFilter('all')}>
            전체 ({rows.length.toLocaleString()})</button>
        </div>
        {visible.length === 0 ? (
          <p className="console-none issue-rows-none">건너뛸 행이 없어요. {sendable.toLocaleString()}행 모두 발급해요.</p>
        ) : (<>
          <div className="console-table-box flat issue-table">
            <table className="console-table">
              <thead>
                <tr><th scope="col">행</th>{importFields.map(f => <th key={f.key} scope="col">{f.label}</th>)}<th scope="col">확인</th></tr>
              </thead>
              <tbody>
                {visible.map(({ row, index, problem }) => (
                  <tr key={index} className={problem ? `row-${PROBLEM_TONE[problem]}` : ''}>
                    <td className="muted">{index + 1}</td>
                    {importFields.map(f => <td key={f.key}>{row[f.key] || '-'}</td>)}
                    <td className={problem ? `issue-row-why ${PROBLEM_TONE[problem]}` : 'muted'}>{problem ? `${problem} · 건너뜀` : '발급'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ul className="issue-cards" aria-label={shown === 'skip' ? '건너뛸 행' : '전체 행'}>
            {visible.map(({ row, index, problem }) => (
              <li key={index} className={problem ? `row-${PROBLEM_TONE[problem]}` : ''}>
                <span className="issue-card-top"><b>{row.email || '(이메일 없음)'}</b><span className="muted">{index + 1}행</span></span>
                {importFields.some(f => f.key !== 'email' && row[f.key]) && (
                  <span className="issue-card-more">
                    {importFields.filter(f => f.key !== 'email' && row[f.key]).map(f => `${f.label} ${row[f.key]}`).join(' · ')}</span>
                )}
                <span className={problem ? `issue-row-why ${PROBLEM_TONE[problem]}` : 'muted'}>{problem ? `${problem} · 건너뜀` : '발급'}</span>
              </li>
            ))}
          </ul>
        </>)}
      </div>

      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="issue-bar">
        <span className="issue-notify muted">메일은 발급한 뒤 결과에서 따로 보내요.</span>
        <div className="issue-bar-buttons">
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => setStep(1)}>이전</button>
          <button type="button" className="btn-primary" disabled={busy || sendable === 0} onClick={() => void submit()}>
            {busy ? '발급 중…' : `${sendable.toLocaleString()}장 발급하기`}</button>
        </div>
      </div>
    </div>
  );
}
