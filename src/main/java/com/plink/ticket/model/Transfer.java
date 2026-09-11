package com.plink.ticket.model;

import java.sql.Timestamp;

public class Transfer {
    public long id, ticketId;
    public String fromEmail, toEmail, toTokenHmac, status, policySnapshot;
    public Timestamp expiresAt, acceptedAt, closedAt, createdAt;

    public boolean pending() { return "PENDING".equals(status); }
    public boolean expired() { return expiresAt.toInstant().isBefore(java.time.Instant.now()); }
    public boolean open() { return pending() && !expired(); }
}
