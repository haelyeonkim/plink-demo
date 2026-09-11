package com.plink.ticket.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class FaceRepository {
    private final JdbcTemplate jdbc;
    public FaceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static class Template {
        public long id, ticketId, sessionId;
        public String algoVersion, vector;
        public int dimensions;
        public double quality;
        public Timestamp enrolledAt, revokedAt, purgeAfter;
    }

    public static class Consent {
        public long id, ticketId;
        public String email, consentVersion, purposes;
        public Timestamp retainUntil, consentedAt, withdrawnAt;
    }

    private static final RowMapper<Template> TEMPLATE = (rs, row) -> {
        Template t = new Template();
        t.id = rs.getLong("id");
        t.ticketId = rs.getLong("ticket_id");
        t.sessionId = rs.getLong("session_id");
        t.algoVersion = rs.getString("algo_version");
        t.dimensions = rs.getInt("dimensions");
        t.quality = rs.getDouble("quality");
        t.vector = rs.getString("vector");
        t.enrolledAt = rs.getTimestamp("enrolled_at");
        t.revokedAt = rs.getTimestamp("revoked_at");
        t.purgeAfter = rs.getTimestamp("purge_after");
        return t;
    };

    private static final RowMapper<Consent> CONSENT = (rs, row) -> {
        Consent c = new Consent();
        c.id = rs.getLong("id");
        c.ticketId = rs.getLong("ticket_id");
        c.email = rs.getString("email");
        c.consentVersion = rs.getString("consent_version");
        c.purposes = rs.getString("purposes");
        c.retainUntil = rs.getTimestamp("retain_until");
        c.consentedAt = rs.getTimestamp("consented_at");
        c.withdrawnAt = rs.getTimestamp("withdrawn_at");
        return c;
    };

    public void recordConsent(long ticketId, String email, String version, String purposes, Timestamp retainUntil) {
        jdbc.update("INSERT INTO face_consent (ticket_id, email, consent_version, purposes, retain_until) "
            + "VALUES (?, ?, ?, ?, ?)", ticketId, email, version, purposes, retainUntil);
    }

    public Optional<Consent> activeConsent(long ticketId) {
        return jdbc.query("SELECT * FROM face_consent WHERE ticket_id = ? AND withdrawn_at IS NULL "
            + "ORDER BY id DESC", CONSENT, ticketId).stream().findFirst();
    }

    public void withdrawConsent(long ticketId) {
        jdbc.update("UPDATE face_consent SET withdrawn_at = CURRENT_TIMESTAMP "
            + "WHERE ticket_id = ? AND withdrawn_at IS NULL", ticketId);
    }

    public void saveTemplate(long ticketId, long sessionId, String algoVersion, int dimensions,
            double quality, String vector, Timestamp purgeAfter) {
        jdbc.update("DELETE FROM face_template WHERE ticket_id = ?", ticketId);
        jdbc.update("INSERT INTO face_template (ticket_id, session_id, algo_version, dimensions, quality, "
            + "vector, purge_after) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ticketId, sessionId, algoVersion, dimensions, quality, vector, purgeAfter);
    }

    public Optional<Template> findByTicket(long ticketId) {
        return jdbc.query("SELECT * FROM face_template WHERE ticket_id = ? AND revoked_at IS NULL",
            TEMPLATE, ticketId).stream().findFirst();
    }

    /** The candidate set for 1:N is one session's holders, which keeps N in the thousands. */
    public List<Template> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM face_template WHERE session_id = ? AND revoked_at IS NULL",
            TEMPLATE, sessionId);
    }

    /** Withdrawal destroys the template immediately; there is nothing to keep. */
    public int deleteTemplate(long ticketId) {
        return jdbc.update("DELETE FROM face_template WHERE ticket_id = ?", ticketId);
    }

    public int purgeExpired(Timestamp now) {
        return jdbc.update("DELETE FROM face_template WHERE purge_after < ?", now);
    }

    public void recordAttempt(long sessionId, Long ticketId, String gateId, String stage, String result,
            Double score, Double runnerUp, Double liveness, String reason) {
        jdbc.update("INSERT INTO face_attempt (session_id, ticket_id, gate_id, stage, result, score, "
            + "runner_up, liveness, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sessionId, ticketId, gateId, stage, result, score, runnerUp, liveness, reason);
    }

    public List<java.util.Map<String, Object>> recentAttempts(long sessionId, int limit) {
        return jdbc.queryForList("SELECT stage, result, score, reason, occurred_at FROM face_attempt "
            + "WHERE session_id = ? ORDER BY id DESC LIMIT ?", sessionId, limit);
    }
}
