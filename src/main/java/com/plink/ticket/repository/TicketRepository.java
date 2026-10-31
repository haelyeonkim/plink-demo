package com.plink.ticket.repository;

import com.plink.ticket.model.Ticket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TicketRepository {
    private final JdbcTemplate jdbc;
    public TicketRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static final RowMapper<Ticket> MAPPER = (rs, row) -> {
        Ticket t = new Ticket();
        t.id = rs.getLong("id");
        t.sessionId = rs.getLong("session_id");
        long holder = rs.getLong("holder_id");
        t.holderId = rs.wasNull() ? null : holder;
        t.ticketRef = rs.getString("ticket_ref");
        t.seat = rs.getString("seat");
        t.tier = rs.getString("tier");
        t.status = rs.getString("status");
        t.issuedToEmail = rs.getString("issued_to_email");
        t.holderEmail = rs.getString("holder_email");
        t.boundAt = rs.getTimestamp("bound_at");
        t.claimExpiresAt = rs.getTimestamp("claim_expires_at");
        t.createdAt = rs.getTimestamp("created_at");
        t.transferCount = rs.getInt("transfer_count");
        t.reissueCount = rs.getInt("reissue_count");
        t.phone = rs.getString("phone");
        t.deliveredVia = rs.getString("delivered_via");
        t.deliveredAt = rs.getTimestamp("delivered_at");
        t.tokenCipher = rs.getString("token_cipher");
        t.attributes = rs.getString("attributes");
        long invitedBy = rs.getLong("invited_by");
        t.invitedBy = rs.wasNull() ? null : invitedBy;
        t.invitee = rs.getBoolean("invitee");
        return t;
    };

    public long insert(long sessionId, String ticketRef, String tokenHmac, String tokenCipher,
            String seat, String tier, String attributes, String issuedToEmail, String phone,
            Timestamp claimExpiresAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ticket (session_id, ticket_ref, token_hmac, token_cipher, seat, tier, "
                + "attributes, issued_to_email, phone, claim_expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new String[] { "id" });
            ps.setLong(1, sessionId);
            ps.setString(2, ticketRef);
            ps.setString(3, tokenHmac);
            ps.setString(4, tokenCipher);
            ps.setString(5, seat);
            ps.setString(6, tier);
            ps.setString(7, attributes);
            ps.setString(8, issuedToEmail);
            ps.setString(9, phone);
            ps.setTimestamp(10, claimExpiresAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Marks a ticket just inserted as issued on a holder's invitation. */
    public void markInvited(long id, long invitedBy) {
        jdbc.update("UPDATE ticket SET invited_by = ?, invitee = TRUE WHERE id = ?", invitedBy, id);
    }

    /** The tickets a holder has invited, whatever became of them, oldest first. */
    public List<Ticket> findInvitedBy(long inviterId) {
        return jdbc.query("SELECT * FROM ticket WHERE invited_by = ? ORDER BY id", MAPPER, inviterId);
    }

    /** How many each of these tickets has invited: what the console's list shows beside an inviter. */
    public Map<Long, Integer> inviteCounts(java.util.Collection<Long> inviterIds) {
        Map<Long, Integer> counts = new java.util.HashMap<>();
        List<Long> ids = new ArrayList<>(inviterIds);
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            jdbc.query("SELECT invited_by, COUNT(*) AS n FROM ticket WHERE invited_by IN ("
                + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ") GROUP BY invited_by",
                rs -> { counts.put(rs.getLong("invited_by"), rs.getInt("n")); }, chunk.toArray());
        }
        return counts;
    }

    public List<Ticket> findAllById(java.util.Collection<Long> ids) {
        List<Ticket> found = new ArrayList<>();
        List<Long> wanted = new ArrayList<>(new java.util.TreeSet<>(ids));
        for (int from = 0; from < wanted.size(); from += 500) {
            List<Long> chunk = wanted.subList(from, Math.min(wanted.size(), from + 500));
            found.addAll(jdbc.query("SELECT * FROM ticket WHERE id IN ("
                + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")", MAPPER, chunk.toArray()));
        }
        return found;
    }

    /**
     * Live tickets already issued to an address for this event. Revoked ones are left
     * out: cancelling a ticket is how an organiser frees the address up again.
     */
    public List<Ticket> findLiveBySessionAndEmail(long sessionId, String email) {
        return jdbc.query("SELECT * FROM ticket WHERE session_id = ? AND LOWER(issued_to_email) = ? "
            + "AND status <> 'REVOKED'", MAPPER, sessionId, email.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Which of these addresses already hold a live ticket in the event, lower-cased: what
     * a list upload would be refused for, said before anything is sent.
     */
    public java.util.Set<String> liveEmailsAmong(long sessionId, java.util.Collection<String> emails) {
        java.util.Set<String> found = new java.util.HashSet<>();
        List<String> wanted = emails.stream().filter(java.util.Objects::nonNull)
            .map(email -> email.trim().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
        for (int from = 0; from < wanted.size(); from += 500) {
            List<String> chunk = wanted.subList(from, Math.min(wanted.size(), from + 500));
            List<Object> args = new ArrayList<>();
            args.add(sessionId);
            args.addAll(chunk);
            jdbc.query("SELECT LOWER(issued_to_email) AS email FROM ticket WHERE session_id = ? "
                + "AND status <> 'REVOKED' AND LOWER(issued_to_email) IN ("
                + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")",
                rs -> { found.add(rs.getString("email")); }, args.toArray());
        }
        return found;
    }

    /**
     * Removes the ticket itself. Everything hanging off it - its passkey, presence,
     * transfers, grants and ledger rows - is cascaded by the schema, which is why this
     * is the console's "delete" and revoking is what keeps the record.
     */
    public void delete(long id) {
        jdbc.update("DELETE FROM ticket WHERE id = ?", id);
    }

    public Optional<Ticket> findByTokenHmac(String tokenHmac) {
        return jdbc.query("SELECT * FROM ticket WHERE token_hmac = ?", MAPPER, tokenHmac).stream().findFirst();
    }

    public Optional<Ticket> findByRef(String ticketRef) {
        return jdbc.query("SELECT * FROM ticket WHERE ticket_ref = ?", MAPPER, ticketRef).stream().findFirst();
    }

    public Optional<Ticket> findById(long id) {
        return jdbc.query("SELECT * FROM ticket WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Row lock used by the admission path so concurrent scans serialise. */
    public Optional<Ticket> lockById(long id) {
        return jdbc.query("SELECT * FROM ticket WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    /**
     * Locks several tickets, always in id order, so two batches over overlapping tickets
     * queue behind each other instead of each holding what the other wants next.
     */
    public List<Ticket> lockAll(java.util.Collection<Long> ids) {
        List<Long> sorted = new java.util.ArrayList<>(new java.util.TreeSet<>(ids));
        List<Ticket> locked = new java.util.ArrayList<>();
        for (int from = 0; from < sorted.size(); from += 500) {
            List<Long> chunk = sorted.subList(from, Math.min(from + 500, sorted.size()));
            String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            locked.addAll(jdbc.query("SELECT * FROM ticket WHERE id IN (" + marks + ") ORDER BY id FOR UPDATE",
                MAPPER, chunk.toArray()));
        }
        return locked;
    }

    public List<Ticket> findBySession(long sessionId) {
        return jdbc.query("SELECT * FROM ticket WHERE session_id = ? ORDER BY id", MAPPER, sessionId);
    }

    /** What the list of events shows for each one, counted for all events at once. */
    public record ListCounts(long issued, long bound, long inside) {}

    public Map<Long, ListCounts> listCounts() {
        Map<Long, long[]> tally = new java.util.HashMap<>();
        jdbc.query("SELECT session_id, COUNT(*) AS issued, "
            + "SUM(CASE WHEN status = 'BOUND' THEN 1 ELSE 0 END) AS bound "
            + "FROM ticket GROUP BY session_id",
            rs -> { tally.put(rs.getLong("session_id"),
                new long[] { rs.getLong("issued"), rs.getLong("bound"), 0 }); });
        jdbc.query("SELECT session_id, COUNT(*) AS inside FROM ticket_presence "
            + "WHERE state = 'INSIDE' GROUP BY session_id",
            rs -> { tally.computeIfAbsent(rs.getLong("session_id"), id -> new long[3])[2] = rs.getLong("inside"); });
        Map<Long, ListCounts> result = new java.util.HashMap<>();
        tally.forEach((id, n) -> result.put(id, new ListCounts(n[0], n[1], n[2])));
        return result;
    }

    /** The ids of every ticket in a session that can still be used, and nothing else. */
    public List<Long> liveIds(long sessionId) {
        return jdbc.queryForList("SELECT id FROM ticket WHERE session_id = ? AND status <> 'REVOKED' "
            + "ORDER BY id", Long.class, sessionId);
    }

    /**
     * What the console's 발급 현황 can narrow the list down to. They match the labels on
     * the rows, so choosing one shows exactly the rows wearing that label.
     */
    public enum Filter { ALL, UNCLAIMED, BOUND, INSIDE, REVOKED, LIVE }

    /** One page of a session's tickets, oldest first. */
    public List<Ticket> search(long sessionId, Filter filter, String query, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(sessionId, filter, query, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT t.* FROM ticket t" + where + " ORDER BY t.id LIMIT ? OFFSET ?",
            MAPPER, args.toArray());
    }

    public long count(long sessionId, Filter filter, String query) {
        List<Object> args = new ArrayList<>();
        Long found = jdbc.queryForObject("SELECT COUNT(*) FROM ticket t" + where(sessionId, filter, query, args),
            Long.class, args.toArray());
        return found == null ? 0 : found;
    }

    private static String where(long sessionId, Filter filter, String query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE t.session_id = ?");
        args.add(sessionId);
        switch (filter == null ? Filter.ALL : filter) {
            case UNCLAIMED -> sql.append(" AND t.status = 'ISSUED' AND t.holder_email IS NULL");
            case BOUND -> sql.append(" AND t.status = 'BOUND'");
            case REVOKED -> sql.append(" AND t.status = 'REVOKED'");
            case LIVE -> sql.append(" AND t.status <> 'REVOKED'");
            case INSIDE -> sql.append(" AND EXISTS (SELECT 1 FROM ticket_presence p "
                + "WHERE p.ticket_id = t.id AND p.state = 'INSIDE')");
            case ALL -> { }
        }
        if (query != null) {
            String like = Search.like(query);
            sql.append(" AND (LOWER(t.ticket_ref) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.seat, '')) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.tier, '')) LIKE ? ESCAPE '\\'"
                + " OR LOWER(t.issued_to_email) LIKE ? ESCAPE '\\'"
                + " OR LOWER(COALESCE(t.holder_email, '')) LIKE ? ESCAPE '\\'"
                + " OR COALESCE(t.phone, '') LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 6; i++) args.add(like);
        }
        return sql.toString();
    }

    /** How many tickets wear each label, counted in one pass. */
    public Map<String, Object> counts(long sessionId) {
        return jdbc.queryForMap(
            "SELECT COUNT(*) AS all_count, "
            + "SUM(CASE WHEN status = 'ISSUED' AND holder_email IS NULL THEN 1 ELSE 0 END) AS unclaimed, "
            + "SUM(CASE WHEN status = 'BOUND' THEN 1 ELSE 0 END) AS bound, "
            + "SUM(CASE WHEN status = 'REVOKED' THEN 1 ELSE 0 END) AS revoked, "
            + "SUM(CASE WHEN status <> 'REVOKED' THEN 1 ELSE 0 END) AS live, "
            + "(SELECT COUNT(*) FROM ticket_presence p WHERE p.session_id = ? AND p.state = 'INSIDE') AS inside "
            + "FROM ticket WHERE session_id = ?", sessionId, sessionId);
    }

    /**
     * The field values already on tickets, without the tickets. The issue form needs to
     * know a seat is taken; it does not need the other two thousand rows to find out.
     */
    public List<FieldValues> valuesInUse(long sessionId) {
        // Read as strings: attributes is a TEXT column, which some drivers hand back as a
        // large-object handle rather than the text in it.
        return jdbc.query("SELECT seat, tier, attributes FROM ticket WHERE session_id = ?",
            (rs, row) -> new FieldValues(rs.getString("seat"), rs.getString("tier"), rs.getString("attributes")),
            sessionId);
    }

    public record FieldValues(String seat, String tier, String attributes) {}

    public void bind(long id, long holderId, String holderEmail) {
        jdbc.update("UPDATE ticket SET status = 'BOUND', holder_id = ?, holder_email = ?, "
            + "bound_at = CURRENT_TIMESTAMP, claim_expires_at = NULL WHERE id = ?",
            holderId, holderEmail, id);
    }

    /**
     * Detaches the ticket from its holder without touching their credential: the person
     * keeps the passkey their other tickets depend on.
     */
    public void unbind(long id) {
        jdbc.update("UPDATE ticket SET holder_id = NULL WHERE id = ?", id);
    }

    public void recordDelivery(long id, String via) {
        jdbc.update("UPDATE ticket SET delivered_via = ?, delivered_at = CURRENT_TIMESTAMP WHERE id = ?",
            via, id);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE ticket SET status = ? WHERE id = ?", status, id);
    }

    /**
     * Hands the ticket to the recipient: their claim token becomes the ticket's own, so
     * the sender's URL stops resolving from this moment.
     */
    public void completeTransfer(long id, String tokenHmac, String tokenCipher, long holderId,
            String holderEmail) {
        jdbc.update("UPDATE ticket SET token_hmac = ?, token_cipher = ?, holder_id = ?, holder_email = ?, "
            + "issued_to_email = ?, status = 'BOUND', bound_at = CURRENT_TIMESTAMP, "
            + "claim_expires_at = NULL, transfer_count = transfer_count + 1 WHERE id = ?",
            tokenHmac, tokenCipher, holderId, holderEmail, holderEmail, id);
    }

    /**
     * Transfer and lost-device recovery both rotate the token, which kills the old URL.
     * Presence and re-entry counters are deliberately left alone: the holder's movements
     * so far still happened. A revoked ticket stays revoked: a new link is not the
     * organiser switching it back on.
     */
    public void rotateToken(long id, String tokenHmac, String tokenCipher, String issuedToEmail,
            Timestamp claimExpiresAt, boolean countAsReissue) {
        jdbc.update("UPDATE ticket SET token_hmac = ?, token_cipher = ?, issued_to_email = ?, "
            + "claim_expires_at = ?, "
            + "status = CASE WHEN status = 'REVOKED' THEN 'REVOKED' ELSE 'ISSUED' END, "
            + "holder_id = NULL, holder_email = NULL, "
            + "bound_at = NULL, reissue_count = reissue_count + ? WHERE id = ?",
            tokenHmac, tokenCipher, issuedToEmail, claimExpiresAt, countAsReissue ? 1 : 0, id);
    }
}
