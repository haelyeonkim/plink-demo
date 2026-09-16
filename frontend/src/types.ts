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
  /** How many people this link was issued to, and how many have registered. */
  recipientCount: number;
  claimedCount: number;
}

/** One person's address under a link: issued to an email, which the passkey binds to. */
export interface LinkRecipient {
  id: number;
  shortCode: string;
  /** Full address to hand out: https://host/s/{issuer}/{code}. */
  url: string;
  email: string | null;
  label: string | null;
  status: string;
  revoked: boolean;
  claimed: boolean;
  holderEmail: string | null;
  viewCount: number;
  createdAt: string;
  claimedAt: string | null;
  /** Set on the issue response: EMAIL when the address was mailed, LINK when not. */
  deliveredVia?: string;
}

export interface LinkDetail extends ProtectedLink {
  views: LinkViewRecord[];
  recipients: LinkRecipient[];
}

export interface LinkViewRecord {
  viewerName: string | null;
  viewedAt: string;
  /** INITIAL_OPEN: the address was opened. PASSKEY_AUTHENTICATED: it was proven. */
  eventType?: string;
}

export interface AccessInfo {
  shortCode: string;
  title: string | null;
  hasPassword: boolean;
  claimed: boolean;
  expired: boolean;
  exhausted: boolean;
  /** Masked form of the address this was issued to, e.g. k***@example.com. */
  issuedTo: string | null;
  /** The account that issued it, and the canonical path it lives at. */
  issuer: string | null;
  path: string;
  /** Whether the visitor still has to prove which address this was sent to. */
  contactRequired?: boolean;
  expiresAt?: string | null;
  maxViews?: number;
  viewCount?: number;
}

export interface CreateLinkRequest {
  originalUrl: string;
  title?: string;
  password?: string;
  expiresAt?: string;
  maxViews?: number;
}
