package com.plink.ticket.repository;

import com.plink.ticket.model.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class TransferRepository {
    private final JdbcTemplate jdbc;
    public TransferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Transfer> MAPPER = (rs, row) -> {
        Transfer t = new Transfer();
        t.id = rs.getLong("id");
        t.ticketId = rs.getLong("ticket_id");
        t.fromEmail = rs.getString("from_email");
        t.toEmail = rs.getString("to_email");
        t.toTokenHmac = rs.getString("to_token_hmac");
        t.status = rs.getString("status");
        t.policySnapshot = rs.getString("policy_snapshot");
        t.expiresAt = rs.getTimestamp("expires_at");
        t.acceptedAt = rs.getTimestamp("accepted_at");
        t.closedAt = rs.getTimestamp("closed_at");
        t.createdAt = rs.getTimestamp("created_at");
        return t;
    };

    public void insert(long ticketId, String fromEmail, String toEmail, String toTokenHmac,
            String policySnapshot, String ip, String userAgent, Timestamp expiresAt) {
        jdbc.update("INSERT INTO ticket_transfer (ticket_id, from_email, to_email, to_token_hmac, "
            + "policy_snapshot, requested_ip, requested_ua, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ticketId, fromEmail, toEmail, toTokenHmac, policySnapshot, ip, userAgent, expiresAt);
    }

    public Optional<Transfer> findPendingByTicket(long ticketId) {
        return jdbc.query("SELECT * FROM ticket_transfer WHERE ticket_id = ? AND status = 'PENDING' "
            + "ORDER BY id DESC", MAPPER, ticketId).stream().findFirst();
    }

    public Optional<Transfer> findByClaimToken(String toTokenHmac) {
        return jdbc.query("SELECT * FROM ticket_transfer WHERE to_token_hmac = ?", MAPPER, toTokenHmac)
            .stream().findFirst();
    }

    public List<Transfer> findByTicket(long ticketId) {
        return jdbc.query("SELECT * FROM ticket_transfer WHERE ticket_id = ? ORDER BY id DESC", MAPPER, ticketId);
    }

    public void close(long id, String status) {
        jdbc.update("UPDATE ticket_transfer SET status = ?, closed_at = CURRENT_TIMESTAMP WHERE id = ?",
            status, id);
    }

    public void accept(long id) {
        jdbc.update("UPDATE ticket_transfer SET status = 'ACCEPTED', accepted_at = CURRENT_TIMESTAMP, "
            + "closed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    /** Pending rows whose window has closed; the ticket returns to the sender. */
    public List<Transfer> findExpired() {
        return jdbc.query("SELECT * FROM ticket_transfer WHERE status = 'PENDING' AND expires_at < ?",
            MAPPER, Timestamp.from(java.time.Instant.now()));
    }
}
