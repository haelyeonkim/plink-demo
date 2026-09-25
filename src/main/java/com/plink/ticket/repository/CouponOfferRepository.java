package com.plink.ticket.repository;

import com.plink.ticket.model.CouponOffer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CouponOfferRepository {
    private final JdbcTemplate jdbc;

    public CouponOfferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<CouponOffer> MAPPER = (rs, row) -> {
        CouponOffer offer = new CouponOffer();
        offer.id = rs.getLong("id");
        offer.sessionId = rs.getLong("session_id");
        offer.boothId = rs.getLong("booth_id");
        offer.title = rs.getString("title");
        offer.detail = rs.getString("detail");
        offer.active = rs.getBoolean("active");
        offer.createdAt = rs.getTimestamp("created_at");
        return offer;
    };

    public long insert(long sessionId, long boothId, String title, String detail) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO coupon_offer (session_id, booth_id, title, detail) VALUES (?, ?, ?, ?)",
                new String[] {"id"});
            ps.setLong(1, sessionId);
            ps.setLong(2, boothId);
            ps.setString(3, title);
            ps.setString(4, detail);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<CouponOffer> findById(long id) {
        return jdbc.query("SELECT * FROM coupon_offer WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<CouponOffer> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM coupon_offer WHERE session_id = ? ORDER BY booth_id, id",
            MAPPER, sessionId);
    }

    public List<CouponOffer> findByBooth(long boothId) {
        return jdbc.query("SELECT * FROM coupon_offer WHERE booth_id = ? ORDER BY id", MAPPER, boothId);
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE coupon_offer SET active = ? WHERE id = ?", active, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM coupon_offer WHERE id = ?", id);
    }

    /** How many of each offer have been given, used, taken back and are still waiting. */
    public List<Map<String, Object>> tallyBySession(long sessionId) {
        return jdbc.queryForList(
            "SELECT offer_id, COUNT(*) AS issued, "
            + "SUM(CASE WHEN status = 'REDEEMED' THEN 1 ELSE 0 END) AS redeemed, "
            + "SUM(CASE WHEN status = 'VOID' THEN 1 ELSE 0 END) AS voided, "
            + "SUM(CASE WHEN status = 'ISSUED' THEN 1 ELSE 0 END) AS waiting "
            + "FROM coupon WHERE session_id = ? AND offer_id IS NOT NULL GROUP BY offer_id",
            sessionId);
    }
}
