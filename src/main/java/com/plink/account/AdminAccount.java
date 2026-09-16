package com.plink.account;

import java.sql.Timestamp;

public class AdminAccount {
    public long id;
    public String email, passwordHash, displayName;
    /** Path segment this account's public link addresses live under: /s/{slug}/{code}. */
    public String slug;
    public int failedLogins;
    public Timestamp lockedUntil, lastLoginAt, createdAt;
    /** What this account may reach: the link console, the ticket console, accounts. */
    public boolean canLinks, canTickets, owner;

    public boolean locked() {
        return lockedUntil != null && lockedUntil.toInstant().isAfter(java.time.Instant.now());
    }

    /** Owner key for links created by this account, kept distinct from Google subjects. */
    public String subject() { return "local:" + id; }
}
