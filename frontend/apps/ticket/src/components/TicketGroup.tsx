import { useState } from 'react';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { cancelInvite, sendInvite, type GroupMember, type InviteRow, type InviteView } from '../ticket/api';

function hm(value: string): string {
  const d = new Date(value);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

const STATE: Record<GroupMember['state'], { label: string; tone: string }> = {
  NOT_ENTERED: { label: '미입장', tone: 'wait' },
  INSIDE: { label: '장내', tone: 'in' },
  OUTSIDE: { label: '장외', tone: 'out' },
};

/** Whether the ticket page has anything to show about invites for this holder. */
export function groupRelevant(view: InviteView | null): boolean {
  return view !== null && (view.quota > 0 || view.invitedBy !== null || view.group !== null || view.invites.length > 0);
}

/** What the tile on the ticket says about the group, in one line. */
export function groupSummary(view: InviteView): string {
  const inside = view.group?.filter(member => !member.me && member.state === 'INSIDE').length ?? 0;
  const parts: string[] = [];
  if (view.quota > 0) parts.push(`초대 ${view.remaining}/${view.quota}명 남음`);
  else if (view.invitedBy) parts.push(`${view.invitedBy}님이 초대했어요`);
  if (view.group) parts.push(inside > 0 ? `일행 ${inside}명 장내` : '일행 아직 장내에 없어요');
  return parts.join(' · ');
}

/**
 * The holder's group: who they invited and whether those have registered, and - when the
 * event shows it - where everyone in the group is now. Addresses are masked throughout.
 */
export default function TicketGroup({ sessionId, token, view, onChange }: {
  sessionId: string; token: string; view: InviteView;
  /** Read the invites again after something here changed them. */
  onChange: () => Promise<void>;
}) {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [cancelling, setCancelling] = useState<InviteRow | null>(null);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
  }

  const invite = () => run(async () => {
    const row = await sendInvite(sessionId, token, email.trim());
    setEmail('');
    setNotice(`${row.email} 님에게 초대 입장권을 보냈어요.`);
    await onChange();
  });

  const cancel = (row: InviteRow) => run(async () => {
    await cancelInvite(sessionId, token, row.ticketId);
    setCancelling(null);
    setNotice(`${row.email} 님 초대를 취소했어요.`);
    await onChange();
  });

  const hidden = view.group === null && (view.status === 'OFF'
    ? '이 이벤트는 일행의 입장 현황을 보여 주지 않아요.'
    : view.status === 'INVITER' && view.invitedBy !== null ? '입장 현황은 초대한 분만 볼 수 있어요.' : null);

  return (
    <div className="group-view">
      <header>
        <h2>일행</h2>
        {view.invitedBy && <p>{view.invitedBy}님이 초대했어요.</p>}
      </header>

      {view.quota > 0 && (
        <section className="group-card" aria-labelledby="invite-title">
          <h3 id="invite-title">초대 <b>{view.remaining}/{view.quota}명 남음</b></h3>
          {view.canInvite ? (
            <form onSubmit={e => { e.preventDefault(); void invite(); }}>
              <div className="field">
                <label htmlFor="invite-email">초대할 분의 이메일</label>
                <input id="invite-email" type="email" required autoComplete="off" value={email}
                  onChange={e => setEmail(e.target.value)} placeholder="friend@example.com" />
              </div>
              <button className="btn-primary" type="submit" disabled={busy || !email.trim()}>초대 입장권 보내기</button>
              <p className="hint-text">받는 분은 메일로 자기 입장권을 받아 직접 등록해요.</p>
            </form>
          ) : (
            <p className="hint-text">초대할 수 있는 인원을 모두 채웠어요. 등록 전인 초대는 취소할 수 있어요.</p>
          )}
          {notice && <p className="notice-text" role="status">{notice}</p>}
          {error && <p className="error-text" role="alert">{error}</p>}
          {view.invites.length > 0 && (
            <ul className="invite-list">
              {view.invites.map(row => (
                <li key={row.ticketId}>
                  <span className="invite-email">{row.email}</span>
                  <span className={`pill pill-${row.revoked ? 'off' : row.registered ? 'ok' : 'wait'}`}>
                    {row.revoked ? '비활성' : row.registered ? '등록됨' : '등록 대기'}</span>
                  {!row.registered && !row.revoked && (
                    <button type="button" className="btn-tiny" disabled={busy} onClick={() => setCancelling(row)}>취소</button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      {view.group && (
        <section className="group-card" aria-labelledby="group-title">
          <h3 id="group-title">입장 현황</h3>
          <ul className="group-list">
            {view.group.map((member, index) => (
              <li key={index} className={member.me ? 'is-me' : ''}>
                <span>
                  <b>{member.email}</b>
                  <small>{member.me ? '나' : member.role === 'INVITER' ? '초대한 분' : '초대받은 분'}</small>
                </span>
                <span className={`pill pill-${STATE[member.state].tone}`}>
                  {STATE[member.state].label}{member.since ? ` · ${hm(member.since)}` : ''}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {hidden && <p className="console-none">{hidden}</p>}
      {view.quota === 0 && !view.invitedBy && view.group === null && view.invites.length === 0 && (
        <p className="console-none">이 입장권으로는 초대할 수 없어요.</p>
      )}

      <ConfirmDialog
        open={cancelling !== null}
        title={`${cancelling?.email} 님 초대를 취소할까요?`}
        message="보낸 입장권 링크가 바로 닫히고, 초대 한 자리가 다시 비어요."
        confirmLabel="초대 취소"
        busy={busy}
        onCancel={() => setCancelling(null)}
        onConfirm={() => { if (cancelling) void cancel(cancelling); }}
      />
    </div>
  );
}
