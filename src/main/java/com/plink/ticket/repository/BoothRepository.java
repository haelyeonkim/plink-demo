package com.plink.ticket.repository;

import com.plink.ticket.model.Booth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class BoothRepository {
    private final JdbcTemplate jdbc;

    public BoothRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Booth> MAPPER = (rs, row) -> {
        Booth booth = new Booth();
        booth.id = rs.getLong("id");
        booth.sessionId = rs.getLong("session_id");
        booth.name = rs.getString("name");
        booth.note = rs.getString("note");
        booth.createdAt = rs.getTimestamp("created_at");
        return booth;
    };

    public long insert(long sessionId, String name, String note) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO booth (session_id, name, note) VALUES (?, ?, ?)", new String[] { "id" });
            ps.setLong(1, sessionId);
            ps.setString(2, name);
            ps.setString(3, note);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<Booth> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM booth WHERE session_id = ? ORDER BY name", MAPPER, sessionId);
    }

    public Optional<Booth> findById(long id) {
        return jdbc.query("SELECT * FROM booth WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void rename(long id, String name, String note) {
        jdbc.update("UPDATE booth SET name = ?, note = ? WHERE id = ?", name, note, id);
    }

    /** Takes the booth's coupons with it, which is why the console asks first. */
    public int delete(long id) {
        return jdbc.update("DELETE FROM booth WHERE id = ?", id);
    }
}
