package com.plink.ticket.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.TreeMap;

/** How many people a holder of each tier may invite. A ticket with no tier is the '' row. */
@Repository
public class InviteQuotaRepository {
    private final JdbcTemplate jdbc;
    public InviteQuotaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Integer> findBySession(long sessionId) {
        Map<String, Integer> quotas = new TreeMap<>();
        jdbc.query("SELECT tier, invites FROM tier_invite_quota WHERE session_id = ?",
            rs -> { quotas.put(rs.getString("tier"), rs.getInt("invites")); }, sessionId);
        return quotas;
    }

    /** What a ticket of this tier may invite: none unless the organiser said otherwise. */
    public int quota(long sessionId, String tier) {
        return jdbc.query("SELECT invites FROM tier_invite_quota WHERE session_id = ? AND tier = ?",
            (rs, row) -> rs.getInt("invites"), sessionId, tier == null ? "" : tier)
            .stream().findFirst().orElse(0);
    }

    /** The event's quotas become exactly these; a tier left out, or at zero, invites nobody. */
    public void replace(long sessionId, Map<String, Integer> quotas) {
        jdbc.update("DELETE FROM tier_invite_quota WHERE session_id = ?", sessionId);
        quotas.forEach((tier, invites) -> {
            if (invites > 0) {
                jdbc.update("INSERT INTO tier_invite_quota (session_id, tier, invites) VALUES (?, ?, ?)",
                    sessionId, tier, invites);
            }
        });
    }
}
