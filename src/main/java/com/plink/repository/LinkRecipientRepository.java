package com.plink.repository;

import com.plink.model.LinkRecipient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class LinkRecipientRepository {
    private final JdbcTemplate jdbc;

    public LinkRecipientRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // The holder is joined in: the console shows who actually registered, which is not
    // necessarily the address the invitation was sent to.
    private static final String SELECT =
        "SELECT r.*, h.email AS holder_email FROM link_recipient r "
        + "LEFT JOIN holder h ON h.id = r.holder_id ";

    private static final RowMapper<LinkRecipient> MAPPER = (rs, row) -> {
        LinkRecipient recipient = new LinkRecipient();
        recipient.id = rs.getLong("id");
        recipient.linkId = rs.getLong("link_id");
        recipient.shortCode = rs.getString("short_code");
        recipient.label = rs.getString("label");
        recipient.status = rs.getString("status");
        recipient.viewCount = rs.getInt("view_count");
        recipient.createdAt = rs.getTimestamp("created_at");
        recipient.claimedAt = rs.getTimestamp("claimed_at");
        recipient.email = rs.getString("email");
        long holderId = rs.getLong("holder_id");
        recipient.holderId = rs.wasNull() ? null : holderId;
        recipient.receiverName = rs.getString("holder_email");
        return recipient;
    };

    public List<LinkRecipient> findByLink(long linkId) {
        return jdbc.query(SELECT + "WHERE r.link_id = ? ORDER BY r.id DESC", MAPPER, linkId);
    }

    public Optional<LinkRecipient> findByCode(String code) {
        return jdbc.query(SELECT + "WHERE r.short_code = ?", MAPPER, code).stream().findFirst();
    }

    public Optional<LinkRecipient> findById(long id) {
        return jdbc.query(SELECT + "WHERE r.id = ?", MAPPER, id).stream().findFirst();
    }

    public long insert(long linkId, String shortCode, String email, String label) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO link_recipient (link_id, short_code, email, label) VALUES (?, ?, ?, ?)",
                new String[] { "id" });
            ps.setLong(1, linkId);
            ps.setString(2, shortCode);
            ps.setString(3, email);
            ps.setString(4, label);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Attaches the person who registered, the way a ticket points at its holder. */
    public void bind(long id, long holderId) {
        jdbc.update("UPDATE link_recipient SET holder_id = ?, status = 'BOUND', "
            + "claimed_at = CURRENT_TIMESTAMP WHERE id = ?", holderId, id);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE link_recipient SET status = ? WHERE id = ?", status, id);
    }

    public void incrementViewCount(long id) {
        jdbc.update("UPDATE link_recipient SET view_count = view_count + 1 WHERE id = ?", id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM link_recipient WHERE id = ?", id);
    }

    public int countByLink(long linkId) {
        Integer value = jdbc.queryForObject(
            "SELECT COUNT(*) FROM link_recipient WHERE link_id = ?", Integer.class, linkId);
        return value == null ? 0 : value;
    }

    public int countClaimedByLink(long linkId) {
        Integer value = jdbc.queryForObject(
            "SELECT COUNT(*) FROM link_recipient WHERE link_id = ? AND claimed_at IS NOT NULL",
            Integer.class, linkId);
        return value == null ? 0 : value;
    }
}
