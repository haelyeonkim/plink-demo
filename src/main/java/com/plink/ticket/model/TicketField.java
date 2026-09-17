package com.plink.ticket.model;

import java.util.Arrays;
import java.util.List;

/**
 * One thing a ticket says about itself: a name, and the values the console offers for it.
 *
 * <p>{@code SEAT} and {@code TIER} keep their own columns on the ticket - a seat because
 * it must be unique, a tier because the gate and the lists read it directly. Every other
 * field is stored on the ticket as JSON.
 */
public class TicketField {
    public long id, sessionId;
    public String label, kind, valuesCsv;
    public int position;

    public boolean seat() { return "SEAT".equals(kind); }
    public boolean tier() { return "TIER".equals(kind); }

    /** Empty means the field takes free text rather than a choice. */
    public List<String> values() {
        if (valuesCsv == null || valuesCsv.isBlank()) return List.of();
        return Arrays.stream(valuesCsv.split("[\\r\\n]"))
            .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
