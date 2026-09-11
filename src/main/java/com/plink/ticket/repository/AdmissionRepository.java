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
