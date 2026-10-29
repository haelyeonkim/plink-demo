import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { ProtectedLink } from '../types';

type Period = 'all' | 'active' | 'expired';

/** Whether a link still opens: past its date, or out of views, it does not. */
export function linkState(row: ProtectedLink): 'active' | 'expired' | 'exhausted' {
  if (row.expiresAt && new Date(row.expiresAt).getTime() < Date.now()) return 'expired';
  if (row.maxViews > 0 && row.viewCount >= row.maxViews) return 'exhausted';
  return 'active';
}
export const STATE_LABEL = { active: '사용 중', expired: '만료', exhausted: '열람 한도 도달' } as const;

export function shortDate(value: string | null): string {
  if (!value) return '';
  const d = new Date(value);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())}`;
}

/**
 * The links, as a table: one row each, the columns an operator compares across links,
 * the newest first. Laid out the way the ticket console lists its events.
 */
export default function LinkTable({ links, loading }: { links: ProtectedLink[]; loading: boolean }) {
  const [period, setPeriod] = useState<Period>('all');
  const [query, setQuery] = useState('');
  const rows = [...links].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  const active = rows.filter(row => linkState(row) === 'active').length;
  const needle = query.trim().toLowerCase();
  const shown = rows
    .filter(row => period === 'all' || (period === 'active') === (linkState(row) === 'active'))
    .filter(row => !needle || [row.title, row.originalUrl, row.contentTitle, row.recipientNames]
      .some(value => (value ?? '').toLowerCase().includes(needle)));
  const periods: Array<[Period, string, number]> = [
    ['all', '전체', rows.length], ['active', '사용 중', active], ['expired', '만료', rows.length - active],
  ];

  if (!loading && links.length === 0) {
    return (
      <div className="console-empty">
        <h3>첫 링크를 만들어 보세요</h3>
        <ol>
          <li><b>열 대상 정하기</b> 외부 주소나 여기서 만든 컨텐츠를 고르세요.</li>
          <li><b>수신자에게 발급</b> 한 사람에 하나씩 링크가 나가요.</li>
          <li><b>열람 확인</b> 누가 언제 열었는지 확인해요.</li>
        </ol>
        <Link className="btn-primary" to="/links/new">링크 추가</Link>
      </div>
    );
  }

  return (
    <>
      <div className="console-toolbar">
        <fieldset className="segmented">
          <legend className="visually-hidden">상태</legend>
          {periods.map(([key, label, n]) => (
            <label key={key} className={period === key ? 'on' : ''}>
              <input type="radio" name="link-period" value={key} checked={period === key}
                onChange={() => setPeriod(key)} />
              {label} <span>{n}</span>
            </label>
          ))}
        </fieldset>
        <label className="console-search">
          <span className="visually-hidden">링크 검색</span>
          <input type="search" placeholder="제목·주소·컨텐츠·수신자 검색" value={query}
            onChange={event => setQuery(event.target.value)} />
        </label>
      </div>

      <div className="console-table-box">
        <table className="console-table event-table link-table">
          <thead>
            <tr>
              <th>상태</th><th>링크</th><th>대상</th><th>만든 날</th><th>만료</th>
              <th className="num">발급</th><th>등록</th><th className="num">열람</th>
            </tr>
          </thead>
          <tbody>
            {shown.map(row => {
              const state = linkState(row);
              const rate = row.recipientCount > 0 ? Math.round((row.claimedCount / row.recipientCount) * 100) : 0;
              return (
                <tr key={row.id} className={state !== 'active' ? 'is-past' : ''}>
                  <td><span className={`pill pill-${state === 'active' ? 'ok' : 'out'}`}>{STATE_LABEL[state]}</span></td>
                  <td className="event-name">
                    <Link to={`/links/${row.id}`}>{row.title || row.contentTitle || row.originalUrl}</Link>
                    {row.hasPassword && <span className="pill pill-wait">비밀번호</span>}
                  </td>
                  <td className="muted link-target">
                    {row.contentId ? <>컨텐츠 · {row.contentTitle}</> : <code title={row.originalUrl ?? ''}>{row.originalUrl}</code>}
                  </td>
                  <td className="muted">{shortDate(row.createdAt)}</td>
                  <td className="muted">{row.expiresAt ? shortDate(row.expiresAt) : '없음'}</td>
                  <td className="num strong">{row.recipientCount.toLocaleString()}</td>
                  <td>
                    <div className="meter" title={`등록 ${row.claimedCount} / ${row.recipientCount}`}>
                      <span className="meter-track"><span style={{ width: `${rate}%` }} /></span>
                      <span className="meter-value">{row.recipientCount > 0 ? `${rate}%` : '–'}</span>
                    </div>
                  </td>
                  <td className="num">{row.viewCount.toLocaleString()}{row.maxViews > 0 ? ` / ${row.maxViews}` : ''}</td>
                </tr>
              );
            })}
            {shown.length === 0 && (
              <tr><td colSpan={8} className="console-none">
                {loading ? '링크를 불러오고 있어요.' : '조건에 맞는 링크가 없어요.'}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="console-foot">링크 이름을 누르면 들어갑니다. 최근에 만든 링크가 위에 놓입니다.</p>
    </>
  );
}
