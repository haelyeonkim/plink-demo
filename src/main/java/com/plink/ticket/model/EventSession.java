package com.plink.ticket.model;

import java.sql.Timestamp;

public class EventSession {
    public long id;
    public String name, venue, reentryMode, unmatchedExit;
    public Timestamp startsAt, gateOpensAt;
    public int reentryMax, reentryGraceMinutes, reentryCooldownSeconds, autoExitAfterMinutes;
    public boolean exitScanRequired;

    public boolean reentryAllowed() { return !"DISABLED".equals(reentryMode); }
    public boolean reentryLimited() { return "LIMITED".equals(reentryMode); }
}
