package com.plink.ticket.model;

import java.sql.Timestamp;

/** A presentation grant: one passkey ceremony buys one short window of rotating codes. */
public class Grant {
    public String id, direction, secret;
    public long ticketId, lastCounter;
    public boolean uv;
    public Timestamp issuedAt, expiresAt, consumedAt;

    public boolean consumed() { return consumedAt != null; }
    public boolean expired() { return expiresAt.toInstant().isBefore(java.time.Instant.now()); }
}
