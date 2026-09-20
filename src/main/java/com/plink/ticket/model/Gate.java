package com.plink.ticket.model;

public class Gate {
    public String id, label, zone, direction, tokenHmac, tokenCipher, boundDevice;
    public String setupTokenHmac, setupTokenCipher, setupCodeHash, setupCodeCipher;
    public java.sql.Timestamp setupExpiresAt;
    public int setupAttempts;
    public java.sql.Timestamp boundAt, lastSeenAt, tokenExpiresAt;
    public long sessionId;

    /** A bidirectional terminal infers direction from the ticket's presence state. */
    public boolean bidirectional() { return "BIDIRECTIONAL".equals(direction); }
    public boolean supports(String wanted) { return bidirectional() || direction.equals(wanted); }

    /** A terminal registered before tokens had an end date has none until it is renewed. */
    public boolean tokenExpired() {
        return tokenExpiresAt != null && tokenExpiresAt.toInstant().isBefore(java.time.Instant.now());
    }

    public boolean setupOpen() {
        return setupTokenHmac != null && setupExpiresAt != null
            && setupExpiresAt.toInstant().isAfter(java.time.Instant.now());
    }
}
