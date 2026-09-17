package com.plink.ticket.live;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Presence;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.ZoneCapacityRepository;
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
    private final EventSessionRepository sessions;
    private final ZoneCapacityRepository capacities;

    public LiveSnapshots(AdmissionRepository admissions, EventSessionRepository sessions,
            ZoneCapacityRepository capacities) {
        this.admissions = admissions;
        this.sessions = sessions;
        this.capacities = capacities;
    }

    /**
     * Crowd per place, as the holder's panel reads it.
     *
     * A place the organiser has given a capacity is measured against that capacity, and
     * the two lines are the percentages they set. A place without one is still read
     * against the busiest place, which is the only thing that can be said without a
     * number: it ranks the doors but says nothing about how full the building is.
     */
    public Map<String, Object> crowding(long sessionId) {
        EventSession session = sessions.findById(sessionId).orElse(null);
        int busyLine = session == null || session.crowdBusyPercent <= 0 ? 75 : session.crowdBusyPercent;
        int steadyLine = session == null || session.crowdSteadyPercent <= 0 ? 40 : session.crowdSteadyPercent;
        Map<String, Integer> capacity = capacities.findBySession(sessionId);

        List<Map<String, Object>> rows = admissions.byZone(sessionId);
        int busiest = 0;
        for (Map<String, Object> row : rows) busiest = Math.max(busiest, inside(row));

        List<Map<String, Object>> zones = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("zone"));
            int inside = inside(row);
            Integer holds = capacity.get(name);
            boolean measured = holds != null && holds > 0;
            double share = measured ? (double) inside / holds
                : busiest == 0 ? 0 : (double) inside / busiest;
            int percent = (int) Math.round(share * 100);
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("zone", name);
            zone.put("inside", inside);
            zone.put("capacity", measured ? holds : null);
            zone.put("share", Math.round(share * 100) / 100.0);
            zone.put("percent", percent);
            // Without a capacity the reading is a ranking, not a fullness. The panel says
            // so rather than showing "80% full" of a number nobody gave it.
            zone.put("basis", measured ? "CAPACITY" : "RELATIVE");
            zone.put("level", inside == 0 ? "EMPTY"
                : percent >= busyLine ? "BUSY" : percent >= steadyLine ? "STEADY" : "QUIET");
            zones.add(zone);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zones", zones);
        result.put("busyPercent", busyLine);
        result.put("steadyPercent", steadyLine);
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
