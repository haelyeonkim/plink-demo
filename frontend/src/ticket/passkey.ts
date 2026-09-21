import { mutate } from '../auth';
import { ticketBase } from './api';
import type { Grant } from './codes';

// WebAuthn wire values are base64url; the browser wants ArrayBuffers.
function decode(value: string): ArrayBuffer {
  const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, c => c.charCodeAt(0)).buffer;
}
function encode(value: ArrayBuffer): string {
  let raw = '';
  new Uint8Array(value).forEach(b => { raw += String.fromCharCode(b); });
  return btoa(raw).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function read(response: Response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || '요청을 처리하지 못했어요. 다시 시도해 주세요.');
  return data;
}

export function supportsPasskeys(): boolean {
  return window.isSecureContext && typeof PublicKeyCredential !== 'undefined' && !!navigator.credentials;
}

export interface TransferStarted { status: string; toEmail: string; expiresAt: string }

/**
 * Where the ceremony has got to, for a screen that wants to show it.
 *
 * <p>These are the three things that actually happen, in order: the server hands out a
 * challenge, the device proves who holds the key, and the server checks the signature.
 * A screen that narrates them is describing the real work rather than stalling politely.
 */
export type CeremonyStage = 'preparing' | 'signing' | 'verifying';

/**
 * Best-effort coarse position, used only to bind a presentation to the venue. It never
 * blocks: a refusal or a slow fix simply leaves the reading out, and the server treats
 * an absent reading as unknown rather than as a failure.
 */
async function coarsePosition(): Promise<Record<string, string>> {
  if (!navigator.geolocation) return {};
  return new Promise(resolve => {
    const done = (value: Record<string, string>) => resolve(value);
    const timer = window.setTimeout(() => done({}), 4000);
    navigator.geolocation.getCurrentPosition(
      position => {
        window.clearTimeout(timer);
        done({
          lat: String(position.coords.latitude),
          lon: String(position.coords.longitude),
          accuracy: String(position.coords.accuracy),
        });
      },
      () => { window.clearTimeout(timer); done({}); },
      { enableHighAccuracy: false, timeout: 3500, maximumAge: 60_000 },
    );
  });
}

/**
 * Runs the ceremony for a ticket. With no passkey yet this registers one and binds the
 * ticket; afterwards it authenticates, and what the server does with that assertion
 * depends on the intent — open a presentation grant, or sign off a transfer.
 */
export async function runCeremony(
  sessionId: string, token: string,
  options_: {
    direction?: 'IN' | 'OUT'; intent?: 'PRESENT' | 'TRANSFER'; toEmail?: string;
    /** The address the ticket was issued to, typed back when no code was required. */
    email?: string;
    /**
     * Told where the ceremony has got to. A callback that returns a promise is waited
     * for, so a screen narrating the steps can hold the authenticator back until the
     * step that says "your device is about to ask you" is the one on screen.
     */
    onStage?: (stage: CeremonyStage) => void | Promise<void>;
  } = {},
): Promise<
  | { mode: 'register'; claimed: true; viaTransfer: boolean }
  | { mode: 'authenticate'; intent: 'PRESENT'; grant: Grant }
  | { mode: 'authenticate'; intent: 'TRANSFER'; transfer: TransferStarted }
  | { mode: 'authenticate'; intent: 'CLAIM'; claimed: true; viaTransfer: boolean }
> {
  const base = ticketBase(sessionId, token);
  const stage = async (step: CeremonyStage) => { await options_.onStage?.(step); };
  await stage('preparing');
  const position = options_.intent === 'TRANSFER' ? {} : await coarsePosition();
  const { mode, intent, options } = await read(await mutate(`${base}/passkey/options`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...options_, ...position }),
  }));
  const publicKey = options.publicKey;
  publicKey.challenge = decode(publicKey.challenge);

  let credential: PublicKeyCredential | null;
  // The prompt belongs to this step, not to the one before it: the screen says the
  // device is about to ask, and then the device asks.
  await stage('signing');
  if (mode === 'register') {
    publicKey.user.id = decode(publicKey.user.id);
    publicKey.excludeCredentials = (publicKey.excludeCredentials || [])
      .map((c: { id: string }) => ({ ...c, id: decode(c.id) }));
    credential = await navigator.credentials.create({ publicKey }) as PublicKeyCredential | null;
  } else {
    publicKey.allowCredentials = (publicKey.allowCredentials || [])
      .map((c: { id: string }) => ({ ...c, id: decode(c.id) }));
    credential = await navigator.credentials.get({ publicKey }) as PublicKeyCredential | null;
  }
  if (!credential) throw new Error('인증이 취소되었어요. 준비되면 다시 눌러 주세요.');

  let payload;
  if (mode === 'register') {
    const r = credential.response as AuthenticatorAttestationResponse;
    payload = {
      clientDataJSON: encode(r.clientDataJSON),
      attestationObject: encode(r.attestationObject),
      transports: typeof r.getTransports === 'function' ? r.getTransports() : [],
    };
  } else {
    const r = credential.response as AuthenticatorAssertionResponse;
    payload = {
      clientDataJSON: encode(r.clientDataJSON),
      authenticatorData: encode(r.authenticatorData),
      signature: encode(r.signature),
      userHandle: r.userHandle ? encode(r.userHandle) : null,
    };
  }
  await stage('verifying');
  const result = await read(await mutate(`${base}/passkey/finish`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      id: credential.id, rawId: encode(credential.rawId), type: credential.type,
      response: payload, clientExtensionResults: credential.getClientExtensionResults(),
    }),
  }));
  if (mode === 'register') {
    return { mode: 'register', claimed: true, viaTransfer: Boolean(result.viaTransfer) };
  }
  if (intent === 'TRANSFER') {
    return { mode: 'authenticate', intent: 'TRANSFER', transfer: result as TransferStarted };
  }
  if (intent === 'CLAIM') {
    // A person who already has a passkey attaches a new ticket by proving it, so a
    // claim can finish through an assertion rather than a registration.
    return { mode: 'authenticate', intent: 'CLAIM', claimed: true,
             viaTransfer: Boolean(result.viaTransfer) };
  }
  return { mode: 'authenticate', intent: 'PRESENT', grant: result as Grant };
}

export function passkeyError(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'AbortError') {
      return '인증이 취소되었어요. 다시 시도해 주세요.';
    }
    if (error.name === 'NotSupportedError') {
      return '이 브라우저에서는 패스키를 사용할 수 없어요. 최신 Safari 또는 Chrome에서 열어 주세요.';
    }
    if (error.name === 'InvalidStateError') {
      return '이미 등록된 패스키가 있어요. 페이지를 새로 고치고 다시 시도해 주세요.';
    }
    return '이 환경에서는 패스키를 사용할 수 없어요. 링크를 기본 브라우저에서 열어 주세요.';
  }
  return error instanceof Error ? error.message : '인증을 확인하지 못했어요. 다시 시도해 주세요.';
}
