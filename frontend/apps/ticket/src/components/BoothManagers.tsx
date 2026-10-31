import { useCallback, useEffect, useState } from 'react';
import { mutate } from '@shared/auth';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { read, shortTime, type GateRow } from '../console/model';
import type { BoothManagerRow, ManagerInvited } from '../booth/api';

const STATE: Record<BoothManagerRow['status'], [string, string]> = {
  INVITED: ['초대됨', 'wait'], ACTIVE: ['사용 중', 'ok'], REVOKED: ['해지', 'off'],
};

const send = (method: string) => (url: string, body: unknown) => mutate(url, {
  method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
}).then(read);
const post = send('POST');
const put = send('PUT');

/** The select's value for "make a new terminal", which no gate id can be. */
const NEW_TERMINAL = ' new';

const terminalName = (gate: GateRow) =>
  `${gate.label || gate.gateId} · ${gate.boundDevice ? '연결됨' : '연결 안 됨'}`;

/**
 * The people who run one booth from their own phones: who was asked, who has signed in,
 * and the way to ask somebody new or to take the booth back from them.
 *
 * <p>A link is only shown right after it was made: the server keeps a hash of it, so a
 * lost link is replaced with a new one rather than shown again.
 *
 * <p>Each person answers for one of the booth's terminals, chosen here or made here: what
 * they hand over is recorded under that terminal's name, and their own phone can become it.
 */
export default function BoothManagers({ boothId, boothName, terminals, newTerminalName, busy, act, onNotice, onError, onChanged }: {
  boothId: number;
  boothName: string;
  /** This booth's terminals, to choose from. */
  terminals: GateRow[];
  /** What a terminal made from the invite form is called unless the organiser says otherwise. */
  newTerminalName: string;
  busy: boolean;
  act: (action: () => Promise<void>) => Promise<boolean>;
  onNotice: (text: string) => void;
  onError: (text: string) => void;
  /** The booth list counts managers, so it is read again after a change. */
  onChanged: () => Promise<void>;
}) {
  const [rows, setRows] = useState<BoothManagerRow[] | null>(null);
  const [email, setEmail] = useState('');
  const [notify, setNotify] = useState(true);
  const [terminal, setTerminal] = useState('');
  const [newName, setNewName] = useState('');
  const [shown, setShown] = useState<{ managerId: number; email: string; link: string; mailed: boolean } | null>(null);
  const [confirm, setConfirm] = useState<{ row: BoothManagerRow; action: 'reissue' | 'revoke' } | null>(null);

  const load = useCallback(async () => {
    try { setRows(await read(await fetch(`/api/admin/booths/${boothId}/managers`))); }
    catch (err) { onError(err instanceof Error ? err.message : '부스 관리자를 불러오지 못했어요.'); }
  }, [boothId, onError]);
  // A terminal renamed or deleted from its card changes what the rows say, so they are read again.
  const terminalKey = terminals.map(gate => `${gate.gateId}:${gate.label ?? ''}`).join('|');
  useEffect(() => { void load(); }, [load, terminalKey]);
  // A chosen terminal that has gone (deleted from its card) is no choice at all.
  useEffect(() => {
    if (terminal && terminal !== NEW_TERMINAL && !terminals.some(gate => gate.gateId === terminal)) setTerminal('');
  }, [terminal, terminals]);

  function chooseTerminal(value: string) {
    setTerminal(value);
    if (value === NEW_TERMINAL && !newName.trim()) setNewName(newTerminalName);
  }

  const invite = () => act(async () => {
    const address = email.trim();
    const where = terminal === NEW_TERMINAL ? { newTerminal: { label: newName.trim() } } : { gateId: terminal };
    const result = await post(`/api/admin/booths/${boothId}/managers`, { email: address, notify, ...where }) as ManagerInvited;
    setShown({ managerId: result.managerId, email: result.email || address, link: result.link, mailed: notify });
    setEmail('');
    setTerminal('');
    setNewName('');
    onNotice(notify ? `${address} 로 초대 메일을 보냈어요.` : `${address} 의 초대 링크를 만들었어요. 아래에서 복사해 전해 주세요.`);
    await Promise.all([load(), onChanged()]);
  });

  const assign = (row: BoothManagerRow, gateId: string) => act(async () => {
    await put(`/api/admin/booth-managers/${row.managerId}`, { gateId: gateId || null });
    const gate = terminals.find(item => item.gateId === gateId);
    onNotice(gate ? `${row.email} 의 담당 단말을 '${gate.label || gate.gateId}'(으)로 바꿨어요.`
      : `${row.email} 의 담당 단말을 비웠어요.`);
    await Promise.all([load(), onChanged()]);
  });

  async function run(row: BoothManagerRow, action: 'reissue' | 'revoke') {
    const ok = await act(async () => {
      if (action === 'reissue') {
        const result = await post(`/api/admin/booth-managers/${row.managerId}/reissue`, { notify: true }) as { link: string };
        setShown({ managerId: row.managerId, email: row.email, link: result.link, mailed: true });
        onNotice(`${row.email} 로 새 링크를 보냈어요. 이전 링크는 이제 열리지 않아요.`);
      } else {
        await read(await mutate(`/api/admin/booth-managers/${row.managerId}`, { method: 'DELETE' }));
        if (shown?.managerId === row.managerId) setShown(null);
        onNotice(`${row.email} 의 관리자 권한을 해지했어요.`);
      }
      await Promise.all([load(), onChanged()]);
    });
    if (ok) setConfirm(null);
  }

  async function copy(link: string) {
    try {
      await navigator.clipboard.writeText(link);
      onNotice('링크를 복사했어요.');
    } catch {
      onError('링크를 복사하지 못했어요. 링크를 직접 선택해 복사해 주세요.');
    }
  }

  return (
    <div className="booth-managers">
      <h4>관리자</h4>
      {rows === null ? <p className="console-none">불러오는 중…</p>
        : rows.length === 0 ? (
          <p className="console-none">
            아직 관리자가 없어요. 부스 담당자를 초대하면 자기 휴대폰으로 쿠폰을 만들고, 주고, 사용 처리할 수 있어요.
          </p>
        ) : (
          <ul className="booth-manager-list">
            {rows.map(row => {
              const [label, tone] = STATE[row.status] ?? [row.status, 'wait'];
              return (
                <li key={row.managerId} className={row.status === 'REVOKED' ? 'is-off' : ''}>
                  <span className="booth-manager-who">
                    <b>{row.email}</b>
                    <span className={`pill pill-${tone}`}>{label}</span>
                  </span>
                  <label className="booth-manager-terminal">
                    <span>담당 단말</span>
                    {row.status === 'REVOKED' ? <b>{row.gateLabel || '없음'}</b> : (
                      <select value={row.gateId ?? ''} disabled={busy}
                        aria-label={`${row.email} 의 담당 단말`}
                        onChange={e => void assign(row, e.target.value)}>
                        <option value="">없음 · 본인 이름으로 기록</option>
                        {row.gateId && !terminals.some(gate => gate.gateId === row.gateId) && (
                          <option value={row.gateId}>{row.gateLabel || row.gateId}</option>
                        )}
                        {terminals.map(gate => <option key={gate.gateId} value={gate.gateId}>{terminalName(gate)}</option>)}
                      </select>
                    )}
                  </label>
                  <small className="muted">
                    {row.lastSeenAt ? `최근 접속 ${shortTime(row.lastSeenAt)}`
                      : row.status === 'INVITED' ? `초대 ${shortTime(row.invitedAt)} · 아직 열지 않음` : '접속 기록 없음'}
                  </small>
                  {row.status !== 'REVOKED' && (
                    <span className="booth-manager-actions">
                      <button type="button" className="btn-tiny" disabled={busy}
                        onClick={() => setConfirm({ row, action: 'reissue' })}>다시 보내기</button>
                      <button type="button" className="btn-tiny btn-tiny-danger" disabled={busy}
                        onClick={() => setConfirm({ row, action: 'revoke' })}>해지</button>
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        )}

      {shown && (
        <div className="booth-manager-link">
          <p className="hint-text">
            {shown.email} 의 링크예요. {shown.mailed ? '메일로도 보냈어요. ' : ''}
            이 화면을 벗어나면 다시 볼 수 없으니 필요하면 지금 복사해 두세요.
          </p>
          <code title={shown.link}>{shown.link}</code>
          <span className="booth-manager-actions">
            <button type="button" className="btn-tiny" onClick={() => void copy(shown.link)}>복사</button>
            <button type="button" className="btn-tiny" onClick={() => setShown(null)}>닫기</button>
          </span>
        </div>
      )}

      <form className="inline-form booth-manager-invite" onSubmit={e => { e.preventDefault(); void invite(); }}>
        <label className="grow">초대할 이메일
          <input type="email" required value={email} onChange={e => setEmail(e.target.value)}
            placeholder="staff@example.com" aria-label={`${boothName} 관리자로 초대할 이메일`} />
        </label>
        <label className="grow">담당 단말
          <select required value={terminal} onChange={e => chooseTerminal(e.target.value)}
            aria-label={`${boothName} 관리자가 맡을 단말`}>
            <option value="" disabled>단말을 골라 주세요</option>
            {terminals.map(gate => <option key={gate.gateId} value={gate.gateId}>{terminalName(gate)}</option>)}
            <option value={NEW_TERMINAL}>새 단말 만들기</option>
          </select>
        </label>
        {terminal === NEW_TERMINAL && (
          <label className="grow">새 단말 이름
            <input required maxLength={40} value={newName} onChange={e => setNewName(e.target.value)}
              placeholder={newTerminalName} />
          </label>
        )}
        <label className="booth-manager-notify">
          <input type="checkbox" checked={notify} onChange={e => setNotify(e.target.checked)} /> 메일로 보내기
        </label>
        <button className="btn-secondary" type="submit" disabled={busy || !email.trim() || !terminal || (terminal === NEW_TERMINAL && !newName.trim())}>초대</button>
      </form>

      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.action === 'revoke'
          ? `${confirm.row.email} 의 관리자 권한을 해지할까요?`
          : `${confirm?.row.email} 에게 새 링크를 보낼까요?`}
        message={confirm?.action === 'revoke'
          ? `이 순간부터 '${boothName}' 부스를 관리할 수 없어요. 열려 있던 화면도 바로 닫혀요. 이미 준 쿠폰은 그대로예요.`
          : '새 링크를 메일로 보내요. 지금까지 쓰던 링크는 더 이상 열리지 않아요. 패스키는 그대로라 새 링크에서 바로 로그인할 수 있어요.'}
        confirmLabel={confirm?.action === 'revoke' ? '해지' : '새 링크 보내기'}
        busy={busy}
        onCancel={() => setConfirm(null)}
        onConfirm={() => { if (confirm) void run(confirm.row, confirm.action); }}
      />
    </div>
  );
}
