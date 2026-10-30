import Icon from '@shared/components/Icon';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { mutate } from '@shared/auth';
import BulkImport from '@shared/components/BulkImport';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import LinkTable, { shortDate } from './LinkTable';
import LinkNav, { type LinkTab } from './LinkNav';
import LinkOverview from './LinkOverview';
import {
  deadlineInstant, deleteLink, deleteRecipient, fetchContents, fetchLink, fetchLinks, issueRecipient,
  setRecipientRevoked,
} from '../api';
import type { ContentSummary, LinkDetail, LinkRecipient, ProtectedLink } from '../types';

type Tab = LinkTab;

/** Each screen's title, which is also its entry in the link's menu. */
const TAB_TITLES: Record<Tab, string> = {
  overview: '개요', links: '발급 현황', issue: '수신자 발급', views: '열람 기록', settings: '링크 설정',
};

/** What this link opens, in one line: an address elsewhere, or a document written here. */
function destinationText(link: { originalUrl: string | null; contentTitle: string | null }): string {
  return link.contentTitle ? `컨텐츠 · ${link.contentTitle}` : link.originalUrl ?? '';
}

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
  const [params] = useSearchParams();
  const { linkId } = useParams<{ linkId: string }>();
  const navigate = useNavigate();
  const [links, setLinks] = useState<ProtectedLink[]>([]);
  const [linksLoaded, setLinksLoaded] = useState(false);
  const [detail, setDetail] = useState<LinkDetail | null>(null);
  const [email, setEmail] = useState('');
  const [label, setLabel] = useState('');
  const [notify, setNotify] = useState(true);
  const [issued, setIssued] = useState<LinkRecipient | null>(null);
  const [copied, setCopied] = useState<number | null>(null);
  const [pending, setPending] = useState<LinkRecipient | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [contents, setContents] = useState<ContentSummary[]>([]);
  const [target, setTarget] = useState<'url' | 'content'>('url');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const requested = params.get('tab');
  const tab: Tab = requested !== null && requested in TAB_TITLES ? (requested as Tab) : 'overview';
  // The link is the address, not a query on it: a console someone opened can be sent to
  // a colleague and lands on the same link.
  const selected = Number(linkId) || null;

  const loadLinks = useCallback(async () => {
    try { setLinks(await fetchLinks()); }
    catch { setError('링크를 불러오지 못했어요.'); }
    finally { setLinksLoaded(true); }
  }, []);

  // Which link the screen is on, and which detail request is the newest. A slow answer
  // for the link just left, or overtaken by a later reload, must not land on this one.
  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const detailRequest = useRef(0);
  const loadDetail = useCallback(async (id: number) => {
    const request = ++detailRequest.current;
    const current = () => request === detailRequest.current && selectedRef.current === id;
    try {
      const data = await fetchLink(id);
      if (current()) setDetail(data);
    } catch { if (current()) setError('링크 정보를 불러오지 못했어요.'); }
  }, []);

  useEffect(() => { void loadLinks(); }, [loadLinks]);
  // Older addresses named the link in the query (?link=7); the link is the path now.
  useEffect(() => {
    const legacy = Number(params.get('link'));
    if (!linkId && legacy) navigate(`/links/${legacy}?tab=${params.get('tab') ?? 'overview'}`, { replace: true });
  }, [linkId, params, navigate]);
  useEffect(() => { fetchContents().then(setContents).catch(() => setContents([])); }, []);
  // The form opens on whichever destination the link already has.
  useEffect(() => { setTarget(detail?.contentId ? 'content' : 'url'); }, [detail?.id, detail?.contentId]);
  useEffect(() => {
    // Nothing of the previous link stays on screen while the next one loads.
    setDetail(null); setIssued(null);
    if (selected === null) return;
    void loadDetail(selected);
  }, [selected, loadDetail]);

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
      expiresAt: deadlineInstant(deadline),
      maxViews: Number(data.get('maxViews') || 0),
    };
    // Absent means "leave it"; empty means "remove it".
    if (clear) body.password = '';
    else if (password) body.password = password;
    // The destination moves with the same save: a URL, or a document written here.
    const target = String(data.get('target') || 'url');
    if (target === 'content') body.contentId = Number(data.get('contentId') || 0) || null;
    else body.originalUrl = String(data.get('originalUrl') || '').trim();
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
    await loadLinks();
    navigate('/links');
  });

  async function copy(recipient: LinkRecipient) {
    // Only claim it was copied once the browser says so: it refuses without a secure
    // context or permission, and a "copied" that was not leaves the operator pasting
    // whatever was on the clipboard before.
    try {
      await navigator.clipboard.writeText(recipient.url);
      setCopied(recipient.id);
      window.setTimeout(() => setCopied(current => current === recipient.id ? null : current), 1500);
    } catch {
      setCopied(null);
      setError('주소를 복사하지 못했어요. 주소를 직접 선택해 복사해 주세요.');
    }
  }

  const expired = detail?.expiresAt ? new Date(detail.expiresAt).getTime() < Date.now() : false;
  const claimed = detail?.recipients.filter(r => r.claimed).length ?? 0;

  return (
    <section className="page-section page-wide console">
      {!detail && (<>
        <div className="page-head">
          <h2><span aria-current="page">링크</span><span className="count">{links.length}</span></h2>
          <span className="page-actions">
            {/* Content is what a link points at, so it is made from here rather than
                from a menu of its own. */}
            <Link className="btn-secondary" to="/links/contents">컨텐츠</Link>
            <Link className="btn-primary" to="/links/new">링크 추가</Link>
          </span>
        </div>
        {notice && <p className="notice-text" role="status">{notice}</p>}
        {error && <p className="error-text" role="alert">{error}</p>}
        {selected === null && <LinkTable links={links} loading={!linksLoaded} />}
        {selected !== null && <p className="console-none">링크를 불러오고 있어요.</p>}
      </>)}

      {detail && (
      <div className="console-layout">
        <LinkNav detail={detail} tab={tab} />
        <div className="console-main">
          <div className="console-head">
            <div>
              <h2>{TAB_TITLES[tab]}</h2>
              <p>{detail.title || detail.contentTitle || detail.originalUrl}
                {detail.expiresAt ? ` · ${shortDate(detail.expiresAt)}까지` : ''}{detail.hasPassword ? ' · 비밀번호 보호' : ''}</p>
            </div>
            <div className="console-actions">
              {(tab === 'overview' || tab === 'links') && <Link className="btn-primary" to="?tab=issue">수신자 발급</Link>}
            </div>
          </div>

          {notice && <p className="notice-text" role="status">{notice}</p>}
          {error && <p className="error-text" role="alert">{error}</p>}

          {tab === 'overview' && <LinkOverview detail={detail} />}

          {tab === 'issue' && (
            <div className="console-split">
              <section className="console-card issue-bulk" aria-label="명단으로 여러 명 발급">
              <BulkImport
                fields={[
                  { key: 'email', label: '이메일', match: ['email', '이메일', '메일'], required: true },
                  { key: 'label', label: '메모', match: ['label', '메모', '이름', 'name', '비고'] },
                ]}
                sampleName="수신자-발급-양식.csv"
                notifyLabel="발급하면서 수신자에게 메일로 보내기"
                onSubmit={issueBulk} />
              </section>
              <aside className="console-aside">
            <form className="console-card" onSubmit={e => { e.preventDefault(); void issue(); }}>
              <h3>한 명 발급</h3>
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
              <button className="btn-secondary" type="submit" disabled={busy || !email.trim()}>
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
                <div className="console-card">
                  <h3>이 링크</h3>
                  <dl className="detail-list">
                    <dt>대상</dt>
                    <dd>
                      {detail.contentId
                        ? <>여기서 만든 컨텐츠 · <Link to={`/links/contents/${detail.contentId}/edit`}>{detail.contentTitle}</Link></>
                        : <a href={detail.originalUrl ?? '#'} target="_blank" rel="noopener">{detail.originalUrl}</a>}
                    </dd>
                    <dt>발급</dt>
                    <dd>{detail.recipients.length}명 · 등록 완료 {claimed}명</dd>
                    <dt>만료</dt>
                    <dd className={expired ? 'expired' : ''}>
                      {detail.expiresAt ? formatDate(detail.expiresAt) : '없음'}{expired ? ' · 만료됨' : ''}
                    </dd>
                  </dl>
                  <p className="hint-text">
                    패스키는 사람(이메일)에 귀속되므로, 한 사람이 여러 링크를 받아도 패스키는 하나입니다.
                  </p>
                </div>
              </aside>
            </div>
          )}

          {tab === 'links' && (
            <div className="console-stack">
              <div className="console-table-box">
                <table className="console-table recipient-table">
                  <thead>
                    <tr><th>수신자</th><th>주소</th><th>등록</th><th className="num">열람</th><th>상태</th><th aria-label="작업" /></tr>
                  </thead>
                  <tbody>
                    {detail.recipients.map(recipient => (
                      <tr key={recipient.id} className={recipient.revoked ? 'is-off' : ''}>
                        <td className="strong">{recipient.email}
                          {recipient.label && <small className="cell-note">{recipient.label}</small>}</td>
                        <td className="muted"><code title={recipient.url}>{recipient.url.replace(/^https?:\/\/[^/]+/, '')}</code></td>
                        <td><span className={`pill pill-${recipient.claimed ? 'ok' : 'wait'}`}>{recipient.claimed ? '등록 완료' : '미등록'}</span>
                          {recipient.claimedAt && <small className="cell-note">{formatDate(recipient.claimedAt)}</small>}</td>
                        <td className="num">{recipient.viewCount}</td>
                        <td><span className={`pill pill-${recipient.revoked ? 'off' : 'in'}`}>{recipient.revoked ? '비활성' : '사용 중'}</span></td>
                        <td className="row-actions">
                          <button className="btn-tiny" onClick={() => copy(recipient)}>
                            {copied === recipient.id ? '복사됨' : '복사'}
                          </button>
                          <button className="btn-tiny" disabled={busy} onClick={() => void toggle(recipient)}>
                            {recipient.revoked ? '다시 사용' : '비활성화'}
                          </button>
                          <button className="btn-tiny btn-tiny-danger" disabled={busy}
                            onClick={() => setPending(recipient)}>삭제</button>
                        </td>
                      </tr>
                    ))}
                    {detail.recipients.length === 0 && (
                      <tr><td colSpan={6} className="console-none">
                        아직 발급한 수신자가 없어요. <Link to="?tab=issue">수신자 발급</Link>에서 시작하세요.
                      </td></tr>
                    )}
                  </tbody>
                </table>
              </div>
              <p className="hint-text console-help">
                비활성화하면 그 사람의 링크만 닫힙니다. 나머지 수신자는 그대로 열 수 있어요.
              </p>
            </div>
          )}

          {tab === 'views' && (
            <div className="console-stack">
              <div className="kpi-grid">
                <div className="kpi"><span className="kpi-label">열람</span>
                  <strong className="kpi-num">{detail.viewCount}{detail.maxViews > 0 && <small> / {detail.maxViews}</small>}</strong></div>
                <div className="kpi"><span className="kpi-label">발급</span><strong className="kpi-num">{detail.recipients.length}</strong></div>
                <div className="kpi"><span className="kpi-label">등록 완료</span><strong className="kpi-num">{claimed}</strong></div>
                <div className="kpi"><span className="kpi-label">마지막 열람</span>
                  <strong className="kpi-num kpi-text">{detail.views.length > 0 ? timeAgo(detail.views[0].viewedAt) : '–'}</strong></div>
              </div>
              <section className="console-card" aria-labelledby="views-log">
                <h3 id="views-log">기록</h3>
                {detail.views.length === 0 ? (
                  <p className="console-none">아직 열람 기록이 없어요.</p>
                ) : (
                  <div className="console-table-box flat">
                    <table className="console-table">
                      <thead>
                        <tr><th>수신자</th><th>무슨 일</th><th>시각</th><th>경과</th></tr>
                      </thead>
                      <tbody>
                        {detail.views.map((view, index) => {
                          // An open says the message arrived; a passkey says it was read.
                          const opened = view.eventType === 'INITIAL_OPEN';
                          return (
                            <tr key={index}>
                              <td data-label="수신자">{view.viewerName || (opened ? '-' : '수신자')}</td>
                              <td data-label="무슨 일">
                                <span className={`pill pill-${opened ? 'wait' : 'ok'}`}>
                                  {opened ? '링크 열림' : '패스키 인증'}
                                </span>
                              </td>
                              <td data-label="시각">{formatDate(view.viewedAt)}</td>
                              <td data-label="경과">{timeAgo(view.viewedAt)}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </div>
          )}

          {tab === 'settings' && (
            <div className="settings-page">
              <section id="settings-link" className="settings-section" aria-labelledby="settings-link-title">
                <div className="settings-side">
                  <h3 id="settings-link-title">링크 정보</h3>
                  <p>무엇을 열지, 언제까지·몇 번까지 열 수 있는지 정합니다. 이미 발급한 수신자 링크에도 바로 적용돼요.</p>
                  <dl className="settings-now">
                    <div><dt>대상</dt><dd>{detail.contentId ? `컨텐츠 · ${detail.contentTitle}` : '외부 주소'}</dd></div>
                    <div><dt>만료</dt><dd>{detail.expiresAt ? formatDate(detail.expiresAt) : '없음'}</dd></div>
                    <div><dt>최대 열람</dt><dd>{detail.maxViews > 0 ? `${detail.maxViews}회` : '제한 없음'}</dd></div>
                    <div><dt>비밀번호</dt><dd>{detail.hasPassword ? '있음' : '없음'}</dd></div>
                  </dl>
                </div>
                <div className="settings-body">
                <form key={detail.id} className="settings-form" onSubmit={e => { e.preventDefault(); saveSettings(e.currentTarget); }}>
                  <div className="field">
                    <label htmlFor="settings-title">제목</label>
                    <input id="settings-title" name="title" defaultValue={detail.title ?? ''} />
                  </div>
                  <div className="field">
                    <label htmlFor="settings-target">무엇을 열까요</label>
                    <select id="settings-target" name="target" value={target}
                      onChange={event => setTarget(event.target.value as 'url' | 'content')}>
                      <option value="url">외부 주소</option>
                      <option value="content">여기서 만든 컨텐츠</option>
                    </select>
                  </div>
                  {target === 'url' ? (
                    <div className="field">
                      <label htmlFor="settings-url">원본 주소</label>
                      <input id="settings-url" name="originalUrl" type="url" required
                        defaultValue={detail.originalUrl ?? ''} placeholder="https://example.com/doc" />
                    </div>
                  ) : (
                    <div className="field">
                      <label htmlFor="settings-content">컨텐츠</label>
                      <select id="settings-content" name="contentId" required
                        defaultValue={detail.contentId ? String(detail.contentId) : ''}>
                        <option value="">컨텐츠를 선택하세요</option>
                        {contents.map(content => (
                          <option key={content.id} value={content.id}>{content.title}</option>
                        ))}
                      </select>
                      <p className="hint-text">
                        <Link to="/links/contents">컨텐츠 만들기·편집</Link>
                      </p>
                    </div>
                  )}
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
                  <button className="btn-primary" type="submit" disabled={busy}>저장하고 바로 적용</button>
                </form>
                </div>
              </section>
              <section id="settings-delete" className="settings-section danger" aria-labelledby="settings-delete-title">
                <div className="settings-side">
                  <h3 id="settings-delete-title">링크 삭제</h3>
                  <p>발급한 수신자 링크 {detail.recipients.length}개와 열람 기록이 함께 사라지고 되돌릴 수 없어요.</p>
                </div>
                <div className="settings-body">
                  <button className="btn-danger" type="button" onClick={() => setConfirmDelete(true)}>이 링크 삭제…</button>
                </div>
              </section>
            </div>
          )}
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
