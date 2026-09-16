package com.plink.ticket.model;

import java.sql.Timestamp;

public class Ticket {
    public long id, sessionId;
    public Long holderId;
    public String ticketRef, seat, tier, status, issuedToEmail, holderEmail, phone, deliveredVia;
    public Timestamp boundAt, claimExpiresAt, createdAt, deliveredAt;
    public int transferCount, reissueCount;

    public boolean bound() { return "BOUND".equals(status); }

    /** Claimed means a person is attached, whatever device they used. */
    public boolean claimed() { return holderId != null; }
    public boolean revoked() { return "REVOKED".equals(status); }
}
