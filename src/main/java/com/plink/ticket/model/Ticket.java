package com.plink.ticket.model;

import java.sql.Timestamp;

public class Ticket {
    public long id, sessionId;
    public String ticketRef, seat, tier, status, issuedToEmail, holderEmail;
    public Timestamp boundAt, claimExpiresAt, createdAt;
    public int transferCount, reissueCount;

    public boolean bound() { return "BOUND".equals(status); }
    public boolean revoked() { return "REVOKED".equals(status); }
}
