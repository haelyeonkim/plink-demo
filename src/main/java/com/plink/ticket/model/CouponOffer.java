package com.plink.ticket.model;

/** Something a booth can give. Coupons are copies of one of these, one per ticket. */
public class CouponOffer {
    public long id, sessionId, boothId;
    public String title, detail;
    public boolean active;
    public java.sql.Timestamp createdAt;
}
