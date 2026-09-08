export interface ProtectedLink {
  id: number;
  shortCode: string;
  originalUrl: string;
  title: string | null;
  hasPassword: boolean;
  expiresAt: string | null;
  recipientNames: string | null;
  maxViews: number;
  viewCount: number;
  createdAt: string;
}

export interface LinkDetail extends ProtectedLink {
  views: LinkViewRecord[];
}

export interface LinkViewRecord {
  viewerName: string;
  viewedAt: string;
}

export interface AccessInfo {
  shortCode: string;
  title: string | null;
  hasPassword: boolean;
  expired: boolean;
}

export interface CreateLinkRequest {
  originalUrl: string;
  title?: string;
  password?: string;
  expiresAt?: string;
  recipientNames?: string;
  maxViews?: number;
}
