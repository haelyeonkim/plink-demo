package com.plink.ticket.model;

/** One offer, for one ticket, at one booth. */
public class Coupon {
    public long id, sessionId, boothId, ticketId;
    public String title, detail, status, redeemedGate;
    public java.sql.Timestamp issuedAt, redeemedAt;

    public boolean redeemed() { return "REDEEMED".equals(status); }
    public boolean live() { return "ISSUED".equals(status); }
}
