import { useCallback, useEffect, useRef, useState } from 'react';
import { mutate } from '@shared/auth';
import ConfirmDialog from '@shared/components/ConfirmDialog';
import { read, shortTime } from '../console/model';
import { openLive } from '../ticket/live';
import { chatCounts, useCoalesced } from '../ticket/coalesce';
import ConsoleChatPeople, { type BanTarget, type ChatPerson } from './ConsoleChatPeople';
import { ThumbDown, ThumbUp } from './Thumbs';

interface FeedRow {
  id: number; author: string; ticketId: number | null; ticketRef: string | null; staff: boolean; notice: boolean;
  body: string; likes: number; dislikes: number; reports: number; hidden: boolean; pinned: boolean; pinnedUntil: string | null;
  muted: boolean; banned: boolean; createdAt: string; alert: boolean;
}
interface ChatSettings { enabled: boolean; cooldownSeconds: number; maxLength: number; hotLikes: number; hotMinutes: number; reportHide: number }
interface ConsoleChatView { settings: ChatSettings; messages: FeedRow[]; reported: number; hiddenCount: number }

type Filter = 'ALL' | 'REPORTED' | 'HIDDEN' | 'PINNED';
/** The feed's two faces: what is said, and who is saying it. */
type Pane = 'MESSAGES' | 'PEOPLE';

const post = (url: string, body: unknown, method = 'POST') => mutate(url, {
  method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
}).then(read);

/**
 * The organiser's side of the room's chat: send a notice, watch what is said, hide what
 * should not be there, stop a ticket writing or put it out of the chat, and set the rules.
 */
export default function ConsoleChat({ sessionId, onNotice, onError }: {
  /** Ignored: the chat listens to the event's channel itself, and tells a like from a new line. */
  tick?: number;
  sessionId: number; onNotice: (text: string) => void; onError: (text: string) => void;
}) {
  const [view, setView] = useState<ConsoleChatView | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [notice, setNotice] = useState('');
  const [pin, setPin] = useState(true);
  const [alsoAd, setAlsoAd] = useState(false);
  const [alert, setAlert] = useState(false);
  const [pane, setPane] = useState<Pane>('MESSAGES');
  const [peopleRevision, setPeopleRevision] = useState(0);
  const [banTarget, setBanTarget] = useState<BanTarget | null>(null);
  const [banBusy, setBanBusy] = useState(false);
  const base = `/api/admin/sessions/${sessionId}/chat`;

  // Only the latest read may fill the feed: a slow answer to an older filter is dropped.
  const latest = useRef(0);
  const load = useCallback(async () => {
    const seq = ++latest.current;
    try {
      const next = await read(await fetch(`${base}?filter=${filter}`)) as ConsoleChatView;
      if (seq === latest.current) setView(next);
    } catch (err) {
      if (seq === latest.current) onError(err instanceof Error ? err.message : '채팅을 불러오지 못했어요.');
    }
  }, [base, filter, onError]);
  useEffect(() => { void load(); }, [load]);

  // A busy room says something several times a second; the feed reads again once per
  // burst, and a like only moves that line's counts.
  const nudge = useCoalesced(useCallback(() => {
    void load();
    setPeopleRevision(value => value + 1);
  }, [load]), 600);
  useEffect(() => {
    const live = openLive(`/ws/admin/sessions/${sessionId}`, message => {
      if (message.type === 'CHAT_LIKE') {
        const counts = chatCounts(message);
        if (counts) {
          setView(current => current && ({
            ...current,
            messages: current.messages.map(row => row.id === counts.messageId
              ? { ...row, likes: counts.likes, dislikes: counts.dislikes } : row),
          }));
          return;
        }
      }
      if (['CHAT', 'CHAT_REPORT', 'CHAT_HIDE', 'CHAT_LIKE'].includes(message.type)) nudge();
    });
    return () => live.close();
  }, [sessionId, nudge]);

  const act = async (work: () => Promise<unknown>, done: string) => {
    try { await work(); onNotice(done); await load(); setPeopleRevision(value => value + 1); }
    catch (err) { onError(err instanceof Error ? err.message : '요청을 처리하지 못했어요.'); }
  };

  const mute = (ticketId: number, author: string, muted: boolean) => void act(
    () => post(`${base}/mute`, { ticketId, muted: !muted }),
    muted ? `${author}의 채팅 쓰기를 다시 열었어요.` : `${author}의 채팅 쓰기를 막았어요. 읽기는 계속돼요.`);

  const confirmBan = async () => {
    if (!banTarget) return;
    setBanBusy(true);
    await act(() => post(`${base}/ban`, { ticketId: banTarget.ticketId, banned: banTarget.banned }),
      banTarget.banned
        ? `${banTarget.author}을(를) 채팅에서 차단했어요. 메시지가 관람객에게서 사라졌어요.`
        : `${banTarget.author}의 차단을 풀었어요. 메시지가 다시 보여요.`);
    setBanBusy(false);
    setBanTarget(null);
  };

  const sendNotice = (event: React.FormEvent) => {
    event.preventDefault();
    if (!notice.trim()) return;
    void act(() => post(`${base}/notice`, { body: notice.trim(), pin, alsoAd, alert }),
      alert ? '공지를 보내고 모든 입장권 화면에 알렸어요.'
        : pin ? '공지를 보내고 채팅 맨 위에 고정했어요.' : '공지를 보냈어요.')
      .then(() => { setNotice(''); setAlert(false); });
  };

  const saveSettings = (form: HTMLFormElement) => {
    const data = new FormData(form);
    void act(() => post(`${base}/settings`, {
      enabled: data.get('enabled') === 'on',
      cooldownSeconds: data.get('cooldownSeconds'), maxLength: data.get('maxLength'),
      hotLikes: data.get('hotLikes'), hotMinutes: data.get('hotMinutes'), reportHide: data.get('reportHide'),
    }, 'PUT'), '채팅 규칙을 저장했어요. 바로 적용돼요.');
  };

  if (!view) return <p className="console-none">채팅을 불러오고 있어요.</p>;
  const pinned = view.messages.filter(row => row.pinned || (row.alert && !row.hidden));
  const filters: Array<[Filter, string]> = [
    ['ALL', '전체'], ['REPORTED', `신고됨 ${view.reported}`], ['HIDDEN', `가려짐 ${view.hiddenCount}`], ['PINNED', '고정됨'],
  ];

  return (
    <div className="console-split">
      <div className="console-stack">
        <form className="console-card" onSubmit={sendNotice}>
          <h3>공지 보내기</h3>
          <label className="visually-hidden" htmlFor="chat-notice">공지 내용</label>
          <textarea id="chat-notice" className="console-textarea" rows={3} maxLength={500} value={notice}
            placeholder="예: 오후 2시 라이브 드로잉이 메인홀 무대에서 시작해요." onChange={e => setNotice(e.target.value)} />
          <div className="console-checks">
            <label><input type="checkbox" checked={pin} onChange={e => setPin(e.target.checked)} /> 채팅 맨 위에 고정</label>
            <label><input type="checkbox" checked={alsoAd} onChange={e => setAlsoAd(e.target.checked)} /> 알림판에도 올리기</label>
            <label><input type="checkbox" checked={alert} onChange={e => setAlert(e.target.checked)} /> 입장권 화면에 알림</label>
          </div>
          {alert && (
            <p className="hint-text">입장 전인 사람까지 이 이벤트의 입장권을 연 모두에게 맨 위에 보여요. 내릴 때까지 남아 있어요.</p>
          )}
          <button className="btn-primary" type="submit" disabled={!notice.trim()}>공지 보내기</button>
        </form>

        <section className="console-card" aria-labelledby="chat-feed">
          <div className="console-card-head">
            <h3 id="chat-feed">{pane === 'MESSAGES' ? '메시지' : '참여자'}</h3>
          </div>
          <div className="inner-tabs" role="tablist" aria-label="채팅 보기">
            {([['MESSAGES', '메시지'], ['PEOPLE', '참여자']] as Array<[Pane, string]>).map(([key, label]) => (
              <button key={key} type="button" role="tab" aria-selected={pane === key}
                className={pane === key ? 'active' : ''} onClick={() => setPane(key)}>{label}</button>
            ))}
          </div>
          {pane === 'PEOPLE' ? (
            <ConsoleChatPeople base={base} revision={peopleRevision} onError={onError} onBan={setBanTarget}
              onMute={(person: ChatPerson) => mute(person.ticketId, person.author, person.muted)} />
          ) : (<>
          <div className="chip-row" role="group" aria-label="보기">
            {filters.map(([key, label]) => (
              <button key={key} type="button" className={filter === key ? 'on' : ''} aria-pressed={filter === key}
                onClick={() => setFilter(key)}>{label}</button>
            ))}
          </div>
          <div className="console-table-box flat">
            <table className="console-table chat-feed">
              <thead><tr><th>보낸 사람</th><th>메시지</th><th className="num">반응</th><th>상태</th><th aria-label="작업" /></tr></thead>
              <tbody>
                {view.messages.map(row => (
                  <tr key={row.id} className={row.hidden || row.banned ? 'is-off' : row.reports > 0 ? 'is-flag' : ''}>
                    <td><b>{row.author}</b>
                      <small className="cell-note">{shortTime(row.createdAt)}{row.ticketRef ? ` · ${row.ticketRef}` : ''}</small></td>
                    <td className="chat-feed-body">{row.body}</td>
                    <td className="num reactions">
                      {/* As holders see them: thumbs up and down, both always counted. */}
                      <span className={`reaction${row.likes > 0 ? '' : ' none'}`} aria-label={`좋아요 ${row.likes}`}>
                        <ThumbUp size={13} /> {row.likes}</span>
                      <span className={`reaction${row.dislikes > 0 ? '' : ' none'}`} aria-label={`싫어요 ${row.dislikes}`}>
                        <ThumbDown size={13} /> {row.dislikes}</span>
                    </td>
                    <td>
                      <span className={`pill pill-${row.hidden ? 'wait' : row.reports > 0 ? 'off' : row.pinned ? 'in' : row.staff ? 'out' : 'ok'}`}>
                        {row.hidden ? '가려짐' : row.reports > 0 ? `신고 ${row.reports}` : row.pinned ? (row.notice ? '공지 고정' : '인기 고정') : row.staff ? '운영' : '보임'}
                      </span>
                      {row.banned && <span className="pill pill-off" title="관람객에게는 보이지 않아요">차단됨</span>}
                      {row.muted && <small className="cell-note">채팅 제한됨</small>}
                      {row.alert && <small className="cell-note">입장권에 알리는 중</small>}
                    </td>
                    <td className="row-actions stack">
                      <button className="btn-tiny" type="button" onClick={() => void act(
                        () => post(`${base}/${row.id}/hidden`, { hidden: !row.hidden }),
                        row.hidden ? '메시지를 다시 보이게 했어요.' : '메시지를 가렸어요.')}>
                        {row.hidden ? '다시 보이기' : '가리기'}</button>
                      {!row.hidden && (
                        <button className="btn-tiny" type="button" onClick={() => void act(
                          () => post(`${base}/${row.id}/pinned`, { pinned: !row.pinned }),
                          row.pinned ? '고정을 내렸어요.' : '맨 위에 고정했어요.')}>
                          {row.pinned ? '고정 내리기' : '고정'}</button>
                      )}
                      {row.notice && !row.hidden && (
                        <button className="btn-tiny" type="button" onClick={() => void act(
                          () => post(`${base}/${row.id}/alert`, { alert: !row.alert }),
                          row.alert ? '입장권 화면에서 내렸어요.' : '모든 입장권 화면에 알렸어요.')}>
                          {row.alert ? '알림 내리기' : '입장권에 알림'}</button>
                      )}
                      {row.ticketId !== null && (
                        <button className="btn-tiny btn-tiny-danger" type="button"
                          onClick={() => mute(row.ticketId!, row.author, row.muted)}>
                          {row.muted ? '제한 풀기' : '채팅 제한'}</button>
                      )}
                      {row.ticketId !== null && (
                        <button className={`btn-tiny${row.banned ? '' : ' btn-tiny-danger'}`} type="button"
                          onClick={() => setBanTarget({ ticketId: row.ticketId!, author: row.author, ticketRef: row.ticketRef,
                            banned: !row.banned })}>
                          {row.banned ? '차단 해제' : '차단'}</button>
                      )}
                    </td>
                  </tr>
                ))}
                {view.messages.length === 0 && (
                  <tr><td colSpan={5} className="console-none">{filter === 'ALL' ? '아직 메시지가 없어요.' : '해당하는 메시지가 없어요.'}</td></tr>
                )}
              </tbody>
            </table>
          </div>
          </>)}
          <p className="console-foot">관람객에게는 자동 별명만 보이고, 여기서만 입장권 번호를 함께 봅니다. 채팅 제한은 그 입장권의 쓰기만 막고, 차단은 쓰기를 막으면서 그 사람의 메시지를 모두 관람객에게서 가려요.</p>
        </section>
      </div>

      <aside className="console-aside">
        <form className="console-card" key={JSON.stringify(view.settings)}
          onSubmit={e => { e.preventDefault(); saveSettings(e.currentTarget); }}>
          <h3>채팅 규칙</h3>
          <label className="console-switch"><input type="checkbox" name="enabled" defaultChecked={view.settings.enabled} /> 채팅 열기</label>
          <div className="console-fields">
            <label>입력 간격(초)<input name="cooldownSeconds" type="number" min={0} max={3600} defaultValue={view.settings.cooldownSeconds} /></label>
            <label>최대 글자<input name="maxLength" type="number" min={10} max={500} defaultValue={view.settings.maxLength} /></label>
            <label>인기 고정 좋아요<input name="hotLikes" type="number" min={1} defaultValue={view.settings.hotLikes} /></label>
            <label>고정 시간(분)<input name="hotMinutes" type="number" min={1} max={600} defaultValue={view.settings.hotMinutes} /></label>
            <label>자동 가림 신고 수<input name="reportHide" type="number" min={1} defaultValue={view.settings.reportHide} /></label>
          </div>
          <p className="hint-text">입장한 관람객만 읽을 수 있고, 장내에 있을 때만 쓸 수 있어요.</p>
          <button className="btn-primary" type="submit">저장하고 바로 적용</button>
        </form>
        <section className="console-card" aria-labelledby="chat-pinned">
          <h3 id="chat-pinned">지금 고정된 것</h3>
          {pinned.length === 0 ? <p className="console-none">고정된 메시지가 없어요.</p> : (
            <ul className="pinned-list">
              {pinned.map(row => (
                <li key={row.id} className={row.notice ? 'notice' : 'hot'}>
                  <b>{row.alert && !row.pinned ? '알림' : row.notice ? '공지' : '인기'}</b>
                  <span>{row.body}{row.alert && row.pinned && <small className="cell-note">입장권에도 알리는 중</small>}</span>
                  <span className="pinned-actions">
                    {row.pinned && (
                      <button className="btn-tiny" type="button" onClick={() => void act(
                        () => post(`${base}/${row.id}/pinned`, { pinned: false }), '고정을 내렸어요.')}>고정 내리기</button>
                    )}
                    {row.alert && (
                      <button className="btn-tiny" type="button" onClick={() => void act(
                        () => post(`${base}/${row.id}/alert`, { alert: false }), '입장권 화면에서 내렸어요.')}>알림 내리기</button>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>
      </aside>

      <ConfirmDialog
        open={banTarget !== null}
        title={banTarget?.banned ? `${banTarget.author}을(를) 채팅에서 차단할까요?` : `${banTarget?.author ?? ''}의 차단을 풀까요?`}
        message={banTarget?.banned
          ? '이 입장권으로는 채팅을 쓰거나 누를 수 없고, 지금까지 쓴 메시지도 모두 관람객에게서 사라져요. 메시지는 지워지지 않아서 차단을 풀면 다시 보여요.'
          : '다시 채팅에 참여할 수 있고, 가려졌던 메시지가 관람객에게 다시 보여요. 따로 가린 메시지는 그대로예요.'}
        details={banTarget?.ticketRef ? [['입장권', banTarget.ticketRef]] : undefined}
        confirmLabel={banTarget?.banned ? '차단' : '차단 해제'}
        tone={banTarget?.banned ? 'danger' : 'primary'}
        busy={banBusy}
        onConfirm={() => void confirmBan()}
        onCancel={() => setBanTarget(null)}
      />
    </div>
  );
}
