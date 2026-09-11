package com.plink.ticket.model;

import java.sql.Timestamp;

public class Presence {
    public long ticketId, sessionId;
    public String state, lastGateId;
    public int entryCount, reentryCount;
    public Timestamp insideSince, lastExitAt, lastEventAt;

    public boolean inside() { return "INSIDE".equals(state); }
}
