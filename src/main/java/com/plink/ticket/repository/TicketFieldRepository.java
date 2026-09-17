package com.plink.ticket.repository;

import com.plink.ticket.model.TicketField;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TicketFieldRepository {
    private final JdbcTemplate jdbc;

    public TicketFieldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TicketField> MAPPER = (rs, row) -> {
        TicketField field = new TicketField();
        field.id = rs.getLong("id");
        field.sessionId = rs.getLong("session_id");
        field.label = rs.getString("label");
        field.kind = rs.getString("kind");
        field.valuesCsv = rs.getString("values_csv");
        field.position = rs.getInt("position");
        return field;
    };

    public List<TicketField> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM ticket_field WHERE session_id = ? ORDER BY position, id",
            MAPPER, sessionId);
    }

    public Optional<TicketField> findById(long id) {
        return jdbc.query("SELECT * FROM ticket_field WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long insert(long sessionId, String label, String kind, String valuesCsv) {
        int position = jdbc.queryForObject(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM ticket_field WHERE session_id = ?",
            Integer.class, sessionId);
        jdbc.update("INSERT INTO ticket_field (session_id, label, kind, values_csv, position) "
            + "VALUES (?, ?, ?, ?, ?)", sessionId, label, kind, valuesCsv, position);
        return jdbc.queryForObject("SELECT id FROM ticket_field WHERE session_id = ? AND label = ?",
            Long.class, sessionId, label);
    }

    public void updateValues(long id, String valuesCsv) {
        jdbc.update("UPDATE ticket_field SET values_csv = ? WHERE id = ?", valuesCsv, id);
    }

    public void rename(long id, String label) {
        jdbc.update("UPDATE ticket_field SET label = ? WHERE id = ?", label, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM ticket_field WHERE id = ?", id);
    }
}
