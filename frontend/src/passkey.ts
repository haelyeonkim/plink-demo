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

export function supportsPasskeys() {
  return window.isSecureContext && typeof PublicKeyCredential !== 'undefined' && !!navigator.credentials;
}

export async function openWithPasskey(code: string, password: string, viewerName: string): Promise<string> {
  const base = `/api/links/s/${encodeURIComponent(code)}/passkey`;
  const { mode, options } = await readResponse(await mutate(`${base}/options`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, viewerName }),
  }));
  const publicKey = options.publicKey;
  publicKey.challenge = decode(publicKey.challenge);
  let credential: PublicKeyCredential | null;
  if (mode === 'register') {
    publicKey.user.id = decode(publicKey.user.id);
    publicKey.excludeCredentials = (publicKey.excludeCredentials || []).map((c: { id: string }) => ({ ...c, id: decode(c.id) }));
    credential = await navigator.credentials.create({ publicKey }) as PublicKeyCredential | null;
  } else {
    publicKey.allowCredentials = (publicKey.allowCredentials || []).map((c: { id: string }) => ({ ...c, id: decode(c.id) }));
    credential = await navigator.credentials.get({ publicKey }) as PublicKeyCredential | null;
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
  return data.originalUrl;
}

export function passkeyError(error: unknown) {
  if (error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'AbortError')
      return '패스키 인증이 취소되었거나 사용할 패스키가 없어요. 처음 등록한 패스키로 다시 시도해 주세요.';
    if (error.name === 'NotSupportedError') return '이 브라우저에서는 패스키를 사용할 수 없어요. 최신 Safari 또는 Chrome에서 열어 주세요.';
    if (error.name === 'InvalidStateError') return '이미 등록된 패스키가 있어요. 페이지를 새로고침하고 다시 시도해 주세요.';
    return '이 환경에서 패스키를 사용할 수 없어요. 링크를 기본 브라우저에서 다시 열어 주세요.';
  }
  return error instanceof Error ? error.message : '패스키를 확인하지 못했어요. 다시 시도해 주세요.';
}
