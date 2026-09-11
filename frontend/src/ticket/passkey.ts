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
  options_: { direction?: 'IN' | 'OUT'; intent?: 'PRESENT' | 'TRANSFER'; toEmail?: string } = {},
): Promise<
  | { mode: 'register'; claimed: true; viaTransfer: boolean }
  | { mode: 'authenticate'; intent: 'PRESENT'; grant: Grant }
  | { mode: 'authenticate'; intent: 'TRANSFER'; transfer: TransferStarted }
> {
  const base = ticketBase(sessionId, token);
  const position = options_.intent === 'TRANSFER' ? {} : await coarsePosition();
  const { mode, intent, options } = await read(await mutate(`${base}/passkey/options`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...options_, ...position }),
  }));
  const publicKey = options.publicKey;
  publicKey.challenge = decode(publicKey.challenge);

  let credential: PublicKeyCredential | null;
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
  return intent === 'TRANSFER'
    ? { mode: 'authenticate', intent: 'TRANSFER', transfer: result as TransferStarted }
    : { mode: 'authenticate', intent: 'PRESENT', grant: result as Grant };
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
