package com.plink.ticket.repository;

import com.plink.ticket.model.Ticket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class TicketRepository {
    private final JdbcTemplate jdbc;
    public TicketRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static final RowMapper<Ticket> MAPPER = (rs, row) -> {
        Ticket t = new Ticket();
        t.id = rs.getLong("id");
        t.sessionId = rs.getLong("session_id");
        t.ticketRef = rs.getString("ticket_ref");
        t.seat = rs.getString("seat");
        t.tier = rs.getString("tier");
        t.status = rs.getString("status");
        t.issuedToEmail = rs.getString("issued_to_email");
        t.holderEmail = rs.getString("holder_email");
        t.boundAt = rs.getTimestamp("bound_at");
        t.claimExpiresAt = rs.getTimestamp("claim_expires_at");
        t.createdAt = rs.getTimestamp("created_at");
        t.transferCount = rs.getInt("transfer_count");
        t.reissueCount = rs.getInt("reissue_count");
        return t;
    };

    public long insert(long sessionId, String ticketRef, String tokenHmac, String seat, String tier,
            String issuedToEmail, Timestamp claimExpiresAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ticket (session_id, ticket_ref, token_hmac, seat, tier, issued_to_email, claim_expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", new String[] { "id" });
            ps.setLong(1, sessionId);
            ps.setString(2, ticketRef);
            ps.setString(3, tokenHmac);
            ps.setString(4, seat);
            ps.setString(5, tier);
            ps.setString(6, issuedToEmail);
            ps.setTimestamp(7, claimExpiresAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Ticket> findByTokenHmac(String tokenHmac) {
        return jdbc.query("SELECT * FROM ticket WHERE token_hmac = ?", MAPPER, tokenHmac).stream().findFirst();
    }

    public Optional<Ticket> findByRef(String ticketRef) {
        return jdbc.query("SELECT * FROM ticket WHERE ticket_ref = ?", MAPPER, ticketRef).stream().findFirst();
    }

    public Optional<Ticket> findById(long id) {
        return jdbc.query("SELECT * FROM ticket WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Row lock used by the admission path so concurrent scans serialise. */
    public Optional<Ticket> lockById(long id) {
        return jdbc.query("SELECT * FROM ticket WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public List<Ticket> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM ticket WHERE session_id = ? ORDER BY id", MAPPER, sessionId);
    }

    public void bind(long id, String holderEmail) {
        jdbc.update("UPDATE ticket SET status = 'BOUND', holder_email = ?, bound_at = CURRENT_TIMESTAMP, "
            + "claim_expires_at = NULL WHERE id = ?", holderEmail, id);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE ticket SET status = ? WHERE id = ?", status, id);
    }

    /**
     * Hands the ticket to the recipient: their claim token becomes the ticket's own, so
     * the sender's URL stops resolving from this moment.
     */
    public void completeTransfer(long id, String tokenHmac, String holderEmail) {
        jdbc.update("UPDATE ticket SET token_hmac = ?, holder_email = ?, issued_to_email = ?, "
            + "status = 'BOUND', bound_at = CURRENT_TIMESTAMP, claim_expires_at = NULL, "
            + "transfer_count = transfer_count + 1 WHERE id = ?",
            tokenHmac, holderEmail, holderEmail, id);
    }

    /**
     * Transfer and lost-device recovery both rotate the token, which kills the old URL.
     * Presence and re-entry counters are deliberately left alone: the holder's movements
     * so far still happened.
     */
    public void rotateToken(long id, String tokenHmac, String issuedToEmail, Timestamp claimExpiresAt,
            boolean countAsReissue) {
        jdbc.update("UPDATE ticket SET token_hmac = ?, issued_to_email = ?, claim_expires_at = ?, "
            + "status = 'ISSUED', holder_email = NULL, bound_at = NULL, "
            + "reissue_count = reissue_count + ? WHERE id = ?",
            tokenHmac, issuedToEmail, claimExpiresAt, countAsReissue ? 1 : 0, id);
    }
}
