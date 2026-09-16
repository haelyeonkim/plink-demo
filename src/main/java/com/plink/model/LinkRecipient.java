package com.plink.model;

import java.sql.Timestamp;

/**
 * One person's way into a protected link. The link is the document; a recipient is an
 * address issued under it, which is what a passkey binds to.
 */
public class LinkRecipient {
    public long id, linkId;
    public Long holderId;
    /** The address this was issued to: the person's identity, as on a ticket. */
    public String email;
    public String shortCode, label, status;
    public int viewCount;
    public Timestamp createdAt, claimedAt;
    /** The holder's own address once they have registered, null until they have. */
    public String receiverName;

    public boolean revoked() { return "REVOKED".equals(status); }
    public boolean claimed() { return claimedAt != null; }
}
