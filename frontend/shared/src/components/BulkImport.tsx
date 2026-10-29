import { useEffect, useRef, useState } from 'react';
import { columnIndexes, parseTable } from '../csv';

export interface ImportField {
  key: string;
  label: string;
  /** Header words that map to this field, lower-case and space-free. */
  match: string[];
  required?: boolean;
}

export interface ImportResult {
  issued: number;
  failed: number;
  results: Array<{ row: number; email: string; ok: boolean; error?: string }>;
}

/**
 * Bulk issue from a spreadsheet.
 *
 * <p>Nothing is sent until the operator has seen the table the way we read it: a file
 * that was saved with the wrong delimiter or a column in an unexpected order shows up
 * as visibly wrong rows, not as two hundred surprise emails.
 */
export default function BulkImport({ fields, sampleName, notifyLabel, onSubmit, checkTaken }: {
  fields: ImportField[];
  sampleName: string;
  notifyLabel: string;
  onSubmit: (rows: Array<Record<string, string>>, notify: boolean) => Promise<ImportResult>;
  /**
   * Which of these addresses already hold one, lower-cased. Given, rows for them are
   * set aside before anything is sent, instead of coming back as failures.
   */
  checkTaken?: (emails: string[]) => Promise<Set<string>>;
}) {
  const [text, setText] = useState('');
  const [fileName, setFileName] = useState('');
  const [notify, setNotify] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<ImportResult | null>(null);
  const file = useRef<HTMLInputElement>(null);

  const table = parseTable(text);
  const mapping = columnIndexes(table.headers, Object.fromEntries(fields.map(f => [f.key, f.match])));
  // With no header row the columns are taken in the order the fields are declared.
  const indexFor = (key: string, position: number) =>
    table.headers.length > 0 ? mapping[key] : position;

  const rows: Array<Record<string, string>> = table.rows.map(cells => {
    const row: Record<string, string> = {};
    fields.forEach((field, position) => {
      const index = indexFor(field.key, position);
      row[field.key] = index === undefined ? '' : (cells[index] ?? '').trim();
    });
    return row;
  });

  const required = fields.filter(f => f.required).map(f => f.key);
  const hasEmail = fields.some(f => f.key === 'email');
  const [taken, setTaken] = useState<Set<string>>(new Set());
  const emailKey = rows.map(row => (row.email ?? '').toLowerCase()).join('\n');
  useEffect(() => {
    if (!checkTaken || !hasEmail) return;
    const emails = [...new Set(emailKey.split('\n').filter(Boolean))];
    if (emails.length === 0) { setTaken(new Set()); return; }
    let current = true;
    const timer = window.setTimeout(() => {
      checkTaken(emails).then(found => { if (current) setTaken(found); }).catch(() => { /* the upload still reports per row */ });
    }, 300);
    return () => { current = false; window.clearTimeout(timer); };
  }, [emailKey, checkTaken, hasEmail]);

  // Why a row will not be sent, said before sending: empty, malformed, twice in the
  // file, or already holding one.
  const seen = new Set<string>();
  const problems = rows.map(row => {
    if (required.some(key => !row[key])) return '필수 값 없음';
    // Only an upload that can ask the server checks addresses here; others keep the
    // server's own per-row answer.
    if (!hasEmail || !checkTaken) return null;
    const email = row.email.toLowerCase();
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return '이메일 형식 오류';
    if (seen.has(email)) return '파일 안에서 중복';
    seen.add(email);
    if (taken.has(email)) return '이미 발급됨';
    return null;
  });
  const invalid = rows.filter((_, index) => problems[index] !== null);
  const tally = problems.reduce<Record<string, number>>((sum, problem) => {
    if (problem) sum[problem] = (sum[problem] ?? 0) + 1;
    return sum;
  }, {});

  async function read(files: FileList | null) {
    const chosen = files?.[0];
    if (!chosen) return;
    setFileName(chosen.name);
    setError(''); setResult(null);
    try { setText(await chosen.text()); }
    catch { setError('파일을 읽지 못했어요.'); }
  }

  async function submit() {
    setBusy(true); setError(''); setResult(null);
    try {
      setResult(await onSubmit(rows.filter((_, index) => problems[index] === null), notify));
      setText('');
      if (file.current) file.current.value = '';
    } catch (err) {
      setError(err instanceof Error ? err.message : '일괄 발급에 실패했어요.');
    } finally {
      setBusy(false);
    }
  }

  function downloadSample() {
    const header = fields.map(f => f.label).join(',');
    const example = fields.map(f => (f.key === 'email' ? 'name@example.com' : '')).join(',');
    const blob = new Blob(['﻿' + header + '\n' + example + '\n'], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = sampleName;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="bulk-import">
      <h3>CSV 일괄 발급</h3>
      <p className="hint-text">
        엑셀에서 CSV로 저장하거나, 시트에서 셀을 복사해 아래에 붙여넣으세요. 열 이름이 있으면 순서가
        달라도 알아서 맞춥니다 ({fields.map(f => f.label).join(' · ')}).
      </p>

      <div className="bulk-actions">
        {/* The browser's own file button speaks the browser's language ("Choose File");
            this one speaks the page's, and says which file was taken. */}
        <label className={`btn-tiny file-pick${busy ? ' is-disabled' : ''}`}>
          <input ref={file} type="file" accept=".csv,.tsv,.txt,text/csv" disabled={busy}
            onChange={event => void read(event.target.files)} />
          CSV 파일 선택
        </label>
        <span className="file-name">{fileName || '선택한 파일 없음'}</span>
        <button type="button" className="btn-tiny" onClick={downloadSample}>양식 내려받기</button>
      </div>

      <textarea className="bulk-text" rows={4} value={text} disabled={busy}
        placeholder={`${fields.map(f => f.label).join(',')}\nname@example.com`}
        onChange={event => { setText(event.target.value); setResult(null); }} />

      {rows.length > 0 && (
        <>
          <div className="bulk-preview">
            <table>
              <thead>
                <tr><th>#</th>{fields.map(f => <th key={f.key}>{f.label}</th>)}<th>확인</th></tr>
              </thead>
              <tbody>
                {/* The first rows, then every row that will be set aside, so a problem
                    on row 140 is not hidden behind "… and 135 more". */}
                {rows.map((row, index) => ({ row, index }))
                  .filter(({ index }) => index < 5 || problems[index] !== null).slice(0, 30)
                  .map(({ row, index }) => (
                  <tr key={index} className={problems[index] ? 'row-bad' : ''}>
                    <td>{index + 1}</td>
                    {fields.map(f => <td key={f.key}>{row[f.key] || '-'}</td>)}
                    <td>{problems[index] ?? '발급'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rows.length > 5 && <p className="hint-text">전체 {rows.length.toLocaleString()}행</p>}
          </div>

          <label className="field-inline">
            <input type="checkbox" checked={notify} disabled={busy}
              onChange={event => setNotify(event.target.checked)} />
            {notifyLabel}
          </label>

          <div className="bulk-tally" role="status">
            <span className="ok">발급 {(rows.length - invalid.length).toLocaleString()}</span>
            {Object.entries(tally).map(([problem, n]) => (
              <span key={problem} className="skip">{problem} {n.toLocaleString()} · 건너뜀</span>
            ))}
          </div>
          <button className="btn-primary" type="button" disabled={busy || rows.length === invalid.length}
            onClick={() => void submit()}>
            {busy ? '발급 중…' : `${rows.length - invalid.length}건 발급하기`}
          </button>
        </>
      )}

      {error && <p className="error-text" role="alert">{error}</p>}

      {result && (
        <div className="bulk-result" role="status">
          <p className="notice-text">성공 {result.issued}건 · 실패 {result.failed}건</p>
          {result.failed > 0 && (
            <ul className="bulk-errors">
              {result.results.filter(row => !row.ok).map(row => (
                <li key={row.row}>{row.row}행 {row.email && <code>{row.email}</code>} - {row.error}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
