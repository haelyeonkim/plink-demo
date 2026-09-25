package com.plink.ticket.repository;

import com.plink.ticket.model.Coupon;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
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
    public boolean insert(long sessionId, long boothId, long offerId, long ticketId, String title,
            String detail) {
        try {
            jdbc.update("INSERT INTO coupon (session_id, booth_id, offer_id, ticket_id, title, detail) "
                + "VALUES (?, ?, ?, ?, ?, ?)", sessionId, boothId, offerId, ticketId, title, detail);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException already) {
            return false;
        }
    }

    public boolean anyForOffer(long offerId) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE offer_id = ?",
            Integer.class, offerId);
        return found != null && found > 0;
    }

    /**
     * One page of a session's coupons, with the ticket and booth each belongs to.
     *
     * <p>Joined in one statement: a page of fifty rows is one query, not fifty-one.
     */
    public List<Listed> search(long sessionId, CouponFilter filter, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String from = from(sessionId, filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(
            "SELECT c.id, c.booth_id, b.name AS booth, c.offer_id, c.title, c.detail, "
            + "c.status, c.ticket_id, c.redeemed_at, t.ticket_ref, t.seat, t.issued_to_email"
            + from + " ORDER BY c.booth_id, c.title, c.id LIMIT ? OFFSET ?",
            (rs, row) -> {
                long offer = rs.getLong("offer_id");
                Long offerId = rs.wasNull() ? null : offer;
                return new Listed(rs.getLong("id"), rs.getLong("booth_id"), rs.getString("booth"), offerId,
                    rs.getString("title"), rs.getString("detail"), rs.getString("status"),
                    rs.getLong("ticket_id"), rs.getString("ticket_ref"), rs.getString("seat"),
                    rs.getString("issued_to_email"), rs.getTimestamp("redeemed_at"));
            }, args.toArray());
    }

    /** A coupon as the console's table shows it: with its ticket and its booth's name. */
    public record Listed(long id, long boothId, String booth, Long offerId, String title, String detail,
            String status, long ticketId, String ticketRef, String seat, String issuedToEmail,
            Timestamp redeemedAt) {}

    public long count(long sessionId, CouponFilter filter) {
        List<Object> args = new ArrayList<>();
        Long found = jdbc.queryForObject("SELECT COUNT(*)" + from(sessionId, filter, args),
            Long.class, args.toArray());
        return found == null ? 0 : found;
    }

    /** What the console can narrow a session's coupons down by. Nulls mean "any". */
    public record CouponFilter(Long boothId, String status, String query) {}

    private static String from(long sessionId, CouponFilter filter, List<Object> args) {
        StringBuilder sql = new StringBuilder(" FROM coupon c JOIN ticket t ON t.id = c.ticket_id "
            + "LEFT JOIN booth b ON b.id = c.booth_id WHERE c.session_id = ?");
        args.add(sessionId);
        if (filter.boothId() != null) {
            sql.append(" AND c.booth_id = ?");
            args.add(filter.boothId());
        }
        if (filter.status() != null) {
            sql.append(" AND c.status = ?");
            args.add(filter.status());
        }
        if (filter.query() != null) {
            String like = Search.like(filter.query());
            sql.append(" AND (LOWER(c.title) LIKE ? ESCAPE '\\' OR LOWER(t.ticket_ref) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.seat, '')) LIKE ? ESCAPE '\\'"
                + " OR LOWER(t.issued_to_email) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.holder_email, '')) LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 5; i++) args.add(like);
        }
        return sql.toString();
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

}
