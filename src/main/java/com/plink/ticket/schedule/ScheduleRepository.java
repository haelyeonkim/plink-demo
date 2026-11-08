package com.plink.ticket.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The days an event runs and the passes that say which of them a ticket admits. */
@Repository
public class ScheduleRepository {
    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One day of the event; times are "HH:mm" on the venue's clock, or null for the whole day. */
    public record Day(LocalDate date, String label, String opensAt, String closesAt, boolean closed) {}

    /** A pass; {@code days} null admits every day of the event. */
    public record Pass(long id, long sessionId, String name, List<LocalDate> days, int position) {
        public boolean admits(LocalDate date) { return days == null || days.contains(date); }
    }

    private static final RowMapper<Day> DAY = (rs, row) -> new Day(rs.getDate("day_date").toLocalDate(),
        rs.getString("label"), rs.getString("opens_at"), rs.getString("closes_at"), rs.getBoolean("closed"));

    private static final RowMapper<Pass> PASS = (rs, row) -> {
        String days = rs.getString("days");
        return new Pass(rs.getLong("id"), rs.getLong("session_id"), rs.getString("name"),
            days == null ? null : days.isBlank() ? List.of()
                : Arrays.stream(days.split(",")).map(LocalDate::parse).toList(),
            rs.getInt("position"));
    };

    public List<Day> days(long sessionId) {
        return jdbc.query("SELECT * FROM event_day WHERE session_id = ? ORDER BY day_date", DAY, sessionId);
    }

    public void replaceDays(long sessionId, List<Day> days) {
        jdbc.update("DELETE FROM event_day WHERE session_id = ?", sessionId);
        for (Day day : days) {
            jdbc.update("INSERT INTO event_day (session_id, day_date, label, opens_at, closes_at, closed) "
                + "VALUES (?, ?, ?, ?, ?, ?)", sessionId, Date.valueOf(day.date()), day.label(),
                day.opensAt(), day.closesAt(), day.closed());
        }
    }

    public List<Pass> passes(long sessionId) {
        return jdbc.query("SELECT * FROM ticket_pass WHERE session_id = ? ORDER BY position, id", PASS, sessionId);
    }

    public Optional<Pass> pass(long sessionId, long id) {
        return jdbc.query("SELECT * FROM ticket_pass WHERE session_id = ? AND id = ?", PASS, sessionId, id)
            .stream().findFirst();
    }

    public long insertPass(long sessionId, String name, List<LocalDate> days) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO ticket_pass (session_id, name, days, position) "
                + "VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM ticket_pass WHERE session_id = ?))",
                new String[] { "id" });
            ps.setLong(1, sessionId);
            ps.setString(2, name);
            ps.setString(3, join(days));
            ps.setLong(4, sessionId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updatePass(long id, String name, List<LocalDate> days) {
        jdbc.update("UPDATE ticket_pass SET name = ?, days = ? WHERE id = ?", name, join(days), id);
    }

    public void deletePass(long id) {
        jdbc.update("DELETE FROM ticket_pass WHERE id = ?", id);
    }

    /** How many tickets carry each pass of the event. */
    public Map<Long, Integer> issuedByPass(long sessionId) {
        return jdbc.query("SELECT pass_id, COUNT(*) AS n FROM ticket WHERE session_id = ? AND pass_id IS NOT NULL "
            + "GROUP BY pass_id", rs -> {
                Map<Long, Integer> out = new java.util.HashMap<>();
                while (rs.next()) out.put(rs.getLong("pass_id"), rs.getInt("n"));
                return out;
            }, sessionId);
    }

    private static String join(List<LocalDate> days) {
        return days == null ? null : days.stream().sorted().map(LocalDate::toString).collect(Collectors.joining(","));
    }
}
