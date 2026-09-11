package com.plink.ticket.repository;

import com.plink.ticket.model.Presence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The admission ledger is append-only; {@code ticket_presence} is the derived current
 * state and is always read under a row lock before a transition is written.
 */
@Repository
public class AdmissionRepository {
    private final JdbcTemplate jdbc;
    public AdmissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Presence> PRESENCE = (rs, row) -> {
        Presence p = new Presence();
        p.ticketId = rs.getLong("ticket_id");
        p.sessionId = rs.getLong("session_id");
        p.state = rs.getString("state");
        p.entryCount = rs.getInt("entry_count");
        p.reentryCount = rs.getInt("reentry_count");
        p.insideSince = rs.getTimestamp("inside_since");
        p.lastExitAt = rs.getTimestamp("last_exit_at");
        p.lastEventAt = rs.getTimestamp("last_event_at");
        p.lastGateId = rs.getString("last_gate_id");
        return p;
    };

    public void append(Long ticketId, Long sessionId, String direction, String gateId, String method,
            String result, String reason, String grantId) {
        jdbc.update("INSERT INTO admission_event (ticket_id, session_id, direction, gate_id, method, result, "
            + "reason, presentation_session_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ticketId, sessionId, direction, gateId, method, result, reason, grantId);
    }

    /** Ledger row with the extra provenance the field tools need. */
    public void appendDetailed(Long ticketId, Long sessionId, String direction, String gateId, String method,
            String result, String reason, String grantId, String operator, boolean offline, boolean flagged) {
        jdbc.update("INSERT INTO admission_event (ticket_id, session_id, direction, gate_id, method, result, "
            + "reason, presentation_session_id, operator, offline, flagged) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ticketId, sessionId, direction, gateId, method, result, reason, grantId, operator, offline, flagged);
    }

    /** Movements a terminal recorded while it could not reach the presence state. */
    public List<Map<String, Object>> flaggedEvents(long sessionId) {
        return jdbc.queryForList("SELECT ticket_id, direction, gate_id, result, reason, occurred_at "
            + "FROM admission_event WHERE session_id = ? AND flagged = TRUE ORDER BY id DESC", sessionId);
    }

    public List<Map<String, Object>> repeatedRefusals(long sessionId, int minimum) {
        return jdbc.queryForList("SELECT ticket_id, COUNT(*) AS refusals FROM admission_event "
            + "WHERE session_id = ? AND result = 'DENIED' AND ticket_id IS NOT NULL "
            + "GROUP BY ticket_id HAVING COUNT(*) >= ? ORDER BY COUNT(*) DESC", sessionId, minimum);
    }

    /**
     * Two accepted movements for one ticket at different gates inside a short window:
     * nobody walks that fast, so the pair is worth a human look.
     */
    public List<Map<String, Object>> impossibleMovements(long sessionId, int withinSeconds) {
        return jdbc.queryForList(
            "SELECT a.ticket_id, a.gate_id AS first_gate, b.gate_id AS second_gate, "
            + "a.occurred_at AS first_at, b.occurred_at AS second_at "
            + "FROM admission_event a JOIN admission_event b ON b.ticket_id = a.ticket_id AND b.id > a.id "
            + "WHERE a.session_id = ? AND b.session_id = ? "
            + "AND a.result IN ('ADMITTED','EXITED') AND b.result IN ('ADMITTED','EXITED') "
            + "AND a.gate_id IS NOT NULL AND b.gate_id IS NOT NULL AND a.gate_id <> b.gate_id "
            + "AND b.occurred_at <= ? ORDER BY a.ticket_id",
            sessionId, sessionId, java.sql.Timestamp.from(java.time.Instant.now()))
            .stream()
            .filter(row -> {
                java.sql.Timestamp first = (java.sql.Timestamp) row.get("first_at");
                java.sql.Timestamp second = (java.sql.Timestamp) row.get("second_at");
                long gap = (second.getTime() - first.getTime()) / 1000;
                return gap >= 0 && gap <= withinSeconds;
            })
            .toList();
    }

    public Optional<Presence> lock(long ticketId) {
        return jdbc.query("SELECT * FROM ticket_presence WHERE ticket_id = ? FOR UPDATE", PRESENCE, ticketId)
            .stream().findFirst();
    }

    public Optional<Presence> find(long ticketId) {
        return jdbc.query("SELECT * FROM ticket_presence WHERE ticket_id = ?", PRESENCE, ticketId)
            .stream().findFirst();
    }

    public void create(long ticketId, long sessionId) {
        jdbc.update("INSERT INTO ticket_presence (ticket_id, session_id) VALUES (?, ?)", ticketId, sessionId);
    }

    public void markInside(long ticketId, int entryCount, int reentryCount, String gateId) {
        jdbc.update("UPDATE ticket_presence SET state = 'INSIDE', entry_count = ?, reentry_count = ?, "
            + "inside_since = CURRENT_TIMESTAMP, last_event_at = CURRENT_TIMESTAMP, last_gate_id = ?, "
            + "updated_at = CURRENT_TIMESTAMP WHERE ticket_id = ?", entryCount, reentryCount, gateId, ticketId);
    }

    public void markOutside(long ticketId, String gateId) {
        jdbc.update("UPDATE ticket_presence SET state = 'OUTSIDE', inside_since = NULL, "
            + "last_exit_at = CURRENT_TIMESTAMP, last_event_at = CURRENT_TIMESTAMP, last_gate_id = ?, "
            + "updated_at = CURRENT_TIMESTAMP WHERE ticket_id = ?", gateId, ticketId);
    }

    /** A new holder starts with a clean movement history; the ledger still holds the old one. */
    public void resetPresence(long ticketId) {
        jdbc.update("UPDATE ticket_presence SET state = 'OUTSIDE', entry_count = 0, reentry_count = 0, "
            + "inside_since = NULL, last_exit_at = NULL, last_event_at = NULL, last_gate_id = NULL, "
            + "updated_at = CURRENT_TIMESTAMP WHERE ticket_id = ?", ticketId);
    }

    /** Live occupancy, per-zone distribution and the unmatched-exit list all come from here. */
    public Map<String, Object> occupancy(long sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inside", count("SELECT COUNT(*) FROM ticket_presence WHERE session_id = ? AND state = 'INSIDE'", sessionId));
        result.put("everEntered", count("SELECT COUNT(*) FROM ticket_presence WHERE session_id = ? AND entry_count > 0", sessionId));
        result.put("tickets", count("SELECT COUNT(*) FROM ticket WHERE session_id = ?", sessionId));
        result.put("bound", count("SELECT COUNT(*) FROM ticket WHERE session_id = ? AND status = 'BOUND'", sessionId));
        return result;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    public List<Map<String, Object>> recentEvents(long sessionId, int limit) {
        return jdbc.queryForList("SELECT ticket_id, direction, gate_id, method, result, reason, occurred_at "
            + "FROM admission_event WHERE session_id = ? ORDER BY id DESC LIMIT ?", sessionId, limit);
    }

    /**
     * Rows the AUTO_EXIT policy should close: still INSIDE long past the session's
     * threshold. Closing them keeps occupancy counts honest without letting the gate
     * forgive an unmatched exit on the spot.
     */
    public List<Map<String, Object>> autoExitCandidates() {
        // The threshold is a per-session column, so the comparison is done in Java
        // rather than with database-specific interval arithmetic.
        return jdbc.queryForList("SELECT p.ticket_id, p.session_id, p.inside_since, "
            + "s.auto_exit_after_minutes FROM ticket_presence p JOIN event_session s ON s.id = p.session_id "
            + "WHERE p.state = 'INSIDE' AND s.unmatched_exit = 'AUTO_EXIT' AND p.inside_since IS NOT NULL");
    }

    /** Tickets left INSIDE: the operations report that drives staff follow-up. */
    public List<Map<String, Object>> stillInside(long sessionId) {
        return jdbc.queryForList("SELECT p.ticket_id, t.seat, t.holder_email, p.inside_since, p.last_gate_id "
            + "FROM ticket_presence p JOIN ticket t ON t.id = p.ticket_id "
            + "WHERE p.session_id = ? AND p.state = 'INSIDE' ORDER BY p.inside_since", sessionId);
    }
}
