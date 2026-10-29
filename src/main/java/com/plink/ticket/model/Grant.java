package com.plink.ticket.model;

import java.sql.Timestamp;

/** A presentation grant: one passkey ceremony buys one short window of rotating codes. */
public class Grant {
    public String id, direction, secret;
    public long ticketId, lastCounter;
    public boolean uv, revoked;
    public Boolean geoOk;
    public Double geoDistanceMeters;
    public Timestamp issuedAt, expiresAt, consumedAt;
    /** Set on a coupon's own code: the one coupon a booth spends when it reads it. */
    public Long couponId;

    public boolean consumed() { return consumedAt != null; }
    public boolean forCoupon() { return couponId != null; }
    public boolean expired() { return expiresAt.toInstant().isBefore(java.time.Instant.now()); }
}
