package com.plink.ticket.repository;

import com.plink.ticket.model.Grant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class PresentationRepository {
    private final JdbcTemplate jdbc;
    public PresentationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Grant> MAPPER = (rs, row) -> {
        Grant g = new Grant();
        g.id = rs.getString("id");
        g.ticketId = rs.getLong("ticket_id");
        g.direction = rs.getString("direction");
        g.secret = rs.getString("secret");
        g.uv = rs.getBoolean("uv");
        g.lastCounter = rs.getLong("last_counter");
        g.issuedAt = rs.getTimestamp("issued_at");
        g.expiresAt = rs.getTimestamp("expires_at");
        g.consumedAt = rs.getTimestamp("consumed_at");
        return g;
    };

    public void insert(Grant grant) {
        jdbc.update("INSERT INTO presentation_session (id, ticket_id, direction, secret, uv, issued_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            grant.id, grant.ticketId, grant.direction, grant.secret, grant.uv, grant.issuedAt, grant.expiresAt);
    }

    public Optional<Grant> findById(String id) {
        return jdbc.query("SELECT * FROM presentation_session WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Grant> lockById(String id) {
        return jdbc.query("SELECT * FROM presentation_session WHERE id = ? FOR UPDATE", MAPPER, id)
            .stream().findFirst();
    }

    public void updateCounter(String id, long counter) {
        jdbc.update("UPDATE presentation_session SET last_counter = ? WHERE id = ?", counter, id);
    }

    public void consume(String id) {
        jdbc.update("UPDATE presentation_session SET consumed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    /** Any earlier grant dies when a new one is issued, or when the ticket is transferred. */
    public void revokeOpenGrants(long ticketId) {
        jdbc.update("UPDATE presentation_session SET consumed_at = CURRENT_TIMESTAMP "
            + "WHERE ticket_id = ? AND consumed_at IS NULL", ticketId);
    }

    public int countSince(long ticketId, Timestamp since) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM presentation_session WHERE ticket_id = ? AND issued_at >= ?",
            Integer.class, ticketId, since);
        return count == null ? 0 : count;
    }
}
