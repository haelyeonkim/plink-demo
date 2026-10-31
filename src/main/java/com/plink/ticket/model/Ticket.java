package com.plink.ticket.model;

import java.sql.Timestamp;

public class Ticket {
    public long id, sessionId;
    public Long holderId;
    public String ticketRef, seat, tier, status, issuedToEmail, holderEmail, phone, deliveredVia;
    /** The URL token as issued, sealed. Null for tickets issued before it was kept. */
    public String tokenCipher;
    /** The organiser's own fields, as JSON. Seat and tier keep their own columns. */
    public String attributes;
    public Timestamp boundAt, claimExpiresAt, createdAt, deliveredAt;
    public int transferCount, reissueCount;
    /** The ticket whose holder invited this one; null once that ticket is deleted. */
    public Long invitedBy;
    /** Issued on a holder's invitation: it never invites in turn, even once unlinked. */
    public boolean invitee;

    public boolean bound() { return "BOUND".equals(status); }

    /** Claimed means a person is attached, whatever device they used. */
    public boolean claimed() { return holderId != null; }
    public boolean revoked() { return "REVOKED".equals(status); }
}
