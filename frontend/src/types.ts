export interface ProtectedLink {
  id: number;
  shortCode: string;
  /** Null when the link opens a document written here instead of an address. */
  originalUrl: string | null;
  contentId: number | null;
  contentTitle: string | null;
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
  /** One of the two: an address elsewhere, or a document written here. */
  originalUrl?: string;
  contentId?: number;
  title?: string;
  password?: string;
  expiresAt?: string;
  maxViews?: number;
}

/** A document written in the studio, which a link can point at. */
export interface ContentSummary {
  id: number;
  title: string;
  kind: string;
  linkCount: number;
  updatedAt: string | null;
  sourceType: 'MANUAL' | 'URL' | 'PDF' | 'SELECTION';
  sourceRef: string | null;
  sourceImportedAt: string | null;
}

export interface ContentDocument extends ContentSummary {
  body: ExhibitionBody;
}

export interface ExhibitionBody {
  intro?: string;
  columns?: '1' | '2';
  /** One row per work; the studio decides the keys, the reader renders what it finds. */
  artworks?: Array<Record<string, string>>;
}

/** A parsed draft is deliberately not persisted until the operator reviews it. */
export interface ContentImportResult {
  title: string;
  body: ExhibitionBody;
  artworkCount: number;
  warnings: string[];
  sourceType: 'URL' | 'PDF';
  /** SHA-256 for URL imports; original base filename for PDF imports. */
  sourceRef: string;
}

/** One reusable work saved from an authored or imported content document. */
export interface ArtworkRecord extends Record<string, string | number> {
  id: number;
  sourceContentId: number;
  image: string;
  artist: string;
  title: string;
  year: string;
  medium: string;
  width: string;
  height: string;
  depth: string;
  unit: string;
  description: string;
  price: string;
}

export interface ArtworkDeliveryRequest {
  artworkIds: number[];
  email: string;
  label?: string;
  title?: string;
  password?: string;
  expiresAt?: string;
  maxViews?: number;
  notify: boolean;
}

export interface ArtworkDelivery extends ProtectedLink {
  recipient: LinkRecipient;
}
