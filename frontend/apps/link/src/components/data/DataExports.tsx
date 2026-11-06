import { Fragment, useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { mutate } from '@shared/auth';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import RowMenu, { type MenuItem } from '@shared/components/RowMenu';
import { DataBar } from './DataLayout';
import {
  copyText, dayClock, getJson, num, PII_LABEL, read, type AccessRow, type ExportRow, type ExportStatus,
} from './dataApi';

const ACCESS_LABEL: Record<AccessRow['action'], string> = {
  OPEN: '열어 봄', CODE_SENT: '코드 보냄', VERIFIED: '코드 확인', REFUSED: '거절', DOWNLOAD: '내려받음',
};

function statusPill(row: ExportRow): { text: string; tone: string } {
  const labels: Record<ExportStatus, { text: string; tone: string }> = {
    OPEN: { text: '열림', tone: 'pill-ok' },
    EXPIRED: { text: '기간 끝 · 파일 지움', tone: 'pill-out' },
    CLOSED: { text: '닫음 · 파일 지움', tone: 'pill-off' },
    USED_UP: { text: '횟수 다 씀', tone: 'pill-out' },
  };
  if (row.status !== 'OPEN') return labels[row.status];
  const left = new Date(row.expiresAt).getTime() - Date.now();
  const days = Math.ceil(left / (24 * 60 * 60 * 1000));
  const hours = Math.max(1, Math.ceil(left / (60 * 60 * 1000)));
  return { text: `열림 · ${days > 1 ? `${days}일` : `${hours}시간`} 남음`, tone: 'pill-ok' };
}

/** Every link made for someone outside, what it held and who has opened it. */
export default function DataExports() {
  const [params] = useSearchParams();
  const createdId = params.get('created');
  const [rows, setRows] = useState<ExportRow[] | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [closing, setClosing] = useState<ExportRow | null>(null);
  const [history, setHistory] = useState<{ id: ExportRow['id']; rows: AccessRow[] | null; error?: string } | null>(null);

  const load = useCallback(async () => {
    try { setRows(await getJson<ExportRow[]>('/api/admin/data/exports')); }
    catch (err) { setError(err instanceof Error ? err.message : '내보내기 목록을 불러오지 못했어요.'); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function act(action: () => Promise<void>) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
  }

  const copy = async (url: string) => {
    setNotice(await copyText(url) ? '링크를 복사했어요.' : '복사하지 못했어요. 링크를 직접 골라서 복사해 주세요.');
  };
  const send = (row: ExportRow) => act(async () => {
    await read(await mutate(`/api/admin/data/exports/${row.id}/send`, { method: 'POST' }));
    setNotice(`${row.recipientEmail}로 링크를 보냈어요.`);
  });
  const close = (row: ExportRow) => act(async () => {
    await read(await mutate(`/api/admin/data/exports/${row.id}/close`, { method: 'POST' }));
    setClosing(null);
    setNotice(`${row.recipientName}에 보낸 링크를 닫았어요. 파일도 지웠어요.`);
    await load();
  });
  async function showHistory(row: ExportRow) {
    if (history?.id === row.id) { setHistory(null); return; }
    setHistory({ id: row.id, rows: null });
    try {
      const found = await getJson<AccessRow[]>(`/api/admin/data/exports/${row.id}/access`);
      setHistory(current => current?.id === row.id ? { id: row.id, rows: found } : current);
    } catch (err) {
      setHistory(current => current?.id === row.id
        ? { id: row.id, rows: [], error: err instanceof Error ? err.message : '기록을 불러오지 못했어요.' } : current);
    }
  }

  const created = createdId && rows ? rows.find(row => String(row.id) === createdId) : undefined;

  return (
    <>
      <DataBar>
        <Link className="data-tool data-tool-primary" to="/admin/data/exports/new">새로 만들기</Link>
      </DataBar>

      {created && created.url && (
        <section role="status" aria-labelledby="export-created" className="export-created">
          <h3 id="export-created">링크를 만들었어요 · {dayClock(created.expiresAt)}까지</h3>
          <div className="export-created-row">
            <label htmlFor="export-created-url" className="visually-hidden">임시 링크</label>
            <input id="export-created-url" readOnly value={created.url} onFocus={e => e.currentTarget.select()} />
            <button type="button" className="export-copy" onClick={() => void copy(created.url!)}>복사</button>
            <button type="button" className="export-send" disabled={busy} onClick={() => void send(created)}>
              {created.recipientEmail}로 보내기
            </button>
          </div>
        </section>
      )}

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      {rows == null ? <p className="loading">내보내기 목록을 불러오고 있어요.</p> : rows.length === 0 ? (
        <div className="data-card data-empty-card">
          <p>아직 만든 내보내기가 없어요.</p>
          <p className="hint-text">주최측에 보낼 데이터를 골라 파일로 만들고, 정해진 기간만 열리는 링크로 전할 수 있어요.</p>
        </div>
      ) : (
        <div className="data-table-wrap" role="region" aria-label="내보내기 목록" tabIndex={0}>
          <table className="data-table export-table">
            <thead>
              <tr>
                <th scope="col">받는 곳</th><th scope="col">담은 것</th><th scope="col">개인정보</th>
                <th scope="col">상태</th><th scope="col">내려받음</th><th scope="col"><span className="visually-hidden">메뉴</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map(row => {
                const pill = statusPill(row);
                const open = row.status === 'OPEN';
                const items: Array<MenuItem | 'separator'> = [
                  ...(open && row.url ? [{ label: '링크 복사', onSelect: () => void copy(row.url!) }] : []),
                  ...(open ? [{ label: '메일로 보내기', onSelect: () => void send(row), disabled: busy }] : []),
                  { label: history?.id === row.id ? '기록 닫기' : '기록', onSelect: () => void showHistory(row) },
                  ...(open ? ['separator' as const, { label: '닫기', danger: true, onSelect: () => setClosing(row) }] : []),
                ];
                return (
                  <Fragment key={row.id}>
                    <tr className={open ? undefined : 'export-done'}>
                      <td>
                        <b className="export-to">{row.recipientName}</b>
                        <small>{dayClock(row.createdAt)} · {row.createdBy}</small>
                      </td>
                      <td>
                        {row.datasets.map(set => set.label).join(', ') || '—'}
                        {row.sessionTitle && <small>{row.sessionTitle}</small>}
                      </td>
                      <td>{PII_LABEL[row.pii]}{row.note && <small>{row.note}</small>}</td>
                      <td><span className={`pill ${pill.tone}`}>{pill.text}</span></td>
                      <td className="nowrap">{num(row.downloads)} / {row.maxDownloads == null ? '제한 없음' : num(row.maxDownloads)}</td>
                      <td className="export-menu">
                        <RowMenu label={`${row.recipientName} 내보내기 관리`} items={items} />
                      </td>
                    </tr>
                    {history?.id === row.id && (
                      <tr className="export-history">
                        <td colSpan={6}>
                          <h4>{row.recipientName} · 여는 기록</h4>
                          {history.error ? <p className="error-text" role="alert">{history.error}</p>
                            : history.rows == null ? <p className="hint-text">기록을 불러오고 있어요.</p>
                              : history.rows.length === 0 ? <p className="hint-text">아직 아무도 열지 않았어요.</p> : (
                                <ul className="access-list">
                                  {history.rows.map((access, index) => (
                                    <li key={`${access.at}-${index}`} className={access.action === 'REFUSED' ? 'data-bad' : undefined}>
                                      <span className="nowrap">{dayClock(access.at)}</span>
                                      <b>{ACCESS_LABEL[access.action] ?? access.action}{access.file ? ` · ${access.file}` : ''}</b>
                                      <span className="muted">{[access.ip, access.userAgent].filter(Boolean).join(' · ')}</span>
                                    </li>
                                  ))}
                                </ul>
                              )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint-text">누가 언제 어디서 내려받았는지는 각 줄의 기록에 남아요.</p>

      <ConfirmDialog
        open={closing != null}
        title="이 링크를 닫을까요?"
        message={`${closing?.recipientName ?? ''}에서 더 이상 열 수 없고, 만든 파일도 바로 지워져요. 되돌릴 수 없어요.`}
        confirmLabel="닫기"
        cancelLabel="그대로 두기"
        tone="danger"
        busy={busy}
        onConfirm={() => closing && void close(closing)}
        onCancel={() => setClosing(null)}
      />
    </>
  );
}
