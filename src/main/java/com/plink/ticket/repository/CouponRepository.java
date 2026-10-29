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

    /** Gives {@code count} more of an offer to one ticket. */
    public void insert(long sessionId, long boothId, long offerId, long ticketId, String title,
            String detail, int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {sessionId, boothId, offerId, ticketId, title, detail});
        }
        jdbc.batchUpdate("INSERT INTO coupon (session_id, booth_id, offer_id, ticket_id, title, detail) "
            + "VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    /** One row to add: which ticket, and how many of the offer. */
    public record Grant(long ticketId, int count) {}

    /** Adds an offer to many tickets in one round trip. */
    public void insertAll(long sessionId, long boothId, long offerId, String title, String detail,
            List<Grant> grants) {
        List<Object[]> rows = new ArrayList<>();
        for (Grant grant : grants) {
            for (int i = 0; i < grant.count(); i++) {
                rows.add(new Object[] {sessionId, boothId, offerId, grant.ticketId(), title, detail});
            }
        }
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO coupon (session_id, booth_id, offer_id, ticket_id, title, detail) "
            + "VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    /**
     * How many of an offer each ticket holds, used or not. A coupon taken back does not
     * count: topping up after a void gives a fresh one.
     */
    public Map<Long, Integer> heldOf(long offerId) {
        Map<Long, Integer> held = new java.util.HashMap<>();
        jdbc.query("SELECT ticket_id, COUNT(*) AS held FROM coupon WHERE offer_id = ? AND status <> 'VOID' "
            + "GROUP BY ticket_id", rs -> { held.put(rs.getLong("ticket_id"), rs.getInt("held")); }, offerId);
        return held;
    }

    /** How many of an offer one ticket holds, used or not, leaving out what was taken back. */
    public int heldBy(long offerId, long ticketId) {
        Integer held = jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE offer_id = ? AND ticket_id = ? "
            + "AND status <> 'VOID'", Integer.class, offerId, ticketId);
        return held == null ? 0 : held;
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
            + "c.status, c.ticket_id, c.issued_at, c.redeemed_at, c.redeemed_gate, g.label AS gate_label, "
            + "t.ticket_ref, t.seat, t.issued_to_email"
            + from + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> {
                long offer = rs.getLong("offer_id");
                Long offerId = rs.wasNull() ? null : offer;
                return new Listed(rs.getLong("id"), rs.getLong("booth_id"), rs.getString("booth"), offerId,
                    rs.getString("title"), rs.getString("detail"), rs.getString("status"),
                    rs.getLong("ticket_id"), rs.getString("ticket_ref"), rs.getString("seat"),
                    rs.getString("issued_to_email"), rs.getTimestamp("issued_at"), rs.getTimestamp("redeemed_at"),
                    rs.getString("gate_label") != null ? rs.getString("gate_label") : rs.getString("redeemed_gate"));
            }, args.toArray());
    }

    /** A coupon as the console's table shows it: with its ticket and its booth's name. */
    public record Listed(long id, long boothId, String booth, Long offerId, String title, String detail,
            String status, long ticketId, String ticketRef, String seat, String issuedToEmail,
            Timestamp issuedAt, Timestamp redeemedAt, String redeemedAtGate) {}

    /** How many of the filtered coupons are in each state, whatever state the filter asks for. */
    public Map<String, Long> countByStatus(long sessionId, CouponFilter filter) {
        List<Object> args = new ArrayList<>();
        CouponFilter any = new CouponFilter(filter.boothId(), filter.offerId(), null, filter.query());
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String state : List.of("ISSUED", "REDEEMED", "VOID")) counts.put(state, 0L);
        jdbc.query("SELECT c.status, COUNT(*) AS n" + from(sessionId, any, args) + " GROUP BY c.status",
            rs -> { counts.put(rs.getString("status"), rs.getLong("n")); }, args.toArray());
        return counts;
    }

    /** Marked used by the organiser rather than read at a booth: no terminal to name. */
    public boolean redeemByStaff(long id, Timestamp at) {
        return jdbc.update("UPDATE coupon SET status = 'REDEEMED', redeemed_at = ?, redeemed_gate = NULL "
            + "WHERE id = ? AND status = 'ISSUED'", at, id) > 0;
    }

    public long count(long sessionId, CouponFilter filter) {
        List<Object> args = new ArrayList<>();
        Long found = jdbc.queryForObject("SELECT COUNT(*)" + from(sessionId, filter, args),
            Long.class, args.toArray());
        return found == null ? 0 : found;
    }

    /** What the console can narrow a session's coupons down by. Nulls mean "any". */
    public record CouponFilter(Long boothId, Long offerId, String status, String query) {}

    private static String from(long sessionId, CouponFilter filter, List<Object> args) {
        StringBuilder sql = new StringBuilder(" FROM coupon c JOIN ticket t ON t.id = c.ticket_id "
            + "LEFT JOIN booth b ON b.id = c.booth_id LEFT JOIN gate g ON g.id = c.redeemed_gate "
            + "WHERE c.session_id = ?");
        args.add(sessionId);
        if (filter.boothId() != null) {
            sql.append(" AND c.booth_id = ?");
            args.add(filter.boothId());
        }
        if (filter.offerId() != null) {
            sql.append(" AND c.offer_id = ?");
            args.add(filter.offerId());
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

    /** Voids a coupon that is not void yet; false when there was nothing to change. */
    public boolean voidCoupon(long id) {
        return jdbc.update("UPDATE coupon SET status = 'VOID' WHERE id = ? AND status <> 'VOID'", id) > 0;
    }

    /** Gives a void coupon back as unused, with no trace of a hand-over it no longer stands for. */
    public boolean restore(long id) {
        return jdbc.update("UPDATE coupon SET status = 'ISSUED', redeemed_at = NULL, redeemed_gate = NULL "
            + "WHERE id = ? AND status = 'VOID'", id) > 0;
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
