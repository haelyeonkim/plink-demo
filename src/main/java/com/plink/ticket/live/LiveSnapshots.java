package com.plink.ticket.live;

import com.plink.ticket.model.Presence;
import com.plink.ticket.repository.AdmissionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pictures a live channel sends: the same numbers the REST routes serve, built here
 * so a socket that has just opened - or a phone that woke up in the queue - starts from
 * the present rather than from whatever it last heard.
 */
@Component
public class LiveSnapshots {
    private final AdmissionRepository admissions;

    public LiveSnapshots(AdmissionRepository admissions) {
        this.admissions = admissions;
    }

    /** Crowd per place, relative to the busiest one, as the holder's panel reads it. */
    public Map<String, Object> crowding(long sessionId) {
        List<Map<String, Object>> rows = admissions.byZone(sessionId);
        int busiest = 0;
        for (Map<String, Object> row : rows) busiest = Math.max(busiest, inside(row));
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int inside = inside(row);
            double share = busiest == 0 ? 0 : (double) inside / busiest;
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("zone", row.get("zone"));
            zone.put("inside", inside);
            zone.put("share", Math.round(share * 100) / 100.0);
            zone.put("level", inside == 0 ? "EMPTY" : share >= 0.75 ? "BUSY" : share >= 0.4 ? "STEADY" : "QUIET");
            zones.add(zone);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zones", zones);
        result.put("measuredAt", java.time.Instant.now().toString());
        return result;
    }

    /** Where one ticket stands, so the phone can flip its buttons without asking again. */
    public Map<String, Object> presence(long ticketId) {
        Presence presence = admissions.find(ticketId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticketId);
        result.put("inside", presence != null && presence.inside());
        result.put("entryCount", presence == null ? 0 : presence.entryCount);
        result.put("reentryCount", presence == null ? 0 : presence.reentryCount);
        return result;
    }

    /** What the console's live panels show: totals, per gate, per place. */
    public Map<String, Object> occupancy(long sessionId) {
        Map<String, Object> result = new LinkedHashMap<>(admissions.occupancy(sessionId));
        result.put("byGate", admissions.byGate(sessionId));
        result.put("byZone", admissions.byZone(sessionId));
        result.put("recent", admissions.recentEvents(sessionId, 30));
        return result;
    }

    private static int inside(Map<String, Object> row) {
        int admitted = row.get("admitted") instanceof Number n ? n.intValue() : 0;
        int exited = row.get("exited") instanceof Number n ? n.intValue() : 0;
        return Math.max(0, admitted - exited);
    }
}
