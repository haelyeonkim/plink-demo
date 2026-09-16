package com.plink.ticket.model;

public class Gate {
    public String id, label, zone, direction, tokenHmac, tokenCipher, boundDevice;
    public String setupTokenHmac, setupTokenCipher, setupCodeHash, setupCodeCipher;
    public java.sql.Timestamp setupExpiresAt;
    public int setupAttempts;
    public java.sql.Timestamp boundAt, lastSeenAt;
    public long sessionId;

    /** A bidirectional terminal infers direction from the ticket's presence state. */
    public boolean bidirectional() { return "BIDIRECTIONAL".equals(direction); }
    public boolean supports(String wanted) { return bidirectional() || direction.equals(wanted); }

    public boolean setupOpen() {
        return setupTokenHmac != null && setupExpiresAt != null
            && setupExpiresAt.toInstant().isAfter(java.time.Instant.now());
    }
}
