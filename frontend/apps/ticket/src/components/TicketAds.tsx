import { useCallback, useEffect, useState } from 'react';
import { AD_CATEGORY, adImageUrl, fetchAd, fetchAds, type AdPost } from '../ticket/api';

type Filter = 'ALL' | 'SALE' | 'PROGRAM' | 'NOTICE';
const FILTERS: Array<[Filter, string]> = [['ALL', '전체'], ['SALE', '할인·이벤트'], ['PROGRAM', '체험·프로그램'], ['NOTICE', '운영 공지']];

function hm(value: string): string {
  const d = new Date(value);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** "~15:00", or "종일" for a notice with no end. */
function until(ad: AdPost): string {
  return ad.endsAt ? `~${hm(ad.endsAt)}` : '종일';
}

/** The event's board: booth and organiser notices, the organiser's pinned first. */
export default function TicketAds({ sessionId, token, tick, seenId, onSeen, onCoupons }: {
  sessionId: string; token: string; tick: number; seenId: number;
  onSeen: (latestId: number) => void; onCoupons: () => void;
}) {
  const [ads, setAds] = useState<AdPost[] | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [open, setOpen] = useState<AdPost | null>(null);
  const [error, setError] = useState('');
  // What counted as seen when the board was opened: marking it read must not wipe the
  // "새 글" labels off the list the holder is looking at.
  const [baseline] = useState(seenId);

  const load = useCallback(async () => {
    try {
      const next = await fetchAds(sessionId, token);
      setAds(next);
      setError('');
      if (next.length > 0) onSeen(Math.max(...next.map(ad => ad.id)));
    } catch (err) { setError(err instanceof Error ? err.message : '알림판을 불러오지 못했어요.'); }
  }, [sessionId, token, onSeen]);
  useEffect(() => { void load(); }, [load, tick]);

  async function show(ad: AdPost) {
    try { setOpen(await fetchAd(sessionId, token, ad.id)); window.scrollTo(0, 0); }
    catch (err) { setError(err instanceof Error ? err.message : '글을 열지 못했어요.'); }
  }

  if (open) {
    return (
      <article className="ad-detail">
        <button type="button" className="ad-back" onClick={() => setOpen(null)}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M15 18l-6-6 6-6" /></svg>
          알림판</button>
        {open.imageId && <img className="ad-photo" src={adImageUrl(sessionId, token, open)!} alt={`${open.booth} 사진`} />}
        <div className="ad-tags">
          <span className={`ad-tag ${open.category.toLowerCase()}`}>{AD_CATEGORY[open.category]}</span>
          <span className="muted">{hm(open.createdAt)} 올림 · 조회 {open.views.toLocaleString()}</span>
        </div>
        <h2>{open.title}</h2>
        <div className="ad-booth">
          <span className={`ad-thumb ${open.category.toLowerCase()}`} aria-hidden="true">{open.booth.slice(0, 1)}</span>
          <span><b>{open.booth}</b><small>{[open.place, open.endsAt ? `${hm(open.endsAt)}까지` : '종일'].filter(Boolean).join(' · ')}</small></span>
        </div>
        <p className="ad-body">{open.body}</p>
        {open.boothId !== null && (
          <button type="button" className="btn-secondary" onClick={onCoupons}>내 쿠폰 보기</button>
        )}
      </article>
    );
  }

  const shown = (ads ?? []).filter(ad => filter === 'ALL' || ad.category === filter);
  return (
    <div className="ad-board">
      <header className="tab-head">
        <h2>알림판</h2>
        <span>부스·운영팀 소식 {(ads ?? []).length}건</span>
      </header>
      <div className="chip-row" role="group" aria-label="분류">
        {FILTERS.map(([key, label]) => (
          <button key={key} type="button" aria-pressed={filter === key} className={filter === key ? 'on' : ''}
            onClick={() => setFilter(key)}>{label}</button>
        ))}
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}
      {ads === null ? <p className="console-none">불러오고 있어요.</p> : shown.length === 0 ? (
        <p className="console-none">{ads.length === 0 ? '아직 올라온 소식이 없어요.' : '이 분류의 소식이 없어요.'}</p>
      ) : (
        <ul className="ad-list">
          {shown.map(ad => (
            <li key={ad.id}>
              <button type="button" className={ad.pinned ? 'pinned' : ''} onClick={() => void show(ad)}>
                {ad.imageId
                  ? <img className="ad-thumb" src={adImageUrl(sessionId, token, ad)!} alt="" loading="lazy" />
                  : <span className={`ad-thumb ${ad.category.toLowerCase()}`} aria-hidden="true">{ad.booth.slice(0, 1)}</span>}
                <span className="ad-text">
                  <span className="ad-tags">
                    {ad.pinned && <span className="ad-tag pin">고정</span>}
                    <span className={`ad-tag ${ad.category.toLowerCase()}`}>{AD_CATEGORY[ad.category]}</span>
                    {ad.id > baseline && <span className="ad-tag fresh">새 글</span>}
                  </span>
                  <b>{ad.title}</b>
                  <small>{[ad.booth, ad.place, until(ad)].filter(Boolean).join(' · ')}</small>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
