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
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error('Failed to create link');
  return res.json();
}

export async function deleteLink(id: number): Promise<void> {
  await fetch(`${BASE}/${id}`, { method: 'DELETE' });
}

export async function accessLink(shortCode: string): Promise<AccessInfo> {
  const res = await fetch(`${BASE}/s/${shortCode}`);
  if (!res.ok) throw new Error('Link not found');
  return res.json();
}

export async function verifyLink(
  shortCode: string,
  password: string,
  viewerName: string,
): Promise<string> {
  const res = await fetch(`${BASE}/s/${shortCode}/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password, viewerName }),
  });
  if (!res.ok) throw new Error('Access denied');
  const data = await res.json();
  return data.originalUrl;
}
