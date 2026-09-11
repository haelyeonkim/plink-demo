package com.plink.ticket.model;

import java.sql.Timestamp;

public class EventSession {
    public long id;
    public String name, venue, reentryMode, unmatchedExit;
    public Timestamp startsAt, gateOpensAt;
    public int reentryMax, reentryGraceMinutes, reentryCooldownSeconds, autoExitAfterMinutes;
    public int transferMax, transferClosesMinutesBefore;
    public boolean transferAfterFirstEntry;
    public boolean faceRequired, reentryRequiresFace;
    public String faceLiveness, faceChallengeOn;
    public int faceRetentionDays;
    public Double venueLat, venueLon;
    public int geoRadiusMeters;
    public String geoMode;
    public boolean exitScanRequired;

    public boolean reentryAllowed() { return !"DISABLED".equals(reentryMode); }
    public boolean reentryLimited() { return "LIMITED".equals(reentryMode); }
}
