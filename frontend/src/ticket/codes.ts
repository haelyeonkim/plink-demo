// Rotating presentation codes. One passkey ceremony yields a short-lived grant plus a
// secret; the phone signs a fresh single-use code from it every period, so the QR on
// screen is an expiring receipt rather than the entitlement itself.

export interface Grant {
  grantId: string;
  secret: string;
  direction: 'IN' | 'OUT';
  prefix: string;
  periodSeconds: number;
  expiresAt: string;
  serverTime: string;
}

const ALNUM = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function nonce(length = 12): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, b => ALNUM[b % ALNUM.length]).join('');
}

function hex(buffer: ArrayBuffer, bytes: number): string {
  return Array.from(new Uint8Array(buffer).slice(0, bytes))
    .map(b => b.toString(16).padStart(2, '0')).join('').toUpperCase();
}

export class CodeMinter {
  private key: Promise<CryptoKey>;
  /** Difference between the server clock and this device, measured when the grant was issued. */
  private readonly skewMs: number;

  constructor(private readonly grant: Grant) {
    this.key = crypto.subtle.importKey(
      'raw', new TextEncoder().encode(grant.secret),
      { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
    );
    this.skewMs = new Date(grant.serverTime).getTime() - Date.now();
  }

  get expiresAtMs(): number { return new Date(this.grant.expiresAt).getTime(); }
  get periodMs(): number { return this.grant.periodSeconds * 1000; }

  serverNowMs(): number { return Date.now() + this.skewMs; }

  secondsLeft(): number {
    return Math.max(0, Math.ceil((this.expiresAtMs - this.serverNowMs()) / 1000));
  }

  /** Seconds until the code on screen is replaced. */
  secondsToRotation(): number {
    const elapsed = this.serverNowMs() - new Date(this.grant.serverTime).getTime();
    return Math.max(0, Math.ceil((this.periodMs - (elapsed % this.periodMs)) / 1000));
  }

  async next(): Promise<string> {
    const nowMs = this.serverNowMs();
    const seconds = Math.floor(nowMs / 1000);
    const elapsed = nowMs - new Date(this.grant.serverTime).getTime();
    // Strictly increasing, so a screenshot dies the moment the next code is drawn.
    const counter = Math.max(1, Math.floor(elapsed / this.periodMs) + 1);
    const time = seconds.toString(36).toUpperCase();
    const random = nonce();
    const payload = `${this.grant.grantId}|${counter}|${time}|${random}`;
    const signature = await crypto.subtle.sign('HMAC', await this.key, new TextEncoder().encode(payload));
    const mac = hex(signature, 16);
    return [this.grant.prefix, this.grant.grantId, String(counter), time, random, mac].join('.');
  }
}
