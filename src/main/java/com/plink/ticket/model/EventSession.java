package com.plink.ticket.model;

import java.sql.Timestamp;

public class EventSession {
    public long id;
    public String name, venue, reentryMode, unmatchedExit;
    public Timestamp startsAt, gateOpensAt;
    public int reentryMax, reentryGraceMinutes, reentryCooldownSeconds, autoExitAfterMinutes;
    public int transferMax, transferClosesMinutesBefore;
    public boolean transferAfterFirstEntry;
    public boolean faceRequired, reentryRequiresFace, claimRequiresOtp;
    public String faceLiveness, faceChallengeOn;
    public int faceRetentionDays;
    public Double venueLat, venueLon;
    public int geoRadiusMeters;
    public String geoMode;
    /** Newline-separated catalogue; empty means the console falls back to free text. */
    public String seats, tiers;
    /** Where the crowding panel draws its two lines, as a percentage of a place's capacity. */
    public int crowdBusyPercent, crowdSteadyPercent;

    public java.util.List<String> seatList() { return split(seats); }
    public java.util.List<String> tierList() { return split(tiers); }

    private static java.util.List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(raw.split("[\\r\\n,]"))
            .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
    public boolean exitScanRequired;

    public boolean reentryAllowed() { return !"DISABLED".equals(reentryMode); }
    public boolean reentryLimited() { return "LIMITED".equals(reentryMode); }
}
