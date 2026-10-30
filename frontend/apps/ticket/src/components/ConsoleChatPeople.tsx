import { useCallback, useEffect, useRef, useState } from 'react';
import { read, shortTime } from '../console/model';
import { EMPTY_PAGE, Pager, useDebounced, type Paged } from './ListTools';

/** A ticket that has written in the chat, or been muted or banned, as the console lists it. */
export interface ChatPerson {
  ticketId: number; ticketRef: string; author: string; messages: number; lastAt: string | null;
  muted: boolean; banned: boolean;
}

/** Who the organiser is about to ban or let back, from either the feed or this list. */
export interface BanTarget { ticketId: number; author: string; ticketRef: string | null; banned: boolean }

const PAGE_SIZE = 20;

/**
 * Everyone in the event's chat, to find one person by ticket number, the name holders
 * see, or email, and mute or ban them without hunting for one of their lines.
 */
export default function ConsoleChatPeople({ base, revision, onMute, onBan, onError }: {
  base: string;
  /** Changes when the room or a ban changed, so the list reads again. */
  revision: number;
  onMute: (person: ChatPerson) => void;
  onBan: (target: BanTarget) => void;
  onError: (text: string) => void;
}) {
  const [query, setQuery] = useState('');
  const search = useDebounced(query.trim());
  const [pageNo, setPageNo] = useState(0);
  const [page, setPage] = useState<Paged<ChatPerson>>({ ...EMPTY_PAGE, size: PAGE_SIZE });

  useEffect(() => { setPageNo(0); }, [search]);

  // Only the latest read may fill the list: a slow answer to an older search is dropped.
  const latest = useRef(0);
  const load = useCallback(async () => {
    const seq = ++latest.current;
    try {
      const params = new URLSearchParams({ q: search, page: String(pageNo), size: String(PAGE_SIZE) });
      const next = await read(await fetch(`${base}/people?${params}`)) as Paged<ChatPerson>;
      if (seq === latest.current) setPage(next);
    } catch (err) {
      if (seq === latest.current) onError(err instanceof Error ? err.message : '참여자를 불러오지 못했어요.');
    }
  }, [base, search, pageNo, onError]);
  useEffect(() => { void load(); }, [load, revision]);

  return (
    <>
      <div className="list-filter">
        <input type="search" value={query} placeholder="입장권 번호, 별명, 이메일로 찾기"
          aria-label="입장권 번호, 별명, 이메일로 찾기" onChange={e => setQuery(e.target.value)} />
      </div>
      <div className="console-table-box flat">
        <table className="console-table chat-people">
          <thead><tr><th>별명</th><th className="num">메시지</th><th>마지막</th><th>상태</th><th aria-label="작업" /></tr></thead>
          <tbody>
            {page.items.map(person => (
              <tr key={person.ticketId} className={person.banned ? 'is-off' : ''}>
                <td><b>{person.author}</b><small className="cell-note">{person.ticketRef}</small></td>
                <td className="num">{person.messages.toLocaleString()}</td>
                <td>{person.lastAt ? shortTime(person.lastAt) : '-'}</td>
                <td>
                  {person.banned && <span className="pill pill-off">차단</span>}
                  {person.muted && <span className="pill pill-wait">채팅 제한</span>}
                  {!person.banned && !person.muted && <span className="pill pill-ok">참여 중</span>}
                </td>
                <td className="row-actions stack">
                  <button className="btn-tiny btn-tiny-danger" type="button" onClick={() => onMute(person)}>
                    {person.muted ? '제한 풀기' : '채팅 제한'}</button>
                  <button className={`btn-tiny${person.banned ? '' : ' btn-tiny-danger'}`} type="button"
                    onClick={() => onBan({ ticketId: person.ticketId, author: person.author, ticketRef: person.ticketRef,
                      banned: !person.banned })}>
                    {person.banned ? '차단 해제' : '차단'}</button>
                </td>
              </tr>
            ))}
            {page.items.length === 0 && (
              <tr><td colSpan={5} className="console-none">
                {search ? '찾는 참여자가 없어요.' : '아직 채팅에 참여한 사람이 없어요.'}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <Pager page={page} unit="명" onPage={setPageNo} />
    </>
  );
}
