export interface ProtectedLink {
  id: number;
  shortCode: string;
  originalUrl: string;
  title: string | null;
  hasPassword: boolean;
  claimed: boolean;
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
  eventType: 'INITIAL_OPEN' | 'PASSKEY_AUTHENTICATED' | string;
}

export interface AccessInfo {
  recipientType: 'email' | 'phone' | null;
  recipientContact: string | null;
  senderLabel: string;
  expiresAt: string | null;
  maxViews: number;
  viewCount: number;
  shortCode: string;
  title: string | null;
  hasPassword: boolean;
  claimed: boolean;
  expired: boolean;
  exhausted: boolean;
}

export interface CreateLinkRequest {
  originalUrl: string;
  title?: string;
  password?: string;
  expiresAt?: string;
  recipientNames?: string;
  maxViews?: number;
}
