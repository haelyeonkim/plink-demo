package com.plink.ticket.repository;

import com.plink.ticket.model.BoothManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BoothManagerRepository {
    private final JdbcTemplate jdbc;

    public BoothManagerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<BoothManager> MAPPER = (rs, row) -> {
        BoothManager m = new BoothManager();
        m.id = rs.getLong("id");
        m.sessionId = rs.getLong("session_id");
        m.boothId = rs.getLong("booth_id");
        m.email = rs.getString("email");
        m.tokenHash = rs.getString("token_hash");
        long holder = rs.getLong("holder_id");
        m.holderId = rs.wasNull() ? null : holder;
        m.status = rs.getString("status");
        m.gateId = rs.getString("gate_id");
        m.invitedAt = rs.getTimestamp("invited_at");
        m.claimedAt = rs.getTimestamp("claimed_at");
        m.lastSeenAt = rs.getTimestamp("last_seen_at");
        return m;
    };

    public long insert(long sessionId, long boothId, String email, String tokenHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO booth_manager (session_id, booth_id, email, token_hash) VALUES (?, ?, ?, ?)",
                new String[] { "id" });
            ps.setLong(1, sessionId);
            ps.setLong(2, boothId);
            ps.setString(3, email);
            ps.setString(4, tokenHash);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<BoothManager> findById(long id) {
        return jdbc.query("SELECT * FROM booth_manager WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<BoothManager> lockById(long id) {
        return jdbc.query("SELECT * FROM booth_manager WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public Optional<BoothManager> findByTokenHash(String hash) {
        return jdbc.query("SELECT * FROM booth_manager WHERE token_hash = ?", MAPPER, hash).stream().findFirst();
    }

    public Optional<BoothManager> findByBoothAndEmail(long boothId, String email) {
        return jdbc.query("SELECT * FROM booth_manager WHERE booth_id = ? AND email = ?", MAPPER, boothId, email)
            .stream().findFirst();
    }

    public List<BoothManager> findByBooth(long boothId) {
        return jdbc.query("SELECT * FROM booth_manager WHERE booth_id = ? ORDER BY id", MAPPER, boothId);
    }

    /** How many people can still open each booth, invited or signed in, for the console's booth list. */
    public Map<Long, Integer> countOpenBySession(long sessionId) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT booth_id, COUNT(*) AS n FROM booth_manager WHERE session_id = ? "
            + "AND status <> 'REVOKED' GROUP BY booth_id",
            rs -> { counts.put(rs.getLong("booth_id"), rs.getInt("n")); }, sessionId);
        return counts;
    }

    /**
     * Binds the person who answered the invitation. Only an invitation nobody holds
     * moves: of two phones finishing at once, the second finds nothing to update. (A
     * holder row deleted from under an active manager leaves it unheld, and claimable.)
     */
    public boolean claim(long id, long holderId, Timestamp at) {
        return jdbc.update("UPDATE booth_manager SET holder_id = ?, status = 'ACTIVE', claimed_at = ?, "
            + "last_seen_at = ? WHERE id = ? AND holder_id IS NULL AND status <> 'REVOKED'",
            holderId, at, at, id) > 0;
    }

    /** A new link, and the invitation open again: whoever took up the old one has to prove it anew. */
    public void reopen(long id, String tokenHash, Timestamp at) {
        jdbc.update("UPDATE booth_manager SET token_hash = ?, holder_id = NULL, status = 'INVITED', "
            + "invited_at = ?, claimed_at = NULL WHERE id = ?", tokenHash, at, id);
    }

    /** Which booth terminal the manager answers for; null takes it away. */
    public void setGate(long id, String gateId) {
        jdbc.update("UPDATE booth_manager SET gate_id = ? WHERE id = ?", gateId, id);
    }

    /** A manager as a terminal card lists them. */
    public record Assigned(long managerId, String email, String status) {}

    /** Who answers for each terminal of an event, leaving out those who were shut out. */
    public Map<String, List<Assigned>> assignedBySession(long sessionId) {
        Map<String, List<Assigned>> result = new HashMap<>();
        jdbc.query("SELECT id, email, status, gate_id FROM booth_manager WHERE session_id = ? "
            + "AND gate_id IS NOT NULL AND status <> 'REVOKED' ORDER BY id",
            rs -> {
                result.computeIfAbsent(rs.getString("gate_id"), key -> new ArrayList<>())
                    .add(new Assigned(rs.getLong("id"), rs.getString("email"), rs.getString("status")));
            }, sessionId);
        return result;
    }

    public boolean revoke(long id) {
        return jdbc.update("UPDATE booth_manager SET status = 'REVOKED' WHERE id = ? AND status <> 'REVOKED'", id) > 0;
    }

    /** Recorded at most once a minute or so: a busy console should not write on every read. */
    public void touch(long id, Timestamp at, Timestamp ifBefore) {
        jdbc.update("UPDATE booth_manager SET last_seen_at = ? WHERE id = ? "
            + "AND (last_seen_at IS NULL OR last_seen_at < ?)", at, id, ifBefore);
    }

    /** A ticket as the booth's list shows it: who, whether they are in, how many of an offer they hold. */
    public record TicketRow(long ticketId, String ticketRef, String email, boolean inside, int held) {}

    /**
     * One page of an event's usable tickets. The search reads the reference, seat and
     * address, so a manager can find the person in front of them; the address itself is
     * masked on the way out.
     */
    public List<TicketRow> searchTickets(long sessionId, String query, boolean insideOnly, Long offerId,
            int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String select = "SELECT t.id, t.ticket_ref, COALESCE(t.holder_email, t.issued_to_email) AS email, "
            + "CASE WHEN p.state = 'INSIDE' THEN 1 ELSE 0 END AS inside, "
            + (offerId == null ? "0" : "(SELECT COUNT(*) FROM coupon c WHERE c.ticket_id = t.id "
                + "AND c.offer_id = ? AND c.status <> 'VOID')") + " AS held";
        if (offerId != null) args.add(offerId);
        String where = ticketWhere(sessionId, query, insideOnly, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query(select + where + " ORDER BY t.id LIMIT ? OFFSET ?",
            (rs, row) -> new TicketRow(rs.getLong("id"), rs.getString("ticket_ref"), rs.getString("email"),
                rs.getInt("inside") == 1, rs.getInt("held")), args.toArray());
    }

    public long countTickets(long sessionId, String query, boolean insideOnly) {
        List<Object> args = new ArrayList<>();
        Long found = jdbc.queryForObject("SELECT COUNT(*)" + ticketWhere(sessionId, query, insideOnly, args),
            Long.class, args.toArray());
        return found == null ? 0 : found;
    }

    /** Usable tickets that are inside right now. */
    public List<Long> insideIds(long sessionId) {
        return jdbc.queryForList("SELECT t.id FROM ticket t JOIN ticket_presence p ON p.ticket_id = t.id "
            + "WHERE t.session_id = ? AND t.status <> 'REVOKED' AND p.state = 'INSIDE' ORDER BY t.id",
            Long.class, sessionId);
    }

    private static String ticketWhere(long sessionId, String query, boolean insideOnly, List<Object> args) {
        StringBuilder sql = new StringBuilder(" FROM ticket t LEFT JOIN ticket_presence p ON p.ticket_id = t.id "
            + "WHERE t.session_id = ? AND t.status <> 'REVOKED'");
        args.add(sessionId);
        if (insideOnly) sql.append(" AND p.state = 'INSIDE'");
        if (query != null) {
            String like = Search.like(query);
            sql.append(" AND (LOWER(t.ticket_ref) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.seat, '')) LIKE ? ESCAPE '\\'"
                + " OR LOWER(t.issued_to_email) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.holder_email, '')) LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 4; i++) args.add(like);
        }
        return sql.toString();
    }

    /** How many different tickets hold each of a booth's offers, leaving out what was taken back. */
    public Map<Long, Integer> holdersByOffer(long boothId) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT offer_id, COUNT(DISTINCT ticket_id) AS n FROM coupon WHERE booth_id = ? "
            + "AND offer_id IS NOT NULL AND status <> 'VOID' GROUP BY offer_id",
            rs -> { counts.put(rs.getLong("offer_id"), rs.getInt("n")); }, boothId);
        return counts;
    }
}
