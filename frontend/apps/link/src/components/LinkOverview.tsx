import { Link } from 'react-router-dom';
import type { LinkDetail } from '../types';
import { linkState, shortDate } from './LinkTable';

interface Todo { tone: 'warn' | 'info' | 'done'; title: string; detail: string; to: string; action: string }

function ago(value: string): string {
  const minutes = Math.floor((Date.now() - new Date(value).getTime()) / 60000);
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours}시간 전` : `${Math.floor(hours / 24)}일 전`;
}

/**
 * The first screen of a link: who it went to, who has registered and read it, and what
 * would stop it opening. Each line that needs doing links to the screen that does it.
 */
export default function LinkOverview({ detail }: { detail: LinkDetail }) {
  const issued = detail.recipients.length;
  const live = detail.recipients.filter(r => !r.revoked);
  const claimed = live.filter(r => r.claimed).length;
  const unclaimed = live.length - claimed;
  const state = linkState(detail);
  const daysLeft = detail.expiresAt
    ? Math.ceil((new Date(detail.expiresAt).getTime() - Date.now()) / 86400000) : null;
  const rate = live.length > 0 ? Math.round((claimed / live.length) * 100) : 0;

  const todos: Todo[] = [];
  if (issued === 0) todos.push({ tone: 'warn', title: '아직 발급한 수신자가 없어요',
    detail: '받을 사람의 이메일로 링크를 하나씩 발급하세요.', to: '?tab=issue', action: '발급하기' });
  if (state === 'expired') todos.push({ tone: 'warn', title: '만료된 링크예요',
    detail: '수신자가 더 이상 열 수 없어요. 만료 일시를 늦추면 다시 열려요.', to: '?tab=settings', action: '설정' });
  else if (daysLeft !== null && daysLeft <= 3) todos.push({ tone: 'warn', title: `만료까지 ${Math.max(daysLeft, 0)}일`,
    detail: `${shortDate(detail.expiresAt)}에 닫혀요.`, to: '?tab=settings', action: '만료 연장' });
  if (state === 'exhausted') todos.push({ tone: 'warn', title: '열람 한도에 도달했어요',
    detail: `최대 ${detail.maxViews}회를 모두 썼어요.`, to: '?tab=settings', action: '설정' });
  if (unclaimed > 0) todos.push({ tone: 'info', title: `아직 등록하지 않은 수신자 ${unclaimed}명`,
    detail: '링크를 열어 패스키를 등록해야 내용을 볼 수 있어요.', to: '?tab=links', action: '발급 현황' });
  if (issued > 0 && unclaimed === 0 && state === 'active') todos.push({ tone: 'done', title: '모든 수신자가 등록했어요',
    detail: `${claimed}명이 패스키로 열 수 있어요.`, to: '?tab=links', action: '발급 현황' });

  return (
    <div className="console-stack">
      <div className="kpi-grid">
        <div className="kpi">
          <span className="kpi-label">발급</span>
          <strong className="kpi-num">{issued.toLocaleString()}</strong>
          <span className="kpi-note">비활성 {(issued - live.length).toLocaleString()}개 포함</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">등록 완료</span>
          <strong className="kpi-num">{claimed.toLocaleString()}</strong>
          <span className="meter-track"><span style={{ width: `${rate}%` }} /></span>
          <span className="kpi-note">{rate}% · 미등록 {unclaimed.toLocaleString()}</span>
        </div>
        <div className="kpi">
          <span className="kpi-label">열람</span>
          <strong className="kpi-num">{detail.viewCount.toLocaleString()}
            {detail.maxViews > 0 && <small> / {detail.maxViews.toLocaleString()}</small>}</strong>
          <span className="kpi-note">{detail.views.length > 0 ? `마지막 ${ago(detail.views[0].viewedAt)}` : '아직 열람 없음'}</span>
        </div>
        <div className={`kpi${state !== 'active' || (daysLeft !== null && daysLeft <= 3) ? ' kpi-warn' : ''}`}>
          <span className="kpi-label">만료</span>
          <strong className="kpi-num kpi-text">{detail.expiresAt ? shortDate(detail.expiresAt) : '없음'}</strong>
          <span className="kpi-note">{state === 'expired' ? '만료됨' : daysLeft !== null ? `${Math.max(daysLeft, 0)}일 남음`
            : detail.hasPassword ? '비밀번호 보호' : '기한 없이 열려요'}</span>
        </div>
      </div>

      <div className="console-two">
        <section className="console-card" aria-labelledby="link-todo">
          <h3 id="link-todo">확인할 일</h3>
          {todos.length === 0 ? <p className="console-none">지금 확인할 일이 없어요.</p> : (
            <ul className="todo-list">
              {todos.map(todo => (
                <li key={todo.title} className={`todo todo-${todo.tone}`}>
                  <span className="todo-mark" aria-hidden="true">{todo.tone === 'done' ? '✓' : todo.tone === 'warn' ? '!' : 'i'}</span>
                  <span className="todo-text">
                    <span className="visually-hidden">{todo.tone === 'done' ? '완료: ' : todo.tone === 'warn' ? '확인 필요: ' : '안내: '}</span>
                    <b>{todo.title}</b><small>{todo.detail}</small>
                  </span>
                  <Link to={todo.to}>{todo.action}</Link>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="console-card" aria-labelledby="link-recent">
          <div className="console-card-head">
            <h3 id="link-recent">최근 열람</h3>
            <Link to="?tab=views">전체 기록</Link>
          </div>
          {detail.views.length === 0 ? <p className="console-none">아직 열람 기록이 없어요.</p> : (
            <ul className="timeline">
              {detail.views.slice(0, 6).map((view, index) => {
                const opened = view.eventType === 'INITIAL_OPEN';
                return (
                  <li key={index}>
                    <span className="muted">{ago(view.viewedAt)}</span>
                    <span className={`pill pill-${opened ? 'wait' : 'ok'}`}>{opened ? '링크 열림' : '패스키 인증'}</span>
                    <span>{view.viewerName || '수신자'}</span>
                  </li>
                );
              })}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
