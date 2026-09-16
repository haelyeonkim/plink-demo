import { mutate } from './auth';
import type { ProtectedLink, LinkDetail, AccessInfo, CreateLinkRequest, LinkRecipient } from './types';

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

/** Issues one more address under a link, to one more person. */
export async function issueRecipient(linkId: number, email: string, label: string, notify: boolean)
    : Promise<LinkRecipient> {
  const res = await mutate(`${BASE}/${linkId}/recipients`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, label, notify }),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || '수신자를 발급하지 못했습니다.');
  }
  return res.json();
}

export async function setRecipientRevoked(linkId: number, recipientId: number, revoked: boolean)
    : Promise<LinkRecipient> {
  const res = await mutate(`${BASE}/${linkId}/recipients/${recipientId}/status`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ revoked }),
  });
  if (!res.ok) throw new Error('수신자 상태를 바꾸지 못했습니다.');
  return res.json();
}

export async function deleteRecipient(linkId: number, recipientId: number): Promise<void> {
  const res = await mutate(`${BASE}/${linkId}/recipients/${recipientId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error('수신자를 삭제하지 못했습니다.');
}

export async function accessLink(shortCode: string, slug?: string): Promise<AccessInfo> {
  const res = await fetch(slug ? `${BASE}/s/${slug}/${shortCode}` : `${BASE}/s/${shortCode}`);
  if (!res.ok) throw new Error('Link not found');
  return res.json();
}
