package com.plink.ticket.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Which day of the event an instant belongs to.
 *
 * <p>A visitor's "yesterday" is the venue's, not the server's: the server runs on UTC,
 * which in Seoul turns the day over at nine in the morning. An event that runs past
 * midnight can also move the turn later, so that 1 a.m. still belongs to the night
 * before.
 */
public final class VenueDay {
    private VenueDay() { }

    public static LocalDate of(Instant at, ZoneId zone, int closeHour) {
        return LocalDateTime.ofInstant(at, zone).minusHours(closeHour).toLocalDate();
    }

    public static boolean changed(Instant since, Instant now, ZoneId zone, int closeHour) {
        return of(since, zone, closeHour).isBefore(of(now, zone, closeHour));
    }
}
