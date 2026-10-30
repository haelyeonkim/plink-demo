import type { BoothDashboard, CouponStatus } from '../../booth/api';

/** What every tab of a booth manager's console is handed. */
export interface BoothTabProps {
  token: string;
  dashboard: BoothDashboard;
  /** Reads the dashboard again, after something changed what it counts. */
  refresh: () => Promise<void>;
  /**
   * Where every failed request goes. A lapsed sign-in or a revoked manager is not an
   * error to show under a button: the page itself changes, so only the console decides.
   */
  fail: (error: unknown) => void;
  notify: (text: string) => void;
}

export const COUPON_STATE: Record<CouponStatus, [string, string]> = {
  ISSUED: ['미사용', 'wait'], REDEEMED: ['사용함', 'ok'], VOID: ['무효', 'off'],
};
