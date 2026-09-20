package com.plink.ticket.repository;

import com.plink.ticket.model.Gate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class GateRepository {
    private final JdbcTemplate jdbc;
    public GateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Gate> MAPPER = (rs, row) -> {
        Gate g = new Gate();
        g.id = rs.getString("id");
        g.sessionId = rs.getLong("session_id");
        g.label = rs.getString("label");
        g.zone = rs.getString("zone");
        g.direction = rs.getString("direction");
        g.tokenHmac = rs.getString("token_hmac");
        g.tokenCipher = rs.getString("token_cipher");
        g.boundDevice = rs.getString("bound_device");
        g.boundAt = rs.getTimestamp("bound_at");
        g.lastSeenAt = rs.getTimestamp("last_seen_at");
        g.setupTokenHmac = rs.getString("setup_token_hmac");
        g.setupTokenCipher = rs.getString("setup_token_cipher");
        g.setupCodeHash = rs.getString("setup_code_hash");
        g.setupCodeCipher = rs.getString("setup_code_cipher");
        g.setupExpiresAt = rs.getTimestamp("setup_expires_at");
        g.setupAttempts = rs.getInt("setup_attempts");
        g.tokenExpiresAt = rs.getTimestamp("token_expires_at");
        return g;
    };

    public Optional<Gate> findById(String id) {
        return jdbc.query("SELECT * FROM gate WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Gate> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM gate WHERE session_id = ? ORDER BY id", MAPPER, sessionId);
    }

    /** Name, place and direction: everything about a terminal that staff can correct. */
    public void updateDetails(String id, String label, String zone, String direction) {
        jdbc.update("UPDATE gate SET label = ?, zone = ?, direction = ? WHERE id = ?",
            label, zone, direction, id);
    }

    public void insert(String id, long sessionId, String label, String zone, String direction,
            String tokenHmac, String tokenCipher, java.sql.Timestamp tokenExpiresAt) {
        jdbc.update("INSERT INTO gate (id, session_id, label, zone, direction, token_hmac, token_cipher, "
            + "token_expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, sessionId, label, zone, direction, tokenHmac, tokenCipher, tokenExpiresAt);
    }

    /** Claims the gate for one terminal. */
    public void bindDevice(String id, String deviceId) {
        jdbc.update("UPDATE gate SET bound_device = ?, bound_at = CURRENT_TIMESTAMP, "
            + "last_seen_at = CURRENT_TIMESTAMP WHERE id = ?", deviceId, id);
    }

    /** Frees the gate so another terminal can take it, from the console. */
    public void releaseDevice(String id) {
        jdbc.update("UPDATE gate SET bound_device = NULL, bound_at = NULL WHERE id = ?", id);
    }

    public void rotateToken(String id, String tokenHmac, String tokenCipher,
            java.sql.Timestamp tokenExpiresAt) {
        jdbc.update("UPDATE gate SET token_hmac = ?, token_cipher = ?, token_expires_at = ?, "
            + "bound_device = NULL, bound_at = NULL WHERE id = ?",
            tokenHmac, tokenCipher, tokenExpiresAt, id);
    }

    /**
     * Pushes the end date out without touching the token, so the tablet that is holding
     * the gate carries on: renewing is a decision about time, not about credentials.
     */
    public void renewToken(String id, java.sql.Timestamp tokenExpiresAt) {
        jdbc.update("UPDATE gate SET token_expires_at = ? WHERE id = ?", tokenExpiresAt, id);
    }

    public java.util.Optional<Gate> findBySetupToken(String setupTokenHmac) {
        return jdbc.query("SELECT * FROM gate WHERE setup_token_hmac = ?", MAPPER, setupTokenHmac)
            .stream().findFirst();
    }

    public void saveSetup(String id, String tokenHmac, String tokenCipher, String codeHash,
            String codeCipher, java.sql.Timestamp expiresAt) {
        jdbc.update("UPDATE gate SET setup_token_hmac = ?, setup_token_cipher = ?, setup_code_hash = ?, "
            + "setup_code_cipher = ?, setup_expires_at = ?, setup_attempts = 0 WHERE id = ?",
            tokenHmac, tokenCipher, codeHash, codeCipher, expiresAt, id);
    }

    public void countSetupAttempt(String id) {
        jdbc.update("UPDATE gate SET setup_attempts = setup_attempts + 1 WHERE id = ?", id);
    }

    public void touch(String id) {
        jdbc.update("UPDATE gate SET last_seen_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
}
