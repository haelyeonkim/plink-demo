package com.plink.ticket.model;

import java.sql.Timestamp;

/** Somebody the organiser has asked to run one booth from their own phone. */
public class BoothManager {
    public long id, sessionId, boothId;
    public String email, tokenHash, status;
    /** The person whose passkey opens the booth; null until the invitation is taken up. */
    public Long holderId;
    /** The booth terminal this person answers for and acts as; null when none was chosen. */
    public String gateId;
    public Timestamp invitedAt, claimedAt, lastSeenAt;

    public boolean revoked() { return "REVOKED".equals(status); }
    public boolean active() { return "ACTIVE".equals(status); }
    public boolean claimed() { return holderId != null && active(); }
}
