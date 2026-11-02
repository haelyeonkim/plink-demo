import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { mutate } from '@shared/auth';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { clock, read, type BoothRow, type GateRow } from '../console/model';
import type { BoothManagerRow, ManagerInvited } from '../booth/api';
import RowMenu, { type MenuItem } from './RowMenu';

/*
 * The people who run a booth from their own phones, managed from the terminal each one
 * answers for: invited from its card, listed on it, moved to another or taken back there.
 * What belongs to no terminal - somebody left without one, or revoked - is listed once,
 * under the booth's terminals.
 *
 * A link is only shown right after it was made: the server keeps a hash of it, so a lost
 * link is replaced with a new one rather than shown again.
 */

const send = (method: string) => (url: string, body: unknown) => mutate(url, {
  method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
}).then(read);
const post = send('POST');
const put = send('PUT');

const terminalName = (gate: GateRow) => gate.label || gate.gateId;

/** How a manager stands, in a few words. */
export const managerState = (row: BoothManagerRow) =>
  row.status === 'REVOKED' ? '해지됨'
    : row.status === 'INVITED' ? '초대됨'
      : row.lastSeenAt ? `최근 접속 ${clock(row.lastSeenAt)}` : '접속 기록 없음';

/** A manager the card knows of before the booth's own list has been read. */
const fromCard = (gate: GateRow, manager: NonNullable<GateRow['managers']>[number]): BoothManagerRow => ({
  managerId: manager.managerId, email: manager.email, status: manager.status,
  invitedAt: null, claimedAt: null, lastSeenAt: null, gateId: gate.gateId, gateLabel: gate.label,
});

/** Closes a dialog on Escape, unless a menu inside it is what the key was meant for. */
function useEscape(onClose: () => void, active = true) {
  useEffect(() => {
    if (!active) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (event.target instanceof Element && event.target.closest('.row-menu')) return;
      onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, active]);
}

interface Shown { boothId: number; managerId: number; email: string; link: string; mailed: boolean }
type Confirm = { row: BoothManagerRow; boothId: number; action: 'reissue' | 'revoke' };

/** Why a terminal takes no more managers, on its greyed-out invite button. */
const STAFFED = '담당자가 이 단말을 쓰고 있어요. 단말 연결을 해제하면 다시 초대할 수 있어요.';

export interface ManagerDesk {
  /** A terminal's managers who can still open it. */
  forTerminal: (gate: GateRow) => BoothManagerRow[];
  /** A device holds it and somebody answers for it: nobody else is invited until it is released. */
  staffed: (gate: GateRow) => boolean;
  /** A booth's managers, all of them; null until read. */
  forBooth: (boothId: number) => BoothManagerRow[] | null;
  menu: (row: BoothManagerRow, boothId: number) => Array<MenuItem | 'separator'>;
  openInvite: (gate: GateRow) => void;
  openSheet: (gate: GateRow) => void;
  /** Invites somebody to a terminal straight away: for a terminal just made with an address. */
  invite: (boothId: number, gateId: string, email: string, notify: boolean) => Promise<ManagerInvited>;
  shown: Shown | null;
  /** Shows a link just made under its booth, once. */
  reveal: (shown: Shown) => void;
  closeShown: () => void;
  copy: (link: string) => Promise<void>;
  busy: boolean;
  /** Every dialog the desk opens, rendered once by the console. */
  dialogs: ReactNode;
}

/**
 * Reads each booth's managers once, keeps them by booth, and holds the actions and the
 * dialogs that change them. Terminal cards and the booth's own leftovers draw from it.
 */
export function useBoothManagers({ booths, terminals, enabled, busy, act, onNotice, onError, onChanged }: {
  booths: BoothRow[];
  /** Every booth terminal of the event. */
  terminals: GateRow[];
  /** Only the booth screen needs them, so nothing is read elsewhere. */
  enabled: boolean;
  busy: boolean;
  act: (action: () => Promise<void>) => Promise<boolean>;
  onNotice: (text: string) => void;
  onError: (text: string) => void;
  /** The booth list and the cards count managers, so they are read again after a change. */
  onChanged: () => Promise<void>;
}): ManagerDesk {
  const [rows, setRows] = useState<Map<number, BoothManagerRow[]>>(new Map());
  const [shown, setShown] = useState<Shown | null>(null);
  const [inviting, setInviting] = useState<GateRow | null>(null);
  const [moving, setMoving] = useState<{ row: BoothManagerRow; boothId: number } | null>(null);
  const [sheet, setSheet] = useState<GateRow | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  const load = useCallback(async (boothId: number) => {
    try {
      const list: BoothManagerRow[] = await read(await fetch(`/api/admin/booths/${boothId}/managers`));
      setRows(current => new Map(current).set(boothId, list));
    } catch (err) {
      onError(err instanceof Error ? err.message : '부스 관리자를 불러오지 못했어요.');
    }
  }, [onError]);

  // A terminal added, renamed or deleted changes what the rows say, so each booth is read
  // again then - and only then, not on every reload of the console.
  const key = enabled ? booths.map(booth => `${booth.boothId}:${terminals
    .filter(gate => gate.boothId === booth.boothId).map(gate => `${gate.gateId}=${gate.label ?? ''}`).join(',')}`).join(';') : '';
  useEffect(() => {
    if (!key) return;
    key.split(';').forEach(part => void load(Number(part.split(':')[0])));
  }, [key, load]);

  const refresh = (boothId: number) => Promise.all([load(boothId), onChanged()]).then(() => undefined);

  const forBooth = (boothId: number) => rows.get(boothId) ?? null;
  const forTerminal = (gate: GateRow) => {
    const list = gate.boothId == null ? null : rows.get(gate.boothId);
    if (!list) return (gate.managers ?? []).map(manager => fromCard(gate, manager));
    return list.filter(row => row.gateId === gate.gateId && row.status !== 'REVOKED');
  };
  const staffed = (gate: GateRow) => Boolean(gate.boundDevice) && forTerminal(gate).length > 0;
  const boothTerminals = (boothId: number) => terminals.filter(gate => gate.boothId === boothId);
  const boothName = (boothId: number) => booths.find(booth => booth.boothId === boothId)?.name ?? '';

  const menu = (row: BoothManagerRow, boothId: number): Array<MenuItem | 'separator'> => {
    const others = boothTerminals(boothId).filter(gate => gate.gateId !== row.gateId);
    return [
      { label: '링크 다시 보내기', onSelect: () => setConfirm({ row, boothId, action: 'reissue' }) },
      {
        label: row.gateId ? '다른 단말로 옮기기' : '단말 지정',
        onSelect: () => setMoving({ row, boothId }),
        // With a terminal there is always somewhere to go: none at all.
        disabled: !row.gateId && others.length === 0,
      },
      'separator',
      { label: '해지', onSelect: () => setConfirm({ row, boothId, action: 'revoke' }), danger: true },
    ];
  };

  const invite = async (boothId: number, gateId: string, email: string, notify: boolean) => {
    const result = await post(`/api/admin/booths/${boothId}/managers`, { email, notify, gateId }) as ManagerInvited;
    await refresh(boothId);
    return result;
  };

  async function copy(link: string) {
    try {
      await navigator.clipboard.writeText(link);
      onNotice('링크를 복사했어요.');
    } catch {
      onError('링크를 복사하지 못했어요. 링크를 직접 선택해 복사해 주세요.');
    }
  }

  async function run({ row, boothId, action }: Confirm) {
    const ok = await act(async () => {
      if (action === 'reissue') {
        const result = await post(`/api/admin/booth-managers/${row.managerId}/reissue`, { notify: true }) as { link: string };
        // The link is shown under the booth, so the sheet over it steps aside.
        setSheet(null);
        setShown({ boothId, managerId: row.managerId, email: row.email, link: result.link, mailed: true });
        onNotice(`${row.email} 로 새 링크를 보냈어요. 이전 링크는 이제 열리지 않아요.`);
      } else {
        await read(await mutate(`/api/admin/booth-managers/${row.managerId}`, { method: 'DELETE' }));
        setShown(current => (current?.managerId === row.managerId ? null : current));
        onNotice(`${row.email} 의 관리자 권한을 해지했어요.`);
      }
      await refresh(boothId);
    });
    if (ok) setConfirm(null);
  }

  const move = (row: BoothManagerRow, boothId: number, gateId: string) => act(async () => {
    await put(`/api/admin/booth-managers/${row.managerId}`, { gateId: gateId || null });
    const gate = boothTerminals(boothId).find(item => item.gateId === gateId);
    onNotice(gate ? `${row.email} 님을 '${terminalName(gate)}' 담당으로 옮겼어요.`
      : `${row.email} 님의 담당 단말을 비웠어요.`);
    await refresh(boothId);
  });

  const closeConfirm = useCallback(() => setConfirm(null), []);
  const sheetGate = sheet && (terminals.find(gate => gate.gateId === sheet.gateId) ?? null);

  const dialogs = (
    <>
      {sheetGate && (
        <TerminalManagersSheet gate={sheetGate} rows={forTerminal(sheetGate)} busy={busy} staffed={staffed(sheetGate)}
          // A dialog opened over it takes Escape for itself.
          covered={confirm !== null || moving !== null || inviting !== null}
          menu={row => menu(row, sheetGate.boothId!)}
          onInvite={() => setInviting(sheetGate)}
          onClose={() => setSheet(null)} />
      )}
      {inviting && (
        <InviteManagerDialog gate={inviting} busy={busy} copy={copy}
          onInvite={async (email, notify) => {
            // The dialog covers the page's own message, so it says what went wrong itself.
            let outcome: ManagerInvited | string = '초대하지 못했어요.';
            await act(async () => {
              try {
                outcome = await invite(inviting.boothId!, inviting.gateId, email, notify);
              } catch (err) {
                if (err instanceof Error) outcome = err.message;
                throw err;
              }
              onNotice(notify ? `${email} 로 초대 메일을 보냈어요.` : `${email} 의 초대 링크를 만들었어요.`);
            });
            return outcome;
          }}
          onClose={() => setInviting(null)} />
      )}
      {moving && (
        <MoveManagerDialog row={moving.row} busy={busy} isStaffed={staffed}
          terminals={boothTerminals(moving.boothId).filter(gate => gate.gateId !== moving.row.gateId)}
          onMove={gateId => void move(moving.row, moving.boothId, gateId).then(ok => { if (ok) setMoving(null); })}
          onClose={() => setMoving(null)} />
      )}
      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.action === 'revoke'
          ? `${confirm.row.email} 의 관리자 권한을 해지할까요?`
          : `${confirm?.row.email} 에게 새 링크를 보낼까요?`}
        message={confirm?.action === 'revoke'
          ? `이 순간부터 '${boothName(confirm.boothId)}' 부스를 관리할 수 없어요. 열려 있던 화면도 바로 닫혀요. 이미 준 쿠폰은 그대로예요.`
          : '새 링크를 메일로 보내요. 지금까지 쓰던 링크는 더 이상 열리지 않아요. 패스키는 그대로라 새 링크에서 바로 로그인할 수 있어요.'}
        confirmLabel={confirm?.action === 'revoke' ? '해지' : '새 링크 보내기'}
        busy={busy}
        onCancel={closeConfirm}
        onConfirm={() => { if (confirm) void run(confirm); }}
      />
    </>
  );

  return {
    forTerminal, staffed, forBooth, menu, invite, shown, copy, busy, dialogs,
    openInvite: setInviting,
    openSheet: setSheet,
    reveal: setShown,
    closeShown: () => setShown(null),
  };
}

/** One manager in a list: the address, how they stand, and the rest behind "⋯". */
function ManagerItem({ row, menu }: { row: BoothManagerRow; menu?: Array<MenuItem | 'separator'> }) {
  return (
    <li className="tm-item">
      <span className="tm-who">
        <span className="tm-email" title={row.email}>{row.email}</span>
        <small>{managerState(row)}{row.status === 'REVOKED' && row.gateLabel ? ` · ${row.gateLabel}` : ''}</small>
      </span>
      {menu && <RowMenu label={`${row.email} 관리`} items={menu} />}
    </li>
  );
}

/**
 * The 담당 row of a terminal card. On a computer the whole list is there; on a phone, where
 * two cards share a row, it is a count that opens the list, and a small invite button.
 */
export function TerminalManagers({ gate, desk }: { gate: GateRow; desk: ManagerDesk }) {
  const list = desk.forTerminal(gate);
  const name = terminalName(gate);
  return (
    <div className="gate-fact-wide terminal-managers">
      <dt>담당</dt>
      <dd>
        {list.length === 0 ? <span className="tm-none">없음</span> : (
          <ul className="tm-list">
            {list.map(row => <ManagerItem key={row.managerId} row={row} menu={desk.menu(row, gate.boothId!)} />)}
          </ul>
        )}
        <span className="tm-compact">
          {list.length === 0 ? <span className="tm-none">담당 없음</span> : (
            <button type="button" className="tm-count" aria-label={`${name} 담당 ${list.length}명 보기`}
              onClick={() => desk.openSheet(gate)}>담당 {list.length}</button>
          )}
          <button type="button" className="btn-tiny" disabled={desk.busy || desk.staffed(gate)}
            title={desk.staffed(gate) ? STAFFED : undefined}
            aria-label={`${name} 관리자 초대`} onClick={() => desk.openInvite(gate)}>초대</button>
        </span>
      </dd>
    </div>
  );
}

/** The card's own invite button, among its actions; a phone has the small one above instead. */
export function InviteManagerButton({ gate, desk }: { gate: GateRow; desk: ManagerDesk }) {
  return (
    <button className="btn-secondary tm-invite" type="button" disabled={desk.busy || desk.staffed(gate)}
      title={desk.staffed(gate) ? STAFFED : undefined}
      aria-label={`${terminalName(gate)} 관리자 초대`} onClick={() => desk.openInvite(gate)}>관리자 초대</button>
  );
}

/**
 * Under a booth's terminals: a link just made again, managers with no terminal, and - out
 * of the way - those who were revoked.
 */
export function BoothManagerExtras({ booth, desk }: { booth: BoothRow; desk: ManagerDesk }) {
  const rows = desk.forBooth(booth.boothId) ?? [];
  const loose = rows.filter(row => row.status !== 'REVOKED' && !row.gateId);
  const revoked = rows.filter(row => row.status === 'REVOKED');
  const shown = desk.shown?.boothId === booth.boothId ? desk.shown : null;
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => { if (shown) panel.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }, [shown]);

  if (!shown && loose.length === 0 && revoked.length === 0) return null;
  return (
    <div className="booth-managers">
      {shown && (
        <div ref={panel} className="booth-manager-link">
          <LinkShown email={shown.email} link={shown.link} mailed={shown.mailed} />
          <span className="booth-manager-actions">
            <button type="button" className="btn-tiny" onClick={() => void desk.copy(shown.link)}>복사</button>
            <button type="button" className="btn-tiny" onClick={desk.closeShown}>닫기</button>
          </span>
        </div>
      )}
      {loose.length > 0 && (
        <div>
          <h4>단말 미지정 관리자</h4>
          <ul className="tm-list tm-loose">
            {loose.map(row => <ManagerItem key={row.managerId} row={row} menu={desk.menu(row, booth.boothId)} />)}
          </ul>
        </div>
      )}
      {revoked.length > 0 && (
        <details className="tm-revoked">
          <summary>해지된 관리자 {revoked.length}명 보기</summary>
          <ul className="tm-list">
            {revoked.map(row => <ManagerItem key={row.managerId} row={row} />)}
          </ul>
        </details>
      )}
    </div>
  );
}

function LinkShown({ email, link, mailed }: { email: string; link: string; mailed: boolean }) {
  return (
    <>
      <p className="hint-text">
        {email} 의 링크예요. {mailed ? '메일로도 보냈어요. ' : ''}
        다시 볼 수 없으니 필요하면 지금 복사해 두세요.
      </p>
      <code title={link}>{link}</code>
    </>
  );
}

/** Asks somebody to run this terminal; the link they get is shown here, once. */
function InviteManagerDialog({ gate, busy, copy, onInvite, onClose }: {
  gate: GateRow; busy: boolean;
  copy: (link: string) => Promise<void>;
  /** The invitation made, or why it was not. */
  onInvite: (email: string, notify: boolean) => Promise<ManagerInvited | string>;
  onClose: () => void;
}) {
  const [email, setEmail] = useState('');
  const [notify, setNotify] = useState(true);
  const [done, setDone] = useState<{ email: string; link: string; mailed: boolean } | null>(null);
  const [problem, setProblem] = useState('');
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => { input.current?.focus(); }, []);
  const close = useCallback(() => { if (!busy) onClose(); }, [busy, onClose]);
  useEscape(close);

  const address = email.trim();
  return (
    <div className="modal-backdrop" role="presentation" onClick={close}>
      <form className="modal tm-dialog" role="dialog" aria-modal="true" aria-labelledby="tm-invite-title"
        onClick={event => event.stopPropagation()}
        onSubmit={event => {
          event.preventDefault();
          if (!address || done) return;
          setProblem('');
          void onInvite(address, notify).then(result => {
            if (typeof result === 'string') setProblem(result);
            else setDone({ email: result.email || address, link: result.link, mailed: notify });
          });
        }}>
        <h3 id="tm-invite-title">{terminalName(gate)} 관리자 초대</h3>
        {done ? (
          <div className="booth-manager-link" role="status">
            <LinkShown email={done.email} link={done.link} mailed={done.mailed} />
          </div>
        ) : (<>
          <p className="modal-message">초대받은 사람은 자기 휴대폰으로 이 단말 이름으로 쿠폰을 주고 사용 처리해요.</p>
          <div className="field">
            <label htmlFor="tm-invite-email">이메일</label>
            <input id="tm-invite-email" ref={input} type="email" required value={email}
              onChange={event => setEmail(event.target.value)} placeholder="staff@example.com" />
          </div>
          <label className="tm-notify">
            <input type="checkbox" checked={notify} onChange={event => setNotify(event.target.checked)} /> 메일로 보내기
          </label>
          {problem && <p className="tm-problem" role="alert">{problem}</p>}
        </>)}
        <div className="modal-actions">
          {done ? (<>
            <button type="button" className="btn-secondary" onClick={() => void copy(done.link)}>복사</button>
            <button type="button" className="btn-primary" onClick={onClose}>닫기</button>
          </>) : (<>
            <button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>취소</button>
            <button type="submit" className="btn-primary" disabled={busy || !address}>{busy ? '초대 중…' : '초대'}</button>
          </>)}
        </div>
      </form>
    </div>
  );
}

const NONE = '';

/** Hands a manager to another of the booth's terminals, or to none. */
function MoveManagerDialog({ row, terminals, isStaffed, busy, onMove, onClose }: {
  row: BoothManagerRow; terminals: GateRow[]; isStaffed: (gate: GateRow) => boolean; busy: boolean;
  onMove: (gateId: string) => void; onClose: () => void;
}) {
  const [target, setTarget] = useState(terminals.find(gate => !isStaffed(gate))?.gateId ?? NONE);
  const select = useRef<HTMLSelectElement>(null);
  useEffect(() => { select.current?.focus(); }, []);
  const close = useCallback(() => { if (!busy) onClose(); }, [busy, onClose]);
  useEscape(close);
  const title = row.gateId ? '다른 단말로 옮기기' : '단말 지정';

  return (
    <div className="modal-backdrop" role="presentation" onClick={close}>
      <form className="modal tm-dialog" role="dialog" aria-modal="true" aria-labelledby="tm-move-title"
        onClick={event => event.stopPropagation()}
        onSubmit={event => { event.preventDefault(); onMove(target); }}>
        <h3 id="tm-move-title">{title}</h3>
        <p className="modal-message">
          <b>{row.email}</b>{' '}
          {row.gateId ? `지금은 '${row.gateLabel || row.gateId}' 담당이에요. ` : ''}이미 준 쿠폰의 기록은 그대로예요.
        </p>
        <div className="field">
          <label htmlFor="tm-move-select">담당 단말</label>
          <select id="tm-move-select" ref={select} value={target} onChange={event => setTarget(event.target.value)}>
            {terminals.map(gate => (
              <option key={gate.gateId} value={gate.gateId} disabled={isStaffed(gate)}>
                {terminalName(gate)} · {isStaffed(gate) ? '담당자 사용 중' : gate.boundDevice ? '연결됨' : '연결 안 됨'}</option>
            ))}
            {row.gateId && <option value={NONE}>없음 · 본인 이름으로 기록</option>}
          </select>
        </div>
        <div className="modal-actions">
          <button type="button" className="btn-secondary" disabled={busy} onClick={onClose}>취소</button>
          <button type="submit" className="btn-primary" disabled={busy || (!row.gateId && (!target || target === NONE))}>
            {busy ? '저장 중…' : row.gateId ? '옮기기' : '지정'}</button>
        </div>
      </form>
    </div>
  );
}

/** A terminal's managers on a phone, where the card only has room for how many there are. */
function TerminalManagersSheet({ gate, rows, busy, covered, staffed, menu, onInvite, onClose }: {
  gate: GateRow; rows: BoothManagerRow[]; busy: boolean; covered: boolean; staffed: boolean;
  menu: (row: BoothManagerRow) => Array<MenuItem | 'separator'>;
  onInvite: () => void; onClose: () => void;
}) {
  useEscape(onClose, !covered);
  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
      <div className="modal tm-dialog" role="dialog" aria-modal="true" aria-labelledby="tm-sheet-title"
        onClick={event => event.stopPropagation()}>
        <h3 id="tm-sheet-title">{terminalName(gate)} 담당</h3>
        {rows.length === 0 ? <p className="console-none">담당자가 없어요.</p> : (
          <ul className="tm-list">{rows.map(row => <ManagerItem key={row.managerId} row={row} menu={menu(row)} />)}</ul>
        )}
        <div className="modal-actions">
          <button type="button" className="btn-secondary" disabled={busy || staffed} title={staffed ? STAFFED : undefined}
            onClick={onInvite}>관리자 초대</button>
          <button type="button" className="btn-primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  );
}
