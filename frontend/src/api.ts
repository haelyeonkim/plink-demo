import { mutate } from './auth';
import type { ProtectedLink, LinkDetail, AccessInfo, CreateLinkRequest } from './types';

const BASE = '/api/links';

export async function fetchLinks(): Promise<ProtectedLink[]> {
  const res = await fetch(BASE);
  if (!res.ok) throw new Error('Failed to fetch links');
  return res.json();
}

export async function fetchLink(id: number): Promise<LinkDetail> {
  const res = await fetch(`${BASE}/${id}`);
  if (!res.ok) throw new Error('Link not found');
  return res.json();
}

export async function createLink(req: CreateLinkRequest): Promise<ProtectedLink> {
  const res = await mutate(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error('Failed to create link');
  return res.json();
}

export async function deleteLink(id: number): Promise<void> {
  const res = await mutate(`${BASE}/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error('Failed to delete link');
}

export async function createLinks(req: CreateLinkRequest & { recipients: string[] }): Promise<ProtectedLink[]> {
  const res = await mutate(`${BASE}/batch`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(req),
  });
  if (!res.ok) {
    const details = await res.json().catch(() => null);
    const messages: Record<number, string> = {
      400: '입력하지 않은 값이나 형식이 올바르지 않은 값을 확인해 주세요.',
      401: '계정 로그인이 만료되었습니다. 다시 로그인해 주세요.',
      403: '계정 권한 또는 보안 확인에 실패했습니다. 다시 로그인한 뒤 시도해 주세요.',
      404: '요청한 기능을 찾을 수 없습니다. 관리자에게 문의해 주세요.',
      405: '현재 요청을 처리할 수 없습니다. 관리자에게 문의해 주세요.',
      500: '서버 오류가 발생했습니다. 계속되면 관리자에게 문의해 주세요.',
      502: '네트워크가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
      503: '서버 연결이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
    };
    // ApiErrors returns a single safe error message; do not expose framework error details.
    const reason = details && typeof details.error === 'string'
      ? details.error : messages[res.status] || '링크 생성 요청을 처리하지 못했습니다.';
    throw new Error(reason);
  }
  return res.json();
}

export async function accessLink(shortCode: string): Promise<AccessInfo> {
  const res = await fetch(`${BASE}/s/${shortCode}`);
  if (!res.ok) throw new Error('Link not found');
  return res.json();
}
