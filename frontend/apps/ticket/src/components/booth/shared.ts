import type { BoothClient, BoothDashboard, CouponStatus } from '../../booth/api';

/** What every tab of a booth console is handed, on a manager's phone or the booth's terminal. */
export interface BoothTabProps {
  /** The manager's link or the booth's terminal: the tabs do not need to know which. */
  client: BoothClient;
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
