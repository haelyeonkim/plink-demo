import { mutate } from './auth';

function decode(value: string): ArrayBuffer {
  const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, c => c.charCodeAt(0)).buffer;
}
function encode(value: ArrayBuffer): string {
  let raw = '';
  new Uint8Array(value).forEach(b => { raw += String.fromCharCode(b); });
  return btoa(raw).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
async function readResponse(res: Response) {
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '요청을 처리하지 못했어요. 다시 시도해 주세요.');
  return data;
}

/**
 * A ceremony that the browser refused, carrying which ceremony it was.
 *
 * <p>The platform reports a cancelled sheet, a missing screen lock and an authenticator
 * that cannot store the credential all as one NotAllowedError, so the advice has to come
 * from what we were asking for - telling a first-time recipient to "use the passkey you
 * registered" sends them looking for something that does not exist.
 */
export class PasskeyCeremonyError extends Error {
  readonly mode: 'register' | 'authenticate';
  readonly reason: unknown;

  constructor(mode: 'register' | 'authenticate', reason: unknown) {
    super(reason instanceof Error ? reason.message : String(reason));
    this.name = 'PasskeyCeremonyError';
    this.mode = mode;
    this.reason = reason;
  }
}

export function supportsPasskeys() {
  return window.isSecureContext && typeof PublicKeyCredential !== 'undefined' && !!navigator.credentials;
}

/** What the link opens: an address elsewhere, or a document written by the sender. */
export interface Opened {
  originalUrl: string | null;
  contentTitle?: string;
  content?: { intro?: string; columns?: '1' | '2'; artworks?: Array<Record<string, string>> };
}

export async function openWithPasskey(code: string, password: string, slug?: string,
    contact?: string): Promise<Opened> {
  const base = slug
    ? `/api/links/s/${encodeURIComponent(slug)}/${encodeURIComponent(code)}/passkey`
    : `/api/links/s/${encodeURIComponent(code)}/passkey`;
  const { mode, options } = await readResponse(await mutate(`${base}/options`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, contact }),
  }));
  const publicKey = options.publicKey;
  publicKey.challenge = decode(publicKey.challenge);
  let credential: PublicKeyCredential | null;
  try {
    if (mode === 'register') {
      publicKey.user.id = decode(publicKey.user.id);
      publicKey.excludeCredentials = (publicKey.excludeCredentials || []).map((c: { id: string }) => ({ ...c, id: decode(c.id) }));
      credential = await navigator.credentials.create({ publicKey }) as PublicKeyCredential | null;
    } else {
      publicKey.allowCredentials = (publicKey.allowCredentials || []).map((c: { id: string }) => ({ ...c, id: decode(c.id) }));
      credential = await navigator.credentials.get({ publicKey }) as PublicKeyCredential | null;
    }
  } catch (err) {
    throw new PasskeyCeremonyError(mode === 'register' ? 'register' : 'authenticate', err);
  }
  if (!credential) throw new Error('패스키 인증이 취소되었어요. 준비되면 다시 눌러 주세요.');
  let response;
  if (mode === 'register') {
    const r = credential.response as AuthenticatorAttestationResponse;
    response = { clientDataJSON: encode(r.clientDataJSON), attestationObject: encode(r.attestationObject),
      transports: typeof r.getTransports === 'function' ? r.getTransports() : [] };
  } else {
    const r = credential.response as AuthenticatorAssertionResponse;
    response = { clientDataJSON: encode(r.clientDataJSON), authenticatorData: encode(r.authenticatorData),
      signature: encode(r.signature), userHandle: r.userHandle ? encode(r.userHandle) : null };
  }
  const data = await readResponse(await mutate(`${base}/finish`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: credential.id, rawId: encode(credential.rawId), type: credential.type,
      response, clientExtensionResults: credential.getClientExtensionResults() }),
  }));
  return data as Opened;
}

export function passkeyError(error: unknown) {
  const mode = error instanceof PasskeyCeremonyError ? error.mode : null;
  const cause = error instanceof PasskeyCeremonyError ? error.reason : error;
  if (cause instanceof DOMException) {
    // The name is appended because these three failures are indistinguishable to the
    // page, and it is the one thing that makes a report actionable.
    const detail = ` (오류: ${cause.name})`;
    if (cause.name === 'NotAllowedError' || cause.name === 'AbortError') {
      return (mode === 'register'
        ? '패스키를 만들지 못했어요. 창을 닫았다면 다시 눌러 주시고, 계속 실패하면 기기의 화면 잠금(지문·얼굴·PIN)과 '
          + 'iCloud 키체인 또는 Google 비밀번호 관리자가 켜져 있는지 확인해 주세요.'
        : '패스키 인증이 취소되었거나 사용할 패스키가 없어요. 처음 등록한 패스키로 다시 시도해 주세요.') + detail;
    }
    if (cause.name === 'NotSupportedError') {
      return '이 브라우저에서는 패스키를 사용할 수 없어요. 최신 Safari 또는 Chrome에서 열어 주세요.' + detail;
    }
    if (cause.name === 'InvalidStateError') {
      return '이 기기에 이미 등록된 패스키가 있어요. 페이지를 새로고침하고 다시 시도해 주세요.' + detail;
    }
    if (cause.name === 'SecurityError') {
      return '주소가 올바르지 않아 패스키를 쓸 수 없어요. 전달받은 주소를 그대로 열어 주세요.' + detail;
    }
    return '이 환경에서 패스키를 사용할 수 없어요. 링크를 기본 브라우저에서 다시 열어 주세요.' + detail;
  }
  return cause instanceof Error ? cause.message : '패스키를 확인하지 못했어요. 다시 시도해 주세요.';
}
