package com.plink.ticket.repository;

import com.plink.ticket.model.Coupon;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CouponRepository {
    private final JdbcTemplate jdbc;

    public CouponRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Coupon> MAPPER = (rs, row) -> {
        Coupon coupon = new Coupon();
        coupon.id = rs.getLong("id");
        coupon.sessionId = rs.getLong("session_id");
        coupon.boothId = rs.getLong("booth_id");
        coupon.ticketId = rs.getLong("ticket_id");
        coupon.title = rs.getString("title");
        coupon.detail = rs.getString("detail");
        coupon.status = rs.getString("status");
        coupon.issuedAt = rs.getTimestamp("issued_at");
        coupon.redeemedAt = rs.getTimestamp("redeemed_at");
        coupon.redeemedGate = rs.getString("redeemed_gate");
        return coupon;
    };

    /** @return true when the offer was new to this ticket, false when it already had it. */
    public boolean insert(long sessionId, long boothId, long ticketId, String title, String detail) {
        try {
            jdbc.update("INSERT INTO coupon (session_id, booth_id, ticket_id, title, detail) "
                + "VALUES (?, ?, ?, ?, ?)", sessionId, boothId, ticketId, title, detail);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException already) {
            return false;
        }
    }

    public List<Coupon> findByTicket(long ticketId) {
        return jdbc.query("SELECT * FROM coupon WHERE ticket_id = ? ORDER BY booth_id, id", MAPPER, ticketId);
    }

    public List<Coupon> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM coupon WHERE session_id = ? ORDER BY booth_id, title, id",
            MAPPER, sessionId);
    }

    public Optional<Coupon> findById(long id) {
        return jdbc.query("SELECT * FROM coupon WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * The coupons this ticket still holds at this booth, oldest first, locked so two
     * terminals cannot hand over the same one.
     */
    public List<Coupon> lockLiveFor(long ticketId, long boothId) {
        return jdbc.query("SELECT * FROM coupon WHERE ticket_id = ? AND booth_id = ? "
            + "AND status = 'ISSUED' ORDER BY id FOR UPDATE", MAPPER, ticketId, boothId);
    }

    public List<Coupon> findByTicketAndBooth(long ticketId, long boothId) {
        return jdbc.query("SELECT * FROM coupon WHERE ticket_id = ? AND booth_id = ? ORDER BY id",
            MAPPER, ticketId, boothId);
    }

    public void redeem(long id, String gateId, Timestamp at) {
        jdbc.update("UPDATE coupon SET status = 'REDEEMED', redeemed_at = ?, redeemed_gate = ? "
            + "WHERE id = ? AND status = 'ISSUED'", at, gateId, id);
    }

    public void setStatus(long id, String status) {
        jdbc.update("UPDATE coupon SET status = ? WHERE id = ?", status, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM coupon WHERE id = ?", id);
    }

    /** What the console's coupon tab counts: how many of each offer are out and used. */
    public List<Map<String, Object>> tallyBySession(long sessionId) {
        return jdbc.queryForList(
            "SELECT booth_id, title, COUNT(*) AS issued, "
            + "SUM(CASE WHEN status = 'REDEEMED' THEN 1 ELSE 0 END) AS redeemed, "
            + "SUM(CASE WHEN status = 'VOID' THEN 1 ELSE 0 END) AS voided "
            + "FROM coupon WHERE session_id = ? GROUP BY booth_id, title ORDER BY booth_id, title",
            sessionId);
    }
}
