package com.plink.ticket.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many people each place holds, as the organiser states it.
 *
 * A place with no row here has no stated capacity, and crowding for it falls back to the
 * relative reading. That is deliberate: an organiser who has not measured a corridor
 * should not be made to invent a number before the panel will work.
 */
@Repository
public class ZoneCapacityRepository {
    private final JdbcTemplate jdbc;

    public ZoneCapacityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Integer> findBySession(long sessionId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT zone, capacity FROM zone_capacity WHERE session_id = ? ORDER BY zone", sessionId)) {
            result.put(String.valueOf(row.get("zone")), ((Number) row.get("capacity")).intValue());
        }
        return result;
    }

    public void save(long sessionId, String zone, int capacity) {
        int updated = jdbc.update("UPDATE zone_capacity SET capacity = ? WHERE session_id = ? AND zone = ?",
            capacity, sessionId, zone);
        if (updated == 0) {
            jdbc.update("INSERT INTO zone_capacity (session_id, zone, capacity) VALUES (?, ?, ?)",
                sessionId, zone, capacity);
        }
    }

    /** Clearing a place's number is how an organiser goes back to the relative reading. */
    public void delete(long sessionId, String zone) {
        jdbc.update("DELETE FROM zone_capacity WHERE session_id = ? AND zone = ?", sessionId, zone);
    }
}
