import { useEffect, useRef, useState, type ReactNode } from 'react';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import Toast from '@shared/components/Toast';
import RowMenu, { type MenuItem } from './RowMenu';
import {
  cancelInvite, renameInvite, resendInvite, sendInvite, type GroupMember, type InviteView,
} from '../ticket/api';

/** The longest call name, as the server holds it. */
const NAME_MAX = 12;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function hm(value: string | null): string {
  if (!value) return '';
  const d = new Date(value);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** "11월 1일(토)" from the server's YYYY-MM-DD, read as the calendar date it names. */
function dayText(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  const weekday = '일월화수목금토'[new Date(y, m - 1, d).getDay()];
  return `${m}월 ${d}일(${weekday})`;
}

function sentText(value: string): string {
  const d = new Date(value);
  return `${d.getMonth() + 1}/${d.getDate()} 보냄`;
}

function chars(text: string): number { return Array.from(text).length; }

/** What a person in the group is: the colour of their circle and their pill share it. */
type Tone = 'in' | 'out' | 'idle' | 'pending' | 'ok' | 'off';

function toneOf(member: GroupMember): Tone {
  if (member.revoked) return 'off';
  if (!member.registered) return 'pending';
  if (member.state === null) return 'ok';
  return member.state === 'INSIDE' ? 'in' : member.state === 'OUTSIDE' ? 'out' : 'idle';
}

function pillText(member: GroupMember): string {
  if (member.revoked) return '비활성';
  if (!member.registered) return '등록 대기';
  if (member.state === null) return '등록됨';
  if (member.state === 'INSIDE') return `${hm(member.since)} 입장`;
  if (member.state === 'OUTSIDE') return `${hm(member.since)} 퇴장`;
  return '미입장';
}

/** What the person is called on this screen: their call name, else the address this holder may see. */
function labelOf(member: GroupMember): string {
  return member.name ?? member.email;
}

function PersonIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" />
    </svg>
  );
}

function PlusIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
      strokeLinecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
  );
}

function Chevron() {
  return (
    <svg className="group-chevron" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
  );
}

export function GiftIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 12v8H4v-8M2 7h20v5H2zM12 22V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z" />
    </svg>
  );
}

/** A circle for one person: its colour is where they are, its letter the first of their call name. */
function Avatar({ member, size }: { member: GroupMember; size?: 'sm' | 'md' | 'lg' }) {
  const letter = member.me ? '나' : member.name ? Array.from(member.name)[0] : null;
  return (
    <span className={`group-avatar tone-${toneOf(member)}${size ? ` avatar-${size}` : ''}`} aria-hidden="true">
      {letter ?? <PersonIcon size={size === 'sm' ? 15 : 18} />}
    </span>
  );
}

/** Whether the ticket page has anything to show about invites for this holder. */
export function groupRelevant(view: InviteView | null): boolean {
  return view !== null && (view.quota > 0 || view.invitedBy !== null || view.members.length > 1);
}

/** Whether this holder is the one who invites, rather than one who was invited. */
function isInviter(view: InviteView): boolean {
  return view.members.find(member => member.me)?.role === 'INVITER';
}

/** Who is in the group and can come: revoked invites are not. */
function active(view: InviteView): GroupMember[] {
  return view.members.filter(member => !member.revoked);
}

/**
 * The 일행 section on the ticket. Before anyone is invited it offers the first invite;
 * once there is a group it is a row of circles that opens the group. {@code compact} is
 * for a holder already inside, where the room's news comes first and this is one line.
 */
export function GroupTile({ view, groupSize, compact, onOpen, onInvite }: {
  view: InviteView;
  /** From the ticket: how many are in the group, for an invitee who is not shown the others. */
  groupSize?: number;
  compact: boolean;
  onOpen: () => void;
  onInvite: () => void;
}) {
  const inviter = isInviter(view);
  const people = active(view);
  const inside = people.filter(member => member.state === 'INSIDE').length;
  const pending = people.filter(member => !member.registered).length;

  if (inviter && people.length <= 1 && view.members.length <= 1) {
    if (!view.canInvite) return null;
    return (
      <section className="room-tiles group-tiles" aria-labelledby="group-tile-title">
        <h3 id="group-tile-title">일행</h3>
        <div className="group-start">
          <div className="group-start-text">
            <span className="group-start-icon" aria-hidden="true">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
                strokeLinecap="round" strokeLinejoin="round"><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20a6.5 6.5 0 0 1 13 0" /><path d="M19 8v6M16 11h6" /></svg>
            </span>
            <span>
              <b>함께 올 사람을 초대할 수 있어요</b>
              <small>{view.remaining}명까지 그분 이름으로 입장권을 보내요</small>
            </span>
          </div>
          <button type="button" className="btn-soft group-start-button" onClick={onInvite}>
            <PlusIcon size={18} /> 일행 초대하기
          </button>
        </div>
      </section>
    );
  }

  // An invitee who is not shown the others still knows how many are coming.
  const shownOthers = view.members.length > 1;
  const size = shownOthers ? people.length : groupSize ?? people.length;
  const title = `일행 ${size}명${view.statesShown && shownOthers ? ` · 장내 ${inside}명` : ''}`;
  const othersInside = people.some(member => !member.me && member.state === 'INSIDE');
  const subtitle = !shownOthers
    ? view.status === 'INVITER' ? '입장 상태는 초대한 분만 볼 수 있어요' : `${view.invitedBy} 님의 일행이에요`
    : compact
      ? [pending > 0 ? `${pending}명 등록 대기` : null, view.canInvite ? `${view.remaining}명 더 초대 가능` : null]
        .filter(Boolean).join(' · ') || (othersInside ? '먼저 온 일행이 안에 있어요' : '일행 보기')
      : pending > 0 ? `${pending}명이 아직 등록 전이에요`
        : view.beforeDay ? `${dayText(view.statesFrom)}부터 입장 상태가 보여요`
          : othersInside ? '먼저 온 일행이 안에 있어요'
            : view.statesShown ? '아직 장내에 일행이 없어요' : '모두 등록을 마쳤어요';
  const circles = people.slice(0, 4);

  return (
    <section className="room-tiles group-tiles" aria-labelledby="group-tile-title">
      <h3 id="group-tile-title">일행</h3>
      <div className={`group-tile${compact ? ' is-compact' : ''}`}>
        <button type="button" className="group-tile-open" onClick={onOpen}>
          <span className="group-stack">
            {circles.map((member, index) => <Avatar key={index} member={member} size={compact ? 'sm' : undefined} />)}
            {people.length > circles.length && (
              <span className={`group-avatar tone-more${compact ? ' avatar-sm' : ''}`} aria-hidden="true">+{people.length - circles.length}</span>
            )}
          </span>
          <span className="group-tile-text">
            <b>{title}</b>
            <small>{subtitle}</small>
          </span>
          <Chevron />
        </button>
        {!compact && inviter && view.quota > 0 && (
          <div className="group-tile-more">
            <span>{view.canInvite ? `${view.remaining}명 더 초대할 수 있어요` : `${view.quota}명 모두 초대했어요`}</span>
            {view.canInvite && (
              <button type="button" className="btn-soft btn-soft-small" onClick={onInvite}><PlusIcon /> 초대하기</button>
            )}
          </div>
        )}
      </div>
    </section>
  );
}

/**
 * A sheet up from the bottom of the phone: modal, closed by Escape, by the scrim or by
 * its close button, with focus on its first field and back where it was on closing.
 */
function Sheet({ labelledBy, busy, onClose, children }: {
  labelledBy: string; busy: boolean; onClose: () => void; children: ReactNode;
}) {
  const panel = useRef<HTMLElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  const busyRef = useRef(busy);
  busyRef.current = busy;

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    (panel.current?.querySelector<HTMLElement>('input, textarea, button:not(.sheet-x)')
      ?? panel.current)?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busyRef.current) { event.stopPropagation(); close.current(); }
      // Tab stays inside the sheet, which is modal.
      if (event.key === 'Tab' && panel.current) {
        const stops = [...panel.current.querySelectorAll<HTMLElement>('input, button:not(:disabled)')];
        if (stops.length === 0) return;
        const first = stops[0], last = stops[stops.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      before?.focus?.({ preventScroll: true });
    };
  }, []);

  return (
    <div className="sheet-scrim group-scrim" role="presentation" onClick={busy ? undefined : onClose}>
      <section ref={panel} className="group-sheet" role="dialog" aria-modal="true" aria-labelledby={labelledBy}
        tabIndex={-1} onClick={event => event.stopPropagation()}>
        <span className="sheet-grip" aria-hidden="true" />
        {children}
      </section>
    </div>
  );
}

function SheetClose({ onClose, disabled }: { onClose: () => void; disabled: boolean }) {
  return (
    <button type="button" className="sheet-x" aria-label="닫기" onClick={onClose} disabled={disabled}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
        strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
    </button>
  );
}

function InviteSheet({ view, busy, onClose, onSend }: {
  view: InviteView; busy: boolean; onClose: () => void;
  /** Rejects with the server's reason, which is shown under the address. */
  onSend: (email: string, name: string | null) => Promise<void>;
}) {
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [touched, setTouched] = useState(false);
  const [refused, setRefused] = useState<{ email: string; reason: string } | null>(null);
  const emailInput = useRef<HTMLInputElement>(null);
  const typed = email.trim();
  const taken = view.members.some(member => !member.revoked && member.email.toLowerCase() === typed.toLowerCase());
  const problem = refused && refused.email === typed ? refused.reason
    : taken ? '이 주소로는 이미 이 이벤트 입장권이 있어요. 다른 주소를 적어 주세요.'
      : touched && typed && !EMAIL.test(typed) ? '이메일 주소를 확인해 주세요.' : '';
  const ready = EMAIL.test(typed) && !problem && chars(name.trim()) <= NAME_MAX;

  const submit = async () => {
    setTouched(true);
    if (!ready || busy) return;
    try {
      await onSend(typed, name.trim() || null);
    } catch (err) {
      setRefused({ email: typed, reason: err instanceof Error ? err.message : '초대를 보내지 못했어요.' });
      // Back to the address, which is what the refusal is about; the send button it was on is now off.
      window.requestAnimationFrame(() => emailInput.current?.focus());
    }
  };

  return (
    <Sheet labelledBy="invite-sheet-title" busy={busy} onClose={onClose}>
      <div className="group-sheet-head">
        <div>
          <h2 id="invite-sheet-title">일행 초대하기</h2>
          <p>{view.remaining}명 더 초대할 수 있어요</p>
        </div>
        <SheetClose onClose={onClose} disabled={busy} />
      </div>
      <form className="group-sheet-form" noValidate onSubmit={event => { event.preventDefault(); void submit(); }}>
        <div className="field">
          <label htmlFor="invite-email">이메일</label>
          <input ref={emailInput} id="invite-email" type="email" inputMode="email" autoComplete="off" autoCapitalize="off"
            value={email} placeholder="friend@example.com" aria-invalid={problem ? true : undefined}
            aria-describedby={problem ? 'invite-email-msg' : undefined}
            onChange={event => setEmail(event.target.value)} onBlur={() => setTouched(true)} />
          {problem && (
            <span id="invite-email-msg" className="group-field-error" role="alert">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
                strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v6M12 16.5v.5" /></svg>
              {problem}
            </span>
          )}
        </div>
        <div className="field">
          <label htmlFor="invite-name">부를 이름 <span className="field-optional">선택</span></label>
          <input id="invite-name" type="text" autoComplete="off" maxLength={NAME_MAX} value={name}
            placeholder="예: 민지" aria-describedby="invite-name-hint" onChange={event => setName(event.target.value)} />
          <span id="invite-name-hint" className="group-field-hint">
            적으면 일행 화면에 이메일 대신 이 이름이 보여요. 비워 두면 가린 이메일이 보여요.
          </span>
        </div>
        <p className="group-sheet-note">그분 이름의 입장권이 메일로 가고, 직접 등록하면 일행에 들어와요. 등록 전에는 취소할 수 있어요.</p>
        <button type="submit" className="btn-primary" disabled={!ready || busy}>
          {busy ? '보내는 중…' : '초대 입장권 보내기'}</button>
      </form>
    </Sheet>
  );
}

function RenameSheet({ member, busy, onClose, onSave }: {
  member: GroupMember; busy: boolean; onClose: () => void;
  onSave: (name: string | null) => Promise<void>;
}) {
  const [name, setName] = useState(member.name ?? '');
  const [error, setError] = useState('');
  const count = chars(name.trim());

  const save = async (next: string | null) => {
    if (busy) return;
    setError('');
    try { await onSave(next); }
    catch (err) { setError(err instanceof Error ? err.message : '이름을 바꾸지 못했어요.'); }
  };

  return (
    <Sheet labelledBy="rename-sheet-title" busy={busy} onClose={onClose}>
      <div className="group-sheet-head is-person">
        <Avatar member={member} size="lg" />
        <div>
          <h2 id="rename-sheet-title" className="group-sheet-who">{member.email}</h2>
          <p>내가 초대 · {pillText(member)}</p>
        </div>
        <SheetClose onClose={onClose} disabled={busy} />
      </div>
      <form className="group-sheet-form" onSubmit={event => { event.preventDefault(); void save(name.trim() || null); }}>
        <div className="field">
          <label htmlFor="rename-input">부를 이름</label>
          <div className="group-count-input">
            <input id="rename-input" type="text" autoComplete="off" maxLength={NAME_MAX} value={name}
              aria-describedby="rename-hint rename-count" onChange={event => setName(event.target.value)} />
            <span id="rename-count" aria-live="polite">{count}/{NAME_MAX}</span>
          </div>
          <span id="rename-hint" className="group-field-hint">일행 모두에게 이 이름으로 보여요. 비우면 가린 이메일로 돌아가요.</span>
          {error && <span className="group-field-error" role="alert">{error}</span>}
        </div>
        <div className="group-sheet-pair">
          <button type="button" className="btn-secondary" disabled={busy || member.name === null}
            onClick={() => { setName(''); void save(null); }}>이름 지우기</button>
          <button type="submit" className="btn-primary" disabled={busy || count > NAME_MAX}>저장</button>
        </div>
        <p className="group-sheet-note">등록한 분과 입장한 분은 이름만 바꿀 수 있어요. 메일 다시 보내기와 초대 취소는 등록 전에만 돼요.</p>
      </form>
    </Sheet>
  );
}

/** Time since the group was last read, in the words of the bar above it. */
function useAgo(stamp: number): string {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 15000);
    return () => window.clearInterval(timer);
  }, [stamp]);
  const minutes = Math.floor((now - stamp) / 60000);
  return minutes < 1 ? '방금 전 업데이트' : `${minutes}분 전 업데이트`;
}

/**
 * The group, reached from the ticket. The inviter sees one list - themselves, then each
 * person they invited - and manages each invite from its row; an invitee sees whom the
 * event lets them see. Where each one is shows only on the event's day and only as the
 * event allows; otherwise the pills say just whether each has registered.
 */
export default function TicketGroup({ sessionId, token, view, onChange, onBack, startInviting, onInviteShown }: {
  sessionId: string; token: string; view: InviteView;
  /** Read the group again after something here changed it. */
  onChange: () => Promise<void>;
  onBack: () => void;
  /** Opened from the ticket's invite button: the invite sheet comes up at once. */
  startInviting: boolean;
  onInviteShown: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [sheet, setSheet] = useState<'invite' | GroupMember | null>(null);
  const [cancelling, setCancelling] = useState<GroupMember | null>(null);
  const [readAt, setReadAt] = useState(() => Date.now());
  useEffect(() => { setReadAt(Date.now()); }, [view]);
  const ago = useAgo(readAt);

  const inviter = isInviter(view);
  useEffect(() => {
    if (!startInviting) return;
    if (inviter && view.canInvite) setSheet('invite');
    onInviteShown();
  }, [startInviting, inviter, view.canInvite, onInviteShown]);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError(''); setNotice('');
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
    finally { setBusy(false); }
  }

  /** Not through run(): the sheet shows the refusal under the address, where it was typed. */
  const send = async (email: string, name: string | null) => {
    setBusy(true); setError(''); setNotice('');
    try {
      const row = await sendInvite(sessionId, token, email, name);
      setSheet(null);
      setNotice(`${row.name ?? row.email} 님에게 초대 입장권을 보냈어요`);
      await onChange();
    } finally { setBusy(false); }
  };

  const rename = async (member: GroupMember, name: string | null) => {
    setBusy(true); setError(''); setNotice('');
    try {
      const row = await renameInvite(sessionId, token, member.ticketId!, name);
      setSheet(null);
      setNotice(row.name ? `이제 '${row.name}' 이름으로 보여요` : '이름을 지웠어요. 가린 이메일로 보여요');
      await onChange();
    } finally { setBusy(false); }
  };

  const resend = (member: GroupMember) => run(async () => {
    const row = await resendInvite(sessionId, token, member.ticketId!);
    if (!row.mailed) throw new Error('메일을 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.');
    setNotice(`${labelOf(member)} 님에게 초대 메일을 다시 보냈어요`);
    await onChange();
  });

  const cancel = (member: GroupMember) => run(async () => {
    await cancelInvite(sessionId, token, member.ticketId!);
    setCancelling(null);
    setNotice(`${labelOf(member)} 님 초대를 취소했어요`);
    await onChange();
  });

  const people = active(view);
  const inside = people.filter(member => member.state === 'INSIDE').length;
  const pending = people.filter(member => !member.registered).length;
  const registered = people.filter(member => !member.me && member.registered).length;
  const counted = [
    view.statesShown ? (inside > 0 ? `장내 ${inside}명` : null) : registered > 0 ? `등록 ${registered}명` : null,
    pending > 0 ? `등록 대기 ${pending}명` : null,
  ].filter(Boolean).join(' · ');
  const guestAlone = !inviter && view.members.length <= 1;
  const pendingInvites = view.members.filter(member => member.ticketId !== null && !member.registered && !member.revoked);

  const menuFor = (member: GroupMember): MenuItem[] => {
    const rename: MenuItem = { label: '부를 이름 바꾸기', onSelect: () => setSheet(member) };
    if (member.registered) return [rename];
    return [
      { label: member.canResend ? '초대 메일 다시 보내기' : '초대 메일 다시 보내기 (모두 보냄)',
        onSelect: () => { void resend(member); }, disabled: !member.canResend || busy },
      rename,
      { label: '초대 취소', onSelect: () => setCancelling(member), danger: true, disabled: !view.canCancel || busy },
    ];
  };

  /** The two lines beside a circle: who it is, and what to know about them. */
  const lines = (member: GroupMember): [ReactNode, string] => {
    if (inviter) {
      if (member.me) return ['나', member.email];
      if (member.revoked) return [labelOf(member), '운영팀이 비활성화했어요'];
      const sub = [member.name ? member.email : null,
        !member.registered && member.sentAt ? sentText(member.sentAt) : null].filter(Boolean).join(' · ');
      return [labelOf(member), sub];
    }
    if (member.me) {
      return [member.name ? <>{member.name} <span className="group-me-tag">(나)</span></> : '내 입장권',
        member.name ? '초대한 분이 붙인 이름이에요' : `${view.invitedBy} 님이 초대`];
    }
    if (member.role === 'INVITER') return [member.email, '초대한 분'];
    return [labelOf(member), '함께 초대받은 분'];
  };

  return (
    <div className="group-view">
      <div className="group-top">
        <button type="button" className="group-back" onClick={onBack}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m15 6-6 6 6 6" /></svg>
          입장권
        </button>
        <span className="group-updated" aria-live="polite">{ago}</span>
      </div>

      <div className="group-head">
        <div>
          <h2>일행</h2>
          <p>
            {!inviter && <><b>{view.invitedBy}</b> 님의 일행이에요</>}
            {!inviter && counted && !guestAlone && <br />}
            {(inviter || !guestAlone) && (counted || (inviter ? `일행 ${people.length}명` : ''))}
          </p>
        </div>
        {inviter && view.quota > 0 && (view.canInvite ? (
          <button type="button" className="btn-primary group-invite" onClick={() => setSheet('invite')} disabled={busy}>
            <PlusIcon /> 초대하기</button>
        ) : (
          <span className="group-invite is-full">{view.quota}명 모두 초대했어요</span>
        ))}
      </div>

      {view.beforeDay && view.status !== 'OFF' && (inviter || view.status === 'GROUP') && (
        <p className="group-banner">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4M16 3v4" /></svg>
          <span><b>{dayText(view.statesFrom)} 당일</b>부터 일행의 입장 상태가 여기에 보여요.</span>
        </p>
      )}

      {guestAlone && (
        <div className="group-lock">
          <span className="group-lock-icon" aria-hidden="true">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" strokeLinejoin="round"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>
          </span>
          {view.status === 'INVITER' ? <>
            <b>입장 상태는 초대한 분만 볼 수 있어요</b>
            <span>이 이벤트는 일행의 입장 상태를 초대한 분에게만 보여 줘요. 만날 곳은 초대한 분과 직접 정해 주세요.</span>
          </> : <>
            <b>이 이벤트는 일행을 보여 주지 않아요</b>
            <span>일행의 입장 상태는 아무에게도 보이지 않아요. 만날 곳은 초대한 분과 직접 정해 주세요.</span>
          </>}
        </div>
      )}

      <ul className="group-members">
        {view.members.map((member, index) => {
          const [title, sub] = lines(member);
          const managed = member.ticketId !== null && !member.revoked;
          return (
            <li key={member.ticketId ?? `m${index}`}
              className={`${member.me ? 'is-me' : ''}${member.revoked ? ' is-off' : ''}${managed ? ' has-menu' : ''}`}>
              <Avatar member={member} />
              <span className="group-who">
                <b>{title}</b>
                {sub && <small>{sub}</small>}
              </span>
              <span className={`group-pill tone-${toneOf(member)}`}>{pillText(member)}</span>
              {managed && (
                <RowMenu label={`${labelOf(member)} ${member.registered ? '관리' : '초대 관리'}`}
                  buttonClassName="group-menu-button" items={menuFor(member)} />
              )}
            </li>
          );
        })}
      </ul>

      {inviter && view.status === 'OFF' && (
        <p className="group-note">이 이벤트는 일행의 입장 상태를 보여 주지 않아요. 내가 초대한 분이 등록했는지만 볼 수 있어요.</p>
      )}
      {!inviter && !guestAlone && (
        <p className="group-foot">초대한 분이 이름을 붙이지 않은 분은 이메일 앞부분만 보여요. 초대받은 입장권으로는 다른 분을 초대할 수 없어요.</p>
      )}
      {inviter && view.quota > 0 && (view.canInvite ? (
        <p className="group-foot">{view.remaining}명 더 초대할 수 있어요 · {view.quota}명 중 {view.used}명 초대</p>
      ) : pendingInvites.length > 0 ? (
        <p className="group-foot">{view.cancelsLeft > 0
          ? `등록 전인 초대를 취소하면 다시 초대할 수 있어요. 취소는 ${view.cancelsLeft}번 더 할 수 있어요.`
          : '취소할 수 있는 횟수를 모두 써서 더는 취소할 수 없어요.'}</p>
      ) : (
        <p className="group-foot">{view.quota}명 중 {view.used}명 초대</p>
      ))}
      {inviter && view.quota === 0 && view.members.length <= 1 && (
        <p className="console-none">이 입장권으로는 초대할 수 없어요.</p>
      )}

      {sheet === 'invite' && (
        <InviteSheet view={view} busy={busy} onClose={() => setSheet(null)} onSend={send} />
      )}
      {sheet !== null && sheet !== 'invite' && (
        <RenameSheet member={sheet} busy={busy} onClose={() => setSheet(null)}
          onSave={name => rename(sheet, name)} />
      )}

      <ConfirmDialog
        open={cancelling !== null}
        tone="danger"
        title={`${cancelling ? labelOf(cancelling) : ''} 님 초대를 취소할까요?`}
        message={`보낸 입장권 링크가 바로 닫히고, ${cancelling ? labelOf(cancelling) : ''} 님에게 취소 메일이 가요. 초대 한 자리가 다시 비어요.`}
        confirmLabel="초대 취소"
        cancelLabel="그대로 두기"
        busy={busy}
        onCancel={() => setCancelling(null)}
        onConfirm={() => { if (cancelling) void cancel(cancelling); }}
      />
      <Toast notice={notice} error={error} onNotice={setNotice} onError={setError} />
    </div>
  );
}
