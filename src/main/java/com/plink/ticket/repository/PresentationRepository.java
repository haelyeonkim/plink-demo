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
        g.revoked = rs.getBoolean("revoked");
        g.lastCounter = rs.getLong("last_counter");
        g.issuedAt = rs.getTimestamp("issued_at");
        g.expiresAt = rs.getTimestamp("expires_at");
        g.consumedAt = rs.getTimestamp("consumed_at");
        long coupon = rs.getLong("coupon_id");
        g.couponId = rs.wasNull() ? null : coupon;
        return g;
    };

    public void insert(Grant grant) {
        jdbc.update("INSERT INTO presentation_session (id, ticket_id, direction, secret, uv, issued_at, "
            + "expires_at, geo_ok, geo_distance_meters, coupon_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            grant.id, grant.ticketId, grant.direction, grant.secret, grant.uv, grant.issuedAt,
            grant.expiresAt, grant.geoOk, grant.geoDistanceMeters, grant.couponId);
    }

    /** Grants opened away from the venue, the signal that a link is being worked remotely. */
    public int countAwayFromVenue(long ticketId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM presentation_session WHERE ticket_id = ? AND geo_ok = FALSE",
            Integer.class, ticketId);
        return count == null ? 0 : count;
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

    /**
     * Any earlier grant stops working when a new one is issued, or when the ticket is
     * transferred. Marked revoked rather than consumed: a terminal that read the old code
     * while offline still saw a real movement.
     */
    public void revokeOpenGrants(long ticketId) {
        jdbc.update("UPDATE presentation_session SET revoked = TRUE "
            + "WHERE ticket_id = ? AND consumed_at IS NULL AND revoked = FALSE", ticketId);
    }

    /** The first code opened since then: when it ages out, one more may be opened. */
    public Timestamp oldestSince(long ticketId, Timestamp since) {
        return jdbc.queryForObject(
            "SELECT MIN(issued_at) FROM presentation_session WHERE ticket_id = ? AND issued_at >= ?",
            Timestamp.class, ticketId, since);
    }

    public int countSince(long ticketId, Timestamp since) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM presentation_session WHERE ticket_id = ? AND issued_at >= ?",
            Integer.class, ticketId, since);
        return count == null ? 0 : count;
    }
}
