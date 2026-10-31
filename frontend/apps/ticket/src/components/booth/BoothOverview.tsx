import { useState } from 'react';
import { clock } from '../../console/model';
import { COUPON_STATE, type BoothTabProps } from './shared';
import BoothTerminal from './BoothTerminal';

/** The booth at a glance: who is around, and how far each of its coupons has gone. */
export default function BoothOverview({ client, dashboard, refresh, fail, onGo }: BoothTabProps & {
  onGo: (tab: 'offers' | 'issue' | 'scan') => void;
}) {
  const [loading, setLoading] = useState(false);
  const sum = (key: 'issued' | 'redeemed') => dashboard.offers.reduce((total, offer) => total + offer[key], 0);
  const issued = sum('issued');
  const redeemed = sum('redeemed');

  async function reload() {
    setLoading(true);
    try { await refresh(); } finally { setLoading(false); }
  }

  return (
    <div className="bm-stack">
      {/* Only a manager who answers for a terminal has one; a terminal is already itself. */}
      {dashboard.terminal && <BoothTerminal client={client} terminal={dashboard.terminal} fail={fail} />}
      <div className="bm-kpis">
        <div className="kpi"><span className="kpi-label">입장권</span><span className="kpi-num">{dashboard.liveTickets.toLocaleString()}</span></div>
        <div className="kpi"><span className="kpi-label">지금 장내</span><span className="kpi-num">{dashboard.inside.toLocaleString()}</span></div>
        <div className="kpi"><span className="kpi-label">지급한 쿠폰</span><span className="kpi-num">{issued.toLocaleString()}</span></div>
        <div className="kpi"><span className="kpi-label">사용된 쿠폰</span><span className="kpi-num">{redeemed.toLocaleString()}</span></div>
      </div>

      <section className="console-card">
        <div className="console-card-head">
          <h3>쿠폰 종류</h3>
          <button type="button" className="btn-tiny" onClick={() => onGo('offers')}>관리</button>
        </div>
        {dashboard.offers.length === 0 ? (
          <p className="console-none">
            아직 쿠폰 종류가 없어요. <b>쿠폰 종류</b>에서 첫 쿠폰을 만들어 주세요.
          </p>
        ) : (
          <ul className="bm-list">
            {dashboard.offers.map(offer => (
              <li key={offer.offerId}>
                <span className="bm-row-main">
                  <b>{offer.title}</b>
                  {!offer.active && <span className="pill pill-off">꺼짐</span>}
                </span>
                <small>
                  지급 {offer.issued.toLocaleString()} · 사용 {offer.redeemed.toLocaleString()}
                  {' '}· 가진 사람 {offer.holders.toLocaleString()}
                  {offer.voided > 0 ? ` · 무효 ${offer.voided.toLocaleString()}` : ''}
                </small>
              </li>
            ))}
          </ul>
        )}
      </section>

      <div className="bm-actions">
        <button type="button" className="btn-primary" onClick={() => onGo('scan')}>QR 스캔하기</button>
        <button type="button" className="btn-secondary" onClick={() => onGo('issue')}>쿠폰 발급하기</button>
      </div>

      <section className="console-card">
        <div className="console-card-head">
          <h3>최근 기록</h3>
          <button type="button" className="btn-tiny" disabled={loading} onClick={() => void reload()}>
            {loading ? '읽는 중…' : '새로 고침'}
          </button>
        </div>
        {dashboard.recent.length === 0 ? <p className="console-none">아직 기록이 없어요.</p> : (
          <ul className="bm-list">
            {dashboard.recent.map(row => {
              const [label, tone] = COUPON_STATE[row.status] ?? [row.status, 'wait'];
              return (
                <li key={`${row.couponId}-${row.status}`}>
                  <span className="bm-row-main">
                    <b>{row.title}</b>
                    <span className={`pill pill-${tone}`}>{label}</span>
                  </span>
                  <small>{row.ticketRef}{row.at ? ` · ${clock(row.at)}` : ''}</small>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
