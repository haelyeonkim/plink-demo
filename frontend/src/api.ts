import { mutate } from './auth';
import type {
  ProtectedLink, LinkDetail, AccessInfo, CreateLinkRequest, LinkRecipient,
  ContentSummary, ContentDocument, ExhibitionBody, ContentImportResult, ArtworkRecord,
  ArtworkDeliveryRequest, ArtworkDelivery,
} from './types';

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

/** Documents written here, which a protected link can point at. */
export async function fetchContents(): Promise<ContentSummary[]> {
  const res = await fetch('/api/contents');
  if (!res.ok) throw new Error('컨텐츠를 불러오지 못했어요.');
  return res.json();
}

export async function fetchContent(id: number): Promise<ContentDocument> {
  const res = await fetch(`/api/contents/${id}`);
  if (!res.ok) throw new Error('컨텐츠를 불러오지 못했어요.');
  return res.json();
}

export async function saveContent(
  id: number | null, title: string, body: ExhibitionBody,
): Promise<ContentDocument> {
  const res = await mutate(id ? `/api/contents/${id}` : '/api/contents', {
    method: id ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, body }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '컨텐츠를 저장하지 못했어요.');
  return data;
}

export async function deleteContent(id: number): Promise<void> {
  const res = await mutate(`/api/contents/${id}`, { method: 'DELETE' });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.error || '컨텐츠를 삭제하지 못했어요.');
  }
}

export async function importContentUrl(url: string): Promise<ContentImportResult> {
  const res = await mutate('/api/contents/import/url', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '웹페이지에서 작품을 가져오지 못했어요.');
  return data;
}

export async function importContentPdf(file: File): Promise<ContentImportResult> {
  const body = new FormData();
  body.append('file', file);
  const res = await mutate('/api/contents/import/pdf', { method: 'POST', body });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'PDF에서 작품을 가져오지 못했어요.');
  return data;
}

export async function fetchArtworks(): Promise<ArtworkRecord[]> {
  const res = await fetch('/api/contents/artworks');
  if (!res.ok) throw new Error('작품 목록을 불러오지 못했어요.');
  return res.json();
}

export async function createArtworkDelivery(request: ArtworkDeliveryRequest): Promise<ArtworkDelivery> {
  const res = await mutate('/api/links/artworks', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '작품 링크를 만들지 못했어요.');
  return data;
}
