import { useCallback, useEffect, useState } from 'react';
import { mutate } from '../auth';
import ConfirmDialog from './ConfirmDialog';

interface AccountRow {
  id: number; email: string; displayName: string | null; slug: string;
  canLinks: boolean; canTickets: boolean; owner: boolean; locked: boolean;
  lastLoginAt: string | null; createdAt: string | null;
}

async function read(response: Response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || '요청을 처리하지 못했어요.');
  return data;
}

type Tab = 'accounts' | 'mail';

const TABS: Array<[Tab, string]> = [
  ['accounts', '계정'],
  ['mail', '메일 발송'],
];

/**
 * Administration, kept out of the two product consoles.
 *
 * <p>Who may open what is not an operator's daily task, so it does not sit in their
 * menu: it lives here behind SCOPE_ACCOUNTS, and only an owner is shown the door.
 *
 * <p>The permission checkboxes save on change - a screen that needs a separate save
 * step is one people leave half-applied. The server refuses the two changes that would
 * lock everyone out: the last owner, and your own account.
 */
export default function AdminConsole() {
  const [tab, setTab] = useState<Tab>('accounts');
  const [accounts, setAccounts] = useState<AccountRow[]>([]);
  const [me, setMe] = useState<string>('');
  const [pending, setPending] = useState<AccountRow | null>(null);
  const [slugEdit, setSlugEdit] = useState<{ id: number; value: string } | null>(null);
  const [mail, setMail] = useState<{ configured: boolean; from: string } | null>(null);
  const [testTo, setTestTo] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    try {
      setAccounts(await read(await fetch('/api/accounts')));
      setMail(await read(await fetch('/api/accounts/mail')));
      const session = await read(await fetch('/api/auth/session'));
      setMe(session.user?.email ?? '');
    } catch (err) {
      setError(err instanceof Error ? err.message : '계정을 불러오지 못했어요.');
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function act(action: () => Promise<void>) {
    setError(''); setNotice(''); setBusy(true);
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
  }

  const setPermission = (row: AccountRow, patch: Partial<AccountRow>) => act(async () => {
    const next = { canLinks: row.canLinks, canTickets: row.canTickets, owner: row.owner, ...patch };
    await read(await mutate(`/api/accounts/${row.id}/permissions`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(next),
    }));
    setNotice(`${row.email}의 권한을 저장했어요.`);
    await load();
  });

  const saveSlug = () => act(async () => {
    if (!slugEdit) return;
    await read(await mutate(`/api/accounts/${slugEdit.id}/slug`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug: slugEdit.value.trim() }),
    }));
    setSlugEdit(null);
    setNotice('링크 주소를 바꿨어요. 이미 전달한 주소도 새 주소로 이어집니다.');
    await load();
  });

  const sendTest = () => act(async () => {
    const result = await read(await mutate('/api/accounts/mail/test', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ to: testTo.trim() || me }),
    }));
    setNotice(`${result.to} 주소로 테스트 메일을 보냈어요. 받은 편지함(및 스팸함)을 확인해 주세요.`);
  });

  const createAccount = (form: HTMLFormElement) => act(async () => {
    const data = new FormData(form);
    await read(await mutate('/api/accounts', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: data.get('email'), password: data.get('password'),
        displayName: data.get('displayName'),
        canLinks: data.get('canLinks') === 'on',
        canTickets: data.get('canTickets') === 'on',
        owner: data.get('owner') === 'on',
      }),
    }));
    form.reset();
    setNotice('계정을 만들었어요. 비밀번호를 안전한 경로로 전달해 주세요.');
    await load();
  });

  const remove = (row: AccountRow) => act(async () => {
    await read(await mutate(`/api/accounts/${row.id}`, { method: 'DELETE' }));
    setPending(null);
    setNotice(`${row.email} 계정을 삭제했어요.`);
    await load();
  });

  return (
    <section className="page-section page-wide">
      <p className="eyebrow"><span></span> ADMIN</p>
      <h2>관리자</h2>

      {notice && <p className="notice-text" role="status">{notice}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="tabs" role="tablist">
        {TABS.map(([key, name]) => (
          <button key={key} role="tab" aria-selected={tab === key}
            className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>{name}</button>
        ))}
      </div>

      {tab === 'accounts' && (
        <div className="tab-panel">
          <h3>관리자 계정</h3>

        <p className="section-desc">
          계정마다 열 수 있는 화면을 정합니다. 본인 계정과 마지막 소유자의 권한은 잠금 상태로 바꿀 수
          없어요. 행의 <code>/s/…</code> 를 누르면 그 계정이 발급하는 링크 주소를 바꿀 수 있습니다.
        </p>

        <ul className="entity-list">
          {accounts.map(row => {
            const self = row.email === me;
            return (
              <li key={row.id}>
                <div className="entity-main">
                  <span className="entity-title">
                    {row.displayName || row.email}{self ? ' · 나' : ''}
                  </span>
                  <span className="entity-meta">
                    <span>{row.email}</span>
                    {slugEdit?.id === row.id ? (
                      <span className="slug-edit">
                        <span>/s/</span>
                        <input value={slugEdit.value} autoFocus disabled={busy}
                          onChange={event => setSlugEdit({ id: row.id, value: event.target.value })}
                          onKeyDown={event => {
                            if (event.key === 'Enter') { event.preventDefault(); void saveSlug(); }
                            if (event.key === 'Escape') setSlugEdit(null);
                          }} />
                        <button className="btn-tiny" disabled={busy} onClick={() => void saveSlug()}>저장</button>
                        <button className="btn-tiny" disabled={busy} onClick={() => setSlugEdit(null)}>취소</button>
                      </span>
                    ) : (
                      <button className="slug-chip" title="링크 주소 바꾸기"
                        onClick={() => setSlugEdit({ id: row.id, value: row.slug })}>
                        <code>/s/{row.slug}/…</code>
                      </button>
                    )}
                    {row.owner && <span className="claimed">소유자</span>}
                    {row.locked && <span className="expired">잠김</span>}
                    <span>
                      {row.lastLoginAt
                        ? `최근 로그인 ${new Date(row.lastLoginAt).toLocaleDateString('ko-KR')}`
                        : '로그인 기록 없음'}
                    </span>
                  </span>
                </div>
                <div className="entity-actions perm-toggles">
                  <label title={self ? '본인 권한은 바꿀 수 없어요' : '링크 관리 접근'}>
                    <input type="checkbox" checked={row.canLinks} disabled={busy || self}
                      onChange={e => setPermission(row, { canLinks: e.target.checked })} />
                    링크
                  </label>
                  <label title={self ? '본인 권한은 바꿀 수 없어요' : '입장권 관리 접근'}>
                    <input type="checkbox" checked={row.canTickets} disabled={busy || self}
                      onChange={e => setPermission(row, { canTickets: e.target.checked })} />
                    입장권
                  </label>
                  <label title={self ? '본인 권한은 바꿀 수 없어요' : '계정 관리 접근'}>
                    <input type="checkbox" checked={row.owner} disabled={busy || self}
                      onChange={e => setPermission(row, { owner: e.target.checked })} />
                    소유자
                  </label>
                  <button className="btn-delete" title="계정 삭제" disabled={busy || self}
                    onClick={() => setPending(row)}>&times;</button>
                </div>
              </li>
            );
          })}
        </ul>
        </div>
      )}

      {tab === 'accounts' && (
        <div className="tab-panel" style={{ marginTop: 16 }}>
          <h3>계정 추가</h3>

        <form className="create-form" onSubmit={e => { e.preventDefault(); createAccount(e.currentTarget); }}>
          <div className="form-row">
            <div className="field">
              <label htmlFor="account-email">이메일</label>
              <input id="account-email" name="email" type="email" required />
            </div>
            <div className="field">
              <label htmlFor="account-name">이름 (선택)</label>
              <input id="account-name" name="displayName" />
            </div>
          </div>
          <div className="field">
            <label htmlFor="account-password">임시 비밀번호 (10자 이상)</label>
            <input id="account-password" name="password" type="password" minLength={10} required />
          </div>
          <div className="perm-toggles">
            <label><input type="checkbox" name="canLinks" defaultChecked /> 링크 관리</label>
            <label><input type="checkbox" name="canTickets" defaultChecked /> 입장권 관리</label>
            <label><input type="checkbox" name="owner" /> 계정 관리(소유자)</label>
          </div>
          <button className="btn-primary" type="submit" disabled={busy}>계정 만들기</button>
        </form>
        </div>
      )}

      {tab === 'mail' && (
        <div className="tab-panel">
          <h3>메일 발송</h3>

        {mail && (
          <>
            <p className="section-desc">
              {mail.configured
                ? <>발송 준비됨 · 보내는 주소 <code>{mail.from}</code></>
                : '아직 설정되지 않았습니다. 지금은 안내 메일이 발송되지 않고 서버 로그에만 남습니다.'}
            </p>
            {!mail.configured && (
              <p className="hint-text">
                서버의 <code>.env</code> 에 <code>spring.mail.host</code>, <code>spring.mail.username</code>,
                <code>spring.mail.password</code>, <code>MAIL_FROM</code> 을 넣고 재시작하면 켜집니다.
              </p>
            )}
            <div className="catalog-add">
              <input type="email" value={testTo} placeholder={me || 'name@example.com'} disabled={busy}
                onChange={event => setTestTo(event.target.value)}
                onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); void sendTest(); } }} />
              <button type="button" className="btn-secondary" disabled={busy} onClick={() => void sendTest()}>
                테스트 메일 보내기
              </button>
            </div>
          </>
        )}
        </div>
      )}

      <ConfirmDialog
        open={pending != null}
        title="계정을 삭제할까요?"
        message={`${pending?.email} 계정이 사라집니다. 이 계정이 만든 링크와 입장권 기록은 남습니다.`}
        confirmLabel="영구 삭제"
        busy={busy}
        onConfirm={() => pending && remove(pending)}
        onCancel={() => setPending(null)}
      />
    </section>
  );
}
