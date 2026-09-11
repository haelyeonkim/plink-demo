package com.plink.ticket.repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * Single-use rotating-code nonces. The primary key does the work: a replayed code
 * loses the insert race and is rejected. Redis can take this over under load.
 */
@Repository
public class NonceRepository {
    private final JdbcTemplate jdbc;
    public NonceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** @return true when this nonce had not been used before. */
    public boolean tryUse(String nonce) {
        try {
            jdbc.update("INSERT INTO qr_nonce (nonce) VALUES (?)", nonce);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    public int purgeBefore(Timestamp cutoff) {
        return jdbc.update("DELETE FROM qr_nonce WHERE used_at < ?", cutoff);
    }
}
