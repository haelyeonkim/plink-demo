import { useCallback, useEffect, useRef, useState } from 'react';
import { scanCode, type ScanAction } from '../../booth/api';
import { ApiError } from '../../ticket/api';
import { useQrReader } from '../../ticket/qr';
import { couponVerdict, grantVerdict } from '../../ticket/verdicts';
import { clockText } from '../../motion';
import GateVerdict, { type Verdict } from '../GateVerdict';
import type { BoothTabProps } from './shared';

const CAMERA = 'plink.booth.camera';
/** How long an answer holds the camera's frame; the reader rests meanwhile. */
const HOLD_MS = 2600;

/**
 * The phone as the booth's reader: point it at a holder's QR and either give them one of
 * the booth's coupons, or use up the coupon they are showing.
 *
 * <p>The camera runs only while this tab is open. A read is sent once; the same code
 * held in front of the lens is not sent again, and nothing is read while an answer is up.
 */
export default function BoothScan({ token, dashboard, refresh, fail }: BoothTabProps) {
  const offers = dashboard.offers.filter(offer => offer.active);
  const [action, setAction] = useState<ScanAction>('REDEEM');
  const [offerId, setOfferId] = useState<number | null>(offers[0]?.offerId ?? null);
  const [on, setOn] = useState(true);
  const [facing, setFacing] = useState<'user' | 'environment'>(() => {
    try { return localStorage.getItem(CAMERA) === 'user' ? 'user' : 'environment'; } catch { return 'environment'; }
  });
  const [flash, setFlash] = useState<Verdict | null>(null);
  const [recent, setRecent] = useState<Verdict[]>([]);
  const video = useRef<HTMLVideoElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const inFlight = useRef(false);
  const restUntil = useRef(0);

  useEffect(() => {
    if (offerId !== null && !offers.some(offer => offer.offerId === offerId)) setOfferId(offers[0]?.offerId ?? null);
  }, [offerId, offers]);

  const giving = action === 'GIVE';
  const offer = offers.find(row => row.offerId === offerId) ?? null;
  // Giving with nothing to give would only produce refusals.
  const canRead = !giving || offer !== null;

  const announce = useCallback((verdict: Omit<Verdict, 'at'>) => {
    const settled = { ...verdict, at: Date.now() };
    restUntil.current = settled.at + HOLD_MS;
    setFlash(settled);
    setRecent(current => [settled, ...current].slice(0, 12));
  }, []);

  useEffect(() => {
    if (!flash) return;
    const timer = window.setTimeout(() => setFlash(null), HOLD_MS);
    return () => window.clearTimeout(timer);
  }, [flash]);

  const onCode = useCallback(async (code: string) => {
    if (inFlight.current || Date.now() < restUntil.current) return;
    if (giving && !offer) return;
    inFlight.current = true;
    try {
      const result = await scanCode(token, code, action, giving ? offer!.offerId : undefined);
      const shown = giving ? grantVerdict({ title: offer!.title, ...result }) : couponVerdict(result);
      announce({
        ...shown,
        // The server's own sentence, when it has one, says it better than a reconstruction.
        detail: [shown.detail, typeof result.message === 'string' ? result.message : ''].filter(Boolean).join(' · '),
        ledger: `${giving ? '지급' : shown.tone === 'repeat' ? '이미 사용' : '사용'} ${clockText()}`,
      });
      void refresh();
    } catch (err) {
      if (err instanceof ApiError && (err.status === 401 || err.status === 410)) { fail(err); return; }
      announce({
        tone: 'deny', headline: giving ? '지급 못 함' : '사용 못 함',
        detail: err instanceof TypeError
          ? '서버에 연결되지 않았어요. 연결을 확인하고 다시 비춰 주세요.'
          : err instanceof Error ? err.message : '확인할 수 없는 코드예요.',
        ledger: `거부 ${clockText()}`,
      });
    } finally {
      // Held briefly so the same hand is not read twice while the answer appears.
      window.setTimeout(() => { inFlight.current = false; }, 800);
    }
  }, [token, action, giving, offer, announce, refresh, fail]);

  const { error, running } = useQrReader({ active: on && canRead, facing, video, canvas, onCode });

  function flip() {
    setFacing(current => {
      const next = current === 'user' ? 'environment' : 'user';
      try { localStorage.setItem(CAMERA, next); } catch { /* remembered only when the browser allows it */ }
      return next;
    });
  }

  return (
    <div className="bm-stack">
      <div className="bm-mode" role="radiogroup" aria-label="스캔해서 할 일">
        {([['REDEEM', '사용 처리'], ['GIVE', '지급']] as Array<[ScanAction, string]>).map(([key, label]) => (
          <button key={key} type="button" role="radio" aria-checked={action === key}
            className={action === key ? 'on' : ''} onClick={() => { setAction(key); setFlash(null); }}>{label}</button>
        ))}
      </div>
      {giving ? (
        offers.length === 0 ? <p className="console-none">발급 중인 쿠폰이 없어요. 쿠폰 종류에서 먼저 만들어 주세요.</p> : (
          <div className="field">
            <label htmlFor="bm-scan-offer">줄 쿠폰</label>
            <select id="bm-scan-offer" value={offerId ?? ''} onChange={e => setOfferId(Number(e.target.value))}>
              {offers.map(row => <option key={row.offerId} value={row.offerId}>{row.title}</option>)}
            </select>
          </div>
        )
      ) : null}
      <p className="hint-text bm-scan-hint">
        {giving
          ? '관람객 입장권의 QR을 비추면 고른 쿠폰을 1장 줘요.'
          : '관람객이 보여 주는 쿠폰 QR을 비추면 그 쿠폰을 사용 처리해요.'}
      </p>

      <div className="bm-camera">
        <video ref={video} playsInline muted aria-label="카메라 화면" />
        <canvas ref={canvas} hidden />
        {!running && !error && (
          <p className="bm-camera-idle">{!on ? '카메라가 꺼져 있어요.' : canRead ? '카메라를 여는 중…' : '줄 쿠폰을 먼저 골라 주세요.'}</p>
        )}
        {error && <p className="bm-camera-idle" role="alert">{error}</p>}
        {flash && <GateVerdict key={flash.at} verdict={flash} rest={HOLD_MS} />}
      </div>
      <div className="bm-actions">
        <button type="button" className="btn-secondary" onClick={() => setOn(value => !value)}>
          {on ? '카메라 끄기' : '카메라 켜기'}</button>
        <button type="button" className="btn-secondary" onClick={flip} disabled={!on}>카메라 전환</button>
      </div>

      <section className="console-card">
        <h3>방금 처리한 것</h3>
        {recent.length === 0 ? <p className="console-none">아직 없어요.</p> : (
          <ul className="bm-list">
            {recent.map(row => (
              <li key={row.at} className={`bm-result verdict-${row.tone}`}>
                <span className="bm-row-main"><b>{row.headline}</b>{row.mark && <small>{row.mark}</small>}</span>
                <small>{row.detail}{row.detail ? ' · ' : ''}{clockText(row.at)}</small>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
