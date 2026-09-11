package com.plink.ticket.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class EmailOtpRepository {
    private final JdbcTemplate jdbc;
    public EmailOtpRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static class Otp {
        public long id;
        public String email, purpose, codeHash;
        public Timestamp expiresAt, consumedAt, createdAt;
        public int attempts;
    }

    private static final RowMapper<Otp> MAPPER = (rs, row) -> {
        Otp o = new Otp();
        o.id = rs.getLong("id");
        o.email = rs.getString("email");
        o.purpose = rs.getString("purpose");
        o.codeHash = rs.getString("code_hash");
        o.expiresAt = rs.getTimestamp("expires_at");
        o.consumedAt = rs.getTimestamp("consumed_at");
        o.createdAt = rs.getTimestamp("created_at");
        o.attempts = rs.getInt("attempts");
        return o;
    };

    public void insert(String email, String purpose, String codeHash, Timestamp expiresAt) {
        jdbc.update("INSERT INTO email_otp (email, purpose, code_hash, expires_at) VALUES (?, ?, ?, ?)",
            email, purpose, codeHash, expiresAt);
    }

    public Optional<Otp> findLatest(String email, String purpose) {
        return jdbc.query("SELECT * FROM email_otp WHERE email = ? AND purpose = ? ORDER BY id DESC",
            MAPPER, email, purpose).stream().findFirst();
    }

    public void incrementAttempts(long id) {
        jdbc.update("UPDATE email_otp SET attempts = attempts + 1 WHERE id = ?", id);
    }

    public void consume(long id) {
        jdbc.update("UPDATE email_otp SET consumed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    /** Invalidate outstanding codes for this purpose so only the newest one can be used. */
    public void consumeAll(String email, String purpose) {
        jdbc.update("UPDATE email_otp SET consumed_at = CURRENT_TIMESTAMP "
            + "WHERE email = ? AND purpose = ? AND consumed_at IS NULL", email, purpose);
    }
}
