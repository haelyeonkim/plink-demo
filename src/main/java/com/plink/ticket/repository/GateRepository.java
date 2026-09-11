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
        return g;
    };

    public Optional<Gate> findById(String id) {
        return jdbc.query("SELECT * FROM gate WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Gate> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM gate WHERE session_id = ? ORDER BY id", MAPPER, sessionId);
    }

    public void insert(String id, long sessionId, String label, String zone, String direction, String tokenHmac) {
        jdbc.update("INSERT INTO gate (id, session_id, label, zone, direction, token_hmac) VALUES (?, ?, ?, ?, ?, ?)",
            id, sessionId, label, zone, direction, tokenHmac);
    }

    public void touch(String id) {
        jdbc.update("UPDATE gate SET last_seen_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
}
