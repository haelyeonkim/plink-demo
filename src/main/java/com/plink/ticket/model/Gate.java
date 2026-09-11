package com.plink.ticket.model;

public class Gate {
    public String id, label, zone, direction, tokenHmac;
    public long sessionId;

    /** A bidirectional terminal infers direction from the ticket's presence state. */
    public boolean bidirectional() { return "BIDIRECTIONAL".equals(direction); }
    public boolean supports(String wanted) { return bidirectional() || direction.equals(wanted); }
}
