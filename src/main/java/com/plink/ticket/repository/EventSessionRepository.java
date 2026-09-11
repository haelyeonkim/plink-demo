package com.plink.ticket.repository;

import com.plink.ticket.model.EventSession;
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
public class EventSessionRepository {
    private final JdbcTemplate jdbc;
    public EventSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<EventSession> MAPPER = (rs, row) -> {
        EventSession s = new EventSession();
        s.id = rs.getLong("id");
        s.name = rs.getString("name");
        s.venue = rs.getString("venue");
        s.startsAt = rs.getTimestamp("starts_at");
        s.gateOpensAt = rs.getTimestamp("gate_opens_at");
        s.reentryMode = rs.getString("reentry_mode");
        s.reentryMax = rs.getInt("reentry_max");
        s.reentryGraceMinutes = rs.getInt("reentry_grace_minutes");
        s.reentryCooldownSeconds = rs.getInt("reentry_cooldown_seconds");
        s.exitScanRequired = rs.getBoolean("exit_scan_required");
        s.unmatchedExit = rs.getString("unmatched_exit");
        s.autoExitAfterMinutes = rs.getInt("auto_exit_after_minutes");
        s.transferMax = rs.getInt("transfer_max");
        s.transferClosesMinutesBefore = rs.getInt("transfer_closes_minutes_before");
        s.transferAfterFirstEntry = rs.getBoolean("transfer_after_first_entry");
        s.faceRequired = rs.getBoolean("face_required");
        s.reentryRequiresFace = rs.getBoolean("reentry_requires_face");
        s.faceLiveness = rs.getString("face_liveness");
        s.faceChallengeOn = rs.getString("face_challenge_on");
        s.faceRetentionDays = rs.getInt("face_retention_days");
        return s;
    };

    public Optional<EventSession> findById(long id) {
        return jdbc.query("SELECT * FROM event_session WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<EventSession> findAll() {
        return jdbc.query("SELECT * FROM event_session ORDER BY starts_at DESC", MAPPER);
    }

    public long insert(String name, String venue, Timestamp startsAt, Timestamp gateOpensAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO event_session (name, venue, starts_at, gate_opens_at) VALUES (?, ?, ?, ?)",
                new String[] { "id" });
            ps.setString(1, name);
            ps.setString(2, venue);
            ps.setTimestamp(3, startsAt);
            ps.setTimestamp(4, gateOpensAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateTransferPolicy(long id, int transferMax, int closesMinutesBefore, boolean afterFirstEntry) {
        jdbc.update("UPDATE event_session SET transfer_max = ?, transfer_closes_minutes_before = ?, "
            + "transfer_after_first_entry = ? WHERE id = ?", transferMax, closesMinutesBefore, afterFirstEntry, id);
    }

    public void updateFacePolicy(long id, boolean faceRequired, boolean reentryRequiresFace,
            String liveness, String challengeOn, int retentionDays) {
        jdbc.update("UPDATE event_session SET face_required = ?, reentry_requires_face = ?, "
            + "face_liveness = ?, face_challenge_on = ?, face_retention_days = ? WHERE id = ?",
            faceRequired, reentryRequiresFace, liveness, challengeOn, retentionDays, id);
    }

    public void updatePolicy(long id, String reentryMode, int reentryMax, int graceMinutes,
            int cooldownSeconds, boolean exitScanRequired, String unmatchedExit) {
        jdbc.update("UPDATE event_session SET reentry_mode = ?, reentry_max = ?, reentry_grace_minutes = ?, "
            + "reentry_cooldown_seconds = ?, exit_scan_required = ?, unmatched_exit = ? WHERE id = ?",
            reentryMode, reentryMax, graceMinutes, cooldownSeconds, exitScanRequired, unmatchedExit, id);
    }
}
