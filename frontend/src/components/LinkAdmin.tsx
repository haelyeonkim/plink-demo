import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { mutate } from '../auth';
import BulkImport from './BulkImport';
import ConfirmDialog from './ConfirmDialog';
import {
  deleteLink, deleteRecipient, fetchLink, fetchLinks, issueRecipient, setRecipientRevoked,
} from '../api';
import type { LinkDetail, LinkRecipient, ProtectedLink } from '../types';

type Tab = 'issue' | 'links' | 'views' | 'settings';

const TABS: Array<[Tab, string]> = [
  ['issue', '발급'],
  ['links', '발급 현황'],
  ['views', '열람 기록'],
  ['settings', '설정'],
];

function formatDate(value: string): string {
  return new Date(value).toLocaleString('ko-KR', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

function timeAgo(value: string): string {
  const minutes = Math.floor((Date.now() - new Date(value).getTime()) / 60000);
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

/** "yyyy-MM-ddTHH:mm" in the operator's own zone, which is what the input speaks. */
function localInput(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * The link console, built the way the ticket console is: pick the link at the top, and
 * every tab below works on that one.
 *
 * <p>A link is to its issued addresses what an event is to its tickets - the thing that
 * holds the policy - and one issued address belongs to one person, exactly like one
 * ticket.
 */
export default function LinkAdmin() {
  const [params, setParams] = useSearchParams();
  const [links, setLinks] = useState<ProtectedLink[]>([]);
  const [detail, setDetail] = useState<LinkDetail | null>(null);
  const [email, setEmail] = useState('');
  const [label, setLabel] = useState('');
  const [notify, setNotify] = useState(true);
  const [issued, setIssued] = useState<LinkRecipient | null>(null);
  const [copied, setCopied] = useState<number | null>(null);
  const [pending, setPending] = useState<LinkRecipient | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const requested = params.get('tab');
  const tab: Tab = TABS.some(([key]) => key === requested) ? (requested as Tab) : 'issue';
  const selected = Number(params.get('link')) || null;

  const loadLinks = useCallback(async () => {
    try { setLinks(await fetchLinks()); }
    catch { setError('링크를 불러오지 못했어요.'); }
  }, []);

  const loadDetail = useCallback(async (id: number) => {
    try { setDetail(await fetchLink(id)); }
    catch { setError('링크 정보를 불러오지 못했어요.'); }
  }, []);

  useEffect(() => { void loadLinks(); }, [loadLinks]);
  useEffect(() => {
    if (selected === null) { setDetail(null); return; }
    void loadDetail(selected);
  }, [selected, loadDetail]);

  function show(next: Partial<{ tab: Tab; link: number | null }>) {
    const tabValue = next.tab ?? tab;
    const linkValue = next.link === undefined ? selected : next.link;
    setParams(linkValue ? { tab: tabValue, link: String(linkValue) } : { tab: tabValue });
    setIssued(null);
  }

  async function act(action: () => Promise<void>) {
    setError(''); setNotice(''); setBusy(true);
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
  }

  const issue = () => act(async () => {
    if (!selected) return;
    const recipient = await issueRecipient(selected, email.trim(), label.trim(), notify);
    setEmail(''); setLabel('');
    setIssued(recipient);
    setNotice(recipient.deliveredVia === 'EMAIL'
      ? `${recipient.email} 주소로 링크를 보냈어요.`
      : '링크를 발급했어요. 아래 주소를 전달해 주세요.');
    await Promise.all([loadDetail(selected), loadLinks()]);
  });

  const issueBulk = async (rows: Array<Record<string, string>>, notifyAll: boolean) => {
    if (!selected) throw new Error('먼저 링크를 선택해 주세요.');
    const response = await mutate(`/api/links/${selected}/recipients/bulk`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ notify: notifyAll, rows }),
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || '일괄 발급에 실패했어요.');
    setNotice(`${result.issued}건 발급했어요${result.failed ? ` · ${result.failed}건 실패` : ''}.`);
    await Promise.all([loadDetail(selected), loadLinks()]);
    return result;
  };

  const toggle = (recipient: LinkRecipient) => act(async () => {
    if (!selected) return;
    await setRecipientRevoked(selected, recipient.id, !recipient.revoked);
    setNotice(recipient.revoked
      ? `${recipient.email} 링크를 다시 사용할 수 있어요.`
      : `${recipient.email} 링크를 비활성화했어요.`);
    await loadDetail(selected);
  });

  const removeRecipient = (recipient: LinkRecipient) => act(async () => {
    if (!selected) return;
    await deleteRecipient(selected, recipient.id);
    setPending(null);
    setNotice('발급한 링크를 지웠어요.');
    await Promise.all([loadDetail(selected), loadLinks()]);
  });

  const saveSettings = (form: HTMLFormElement) => act(async () => {
    if (!selected) return;
    const data = new FormData(form);
    const deadline = String(data.get('expiresAt') || '');
    const password = String(data.get('password') || '');
    const clear = data.get('clearPassword') === 'on';
    const body: Record<string, unknown> = {
      title: data.get('title'),
      expiresAt: deadline ? `${deadline.replace('T', ' ')}:00` : null,
      maxViews: Number(data.get('maxViews') || 0),
    };
    // Absent means "leave it"; empty means "remove it".
    if (clear) body.password = '';
    else if (password) body.password = password;
    const response = await mutate(`/api/links/${selected}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    if (!response.ok) {
      const failed = await response.json().catch(() => ({}));
      throw new Error(failed.error || '설정을 저장하지 못했어요.');
    }
    setNotice('설정을 저장했어요.');
    await Promise.all([loadDetail(selected), loadLinks()]);
  });

  const removeLink = () => act(async () => {
    if (!selected) return;
    await deleteLink(selected);
    setConfirmDelete(false);
    setNotice('링크를 지웠어요.');
    setParams({ tab: 'issue' });
    await loadLinks();
  });

  function copy(recipient: LinkRecipient) {
    void navigator.clipboard.writeText(recipient.url);
    setCopied(recipient.id);
    window.setTimeout(() => setCopied(null), 1500);
  }

  const expired = detail?.expiresAt ? new Date(detail.expiresAt).getTime() < Date.now() : false;
  const claimed = detail?.recipients.filter(r => r.claimed).length ?? 0;

  return (
    <section className="page-section page-wide">
      <p className="eyebrow"><span></span> PRIVATE LINK</p>
      <h2>링크 관리</h2>

      <div className="session-bar">
        <div className="field">
          <select id="link-picker" aria-label="링크 선택" value={selected ?? ''}
            onChange={e => show({ link: e.target.value ? Number(e.target.value) : null })}>
            <option value="">링크를 선택하세요</option>
            {links.map(row => (
              <option key={row.id} value={row.id}>
                {row.title || row.originalUrl} · 발급 {row.recipientCount}개
              </option>
            ))}
          </select>
        </div>
        <Link className="btn-secondary" to="/links/new">링크 추가</Link>
      </div>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="tabs" role="tablist">
        {TABS.map(([key, name]) => (
          <button key={key} role="tab" aria-selected={tab === key}
            className={tab === key ? 'active' : ''} onClick={() => show({ tab: key })}>{name}</button>
        ))}
      </div>

      {!detail && (
        <div className="tab-panel">
          <p className="hint-text">
            {links.length === 0
              ? '아직 링크가 없어요. 링크 추가로 원본 주소를 하나 등록해 주세요.'
              : '먼저 링크를 선택해 주세요.'}
          </p>
        </div>
      )}

      {detail && tab === 'issue' && (
        <div className="tab-panel tab-split">
          <form onSubmit={e => { e.preventDefault(); void issue(); }}>
            <h3>링크 발급</h3>
            <div className="field">
              <label htmlFor="recipient-email">수신자 이메일</label>
              <input id="recipient-email" type="email" required value={email} disabled={busy}
                placeholder="name@example.com" onChange={e => setEmail(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="recipient-label">메모 (선택)</label>
              <input id="recipient-label" value={label} disabled={busy} placeholder="예: 법무팀 김지수"
                onChange={e => setLabel(e.target.value)} />
            </div>
            <label className="field-inline">
              <input type="checkbox" checked={notify} disabled={busy}
                onChange={e => setNotify(e.target.checked)} />
              발급하면서 수신자에게 메일로 보내기
            </label>
            <button className="btn-primary" type="submit" disabled={busy || !email.trim()}>
              {busy ? '발급 중…' : notify ? '발급하고 메일 보내기' : '링크 발급'}
            </button>
            {issued && (
              <div className="issued-link">
                <code title={issued.url}>{issued.url}</code>
                <button type="button" className="btn-tiny" onClick={() => copy(issued)}>
                  {copied === issued.id ? '복사됨' : '복사'}
                </button>
                <p className="hint-text">이 주소는 수신자 한 사람의 것입니다.</p>
              </div>
            )}
          </form>

          <div>
            <h3>링크 정보</h3>
            <dl className="detail-list">
              <dt>원본 URL</dt>
              <dd><a href={detail.originalUrl} target="_blank" rel="noopener">{detail.originalUrl}</a></dd>
              <dt>발급</dt>
              <dd>링크 {detail.recipients.length}개 · 등록 완료 {claimed}명</dd>
              <dt>만료</dt>
              <dd className={expired ? 'expired' : ''}>
                {detail.expiresAt ? formatDate(detail.expiresAt) : '없음'}{expired ? ' · 만료됨' : ''}
              </dd>
              <dt>보호</dt>
              <dd>{detail.hasPassword ? '비밀번호 있음' : '비밀번호 없음'}</dd>
            </dl>
            <p className="hint-text">
              수신자마다 링크를 하나씩 발급해 각각 전달하세요. 패스키는 입장권과 같은 방식으로
              사람(이메일)에 귀속되므로, 한 사람이 여러 링크를 받아도 패스키는 하나입니다.
            </p>

            <BulkImport
              fields={[
                { key: 'email', label: '이메일', match: ['email', '이메일', '메일'], required: true },
                { key: 'label', label: '메모', match: ['label', '메모', '이름', 'name', '비고'] },
              ]}
              sampleName="수신자-발급-양식.csv"
              notifyLabel="발급하면서 수신자에게 메일로 보내기"
              onSubmit={issueBulk} />
          </div>
        </div>
      )}

      {detail && tab === 'links' && (
        <div className="tab-panel">
          <div className="panel-head">
            <h3>발급 현황 ({detail.recipients.length})</h3>
            <button className="btn-tiny" onClick={() => show({ tab: 'issue' })}>발급하기</button>
          </div>
          {detail.recipients.length === 0 ? (
            <p className="empty">아직 발급한 링크가 없어요.</p>
          ) : (
            <ul className="entity-list">
              {detail.recipients.map(recipient => (
                <li key={recipient.id} className={recipient.revoked ? 'revoked' : undefined}>
                  <div className="entity-main">
                    <span className="entity-title">
                      {recipient.email}{recipient.label ? ` · ${recipient.label}` : ''}
                    </span>
                    <span className="entity-meta">
                      <code title={recipient.url}>
                        {recipient.url.replace(/^https?:\/\/[^/]+/, '')}
                      </code>
                      <span className={recipient.claimed ? 'claimed' : ''}>
                        {recipient.claimed ? '등록 완료' : '미등록'}
                      </span>
                      <span>열람 {recipient.viewCount}회</span>
                      {recipient.revoked && <span className="expired">비활성</span>}
                    </span>
                  </div>
                  <div className="entity-actions">
                    <button className="btn-tiny" onClick={() => copy(recipient)}>
                      {copied === recipient.id ? '복사됨' : '복사'}
                    </button>
                    <button className="btn-tiny" disabled={busy} onClick={() => void toggle(recipient)}>
                      {recipient.revoked ? '다시 사용' : '비활성화'}
                    </button>
                    <button className="btn-delete" title="삭제" disabled={busy}
                      onClick={() => setPending(recipient)}>&times;</button>
                  </div>
                </li>
              ))}
            </ul>
          )}
          <p className="hint-text">
            비활성화하면 그 사람의 링크만 닫힙니다. 나머지 수신자는 그대로 열 수 있어요.
          </p>
        </div>
      )}

      {detail && tab === 'views' && (
        <div className="tab-panel">
          <h3>열람 기록</h3>
          <dl className="stat-grid stat-grid-4">
            <div><dt>열람</dt><dd>{detail.viewCount}{detail.maxViews > 0 ? ` / ${detail.maxViews}` : ''}</dd></div>
            <div><dt>발급</dt><dd>{detail.recipients.length}</dd></div>
            <div><dt>등록 완료</dt><dd>{claimed}</dd></div>
            <div>
              <dt>마지막 열람</dt>
              <dd className="stat-small">{detail.views.length > 0 ? timeAgo(detail.views[0].viewedAt) : '-'}</dd>
            </div>
          </dl>
          {detail.views.length === 0 ? (
            <p className="empty">아직 열람 기록이 없어요.</p>
          ) : (
            <ul className="view-list view-list-wide">
              {detail.views.map((view, index) => (
                <li key={index}>
                  <span className="viewer-avatar">{view.viewerName.charAt(0)}</span>
                  <div>
                    <b>{view.viewerName}</b>
                    <small>{timeAgo(view.viewedAt)} · {formatDate(view.viewedAt)}</small>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {detail && tab === 'settings' && (
        <div className="tab-panel tab-split">
          <form key={detail.id} onSubmit={e => { e.preventDefault(); saveSettings(e.currentTarget); }}>
            <h3>링크 설정</h3>
            <div className="field">
              <label htmlFor="settings-title">제목</label>
              <input id="settings-title" name="title" defaultValue={detail.title ?? ''} />
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="settings-expires">만료 일시</label>
                <input id="settings-expires" name="expiresAt" type="datetime-local"
                  defaultValue={localInput(detail.expiresAt)} />
              </div>
              <div className="field">
                <label htmlFor="settings-max">최대 열람</label>
                <input id="settings-max" name="maxViews" type="number" min={0}
                  defaultValue={detail.maxViews} />
              </div>
            </div>
            <div className="field">
              <label htmlFor="settings-password">
                비밀번호 {detail.hasPassword ? '(바꾸려면 입력)' : '(선택)'}
              </label>
              <input id="settings-password" name="password" type="password" autoComplete="new-password" />
            </div>
            {detail.hasPassword && (
              <label className="field-inline">
                <input type="checkbox" name="clearPassword" />
                비밀번호 없애기
              </label>
            )}
            <p className="hint-text">
              만료를 비우면 만료되지 않고, 최대 열람 0은 제한 없음입니다. 이미 발급한 링크에도 바로
              적용됩니다.
            </p>
            <button className="btn-primary" type="submit" disabled={busy}>설정 저장</button>
          </form>

          <div className="danger-zone">
            <h3>링크 삭제</h3>
            <p className="hint-text">
              발급한 수신자 링크 {detail.recipients.length}개와 열람 기록이 함께 사라집니다. 전달한 주소는
              더 이상 열리지 않아요. 되돌릴 수 없습니다.
            </p>
            <button className="btn-danger" onClick={() => setConfirmDelete(true)}>이 링크 삭제</button>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={pending != null}
        title="발급한 링크를 지울까요?"
        message={`${pending?.email ?? '이 수신자'}의 링크가 사라집니다. 전달한 주소는 더 이상 열리지 않습니다. (그 사람의 패스키 자체는 다른 링크·입장권에서 계속 쓰입니다)`}
        details={pending ? [
          ['주소', pending.url.replace(/^https?:\/\/[^/]+/, '')],
          ['등록', pending.claimed ? '완료' : '미등록'],
          ['열람', `${pending.viewCount}회`],
        ] : undefined}
        confirmLabel="영구 삭제"
        busy={busy}
        onConfirm={() => pending && removeRecipient(pending)}
        onCancel={() => setPending(null)}
      />

      <ConfirmDialog
        open={confirmDelete && detail != null}
        title="링크를 삭제할까요?"
        message={`"${detail?.title || detail?.originalUrl}" 과 여기서 발급한 수신자 링크가 모두 사라집니다. 되돌릴 수 없습니다.`}
        details={detail ? [
          ['발급한 링크', detail.recipients.length],
          ['등록 완료', claimed],
          ['열람', detail.viewCount],
        ] : undefined}
        requireText={detail?.title || undefined}
        confirmLabel="영구 삭제"
        busy={busy}
        onConfirm={() => void removeLink()}
        onCancel={() => setConfirmDelete(false)}
      />
    </section>
  );
}
