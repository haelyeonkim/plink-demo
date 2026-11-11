package com.plink.service;

import com.plink.account.AdminPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/** Private operational data never enters the recipient response or public event snapshots. */
@Service
public class ArtworkRequestInbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public ArtworkRequestInbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Filter(String filter, String search, Long linkId, Long artworkId, String processing,
            String owner, LocalDate from, LocalDate to, boolean withdrawals, String sort, int page) {}
    public record Work(long version, String processingState, String assignee, String internalNote) {}
    private static final String STATE = "CASE WHEN r.inquiry_pending THEN 'inquiry' WHEN r.cancellation_requested THEN 'cancel' WHEN r.purchased THEN 'sold' "
        + "WHEN r.purchase_intent THEN 'purchase' WHEN r.hold_state='HELD' THEN 'held' "
        + "WHEN r.hold_state='PENDING' THEN 'pending' WHEN r.interested THEN 'interested' ELSE 'withdrawn' END";
    private static final String TIME = "COALESCE(CASE WHEN r.cancellation_requested OR r.purchased THEN r.requested_at WHEN r.purchase_intent THEN r.purchase_at "
        + "WHEN r.hold_state IN ('PENDING','HELD') THEN r.hold_requested_at WHEN r.interested THEN r.interest_at END,r.requested_at,r.updated_at)";
    private static final Set<String> STATES = Set.of("all", "active", "inquiry", "cancel", "sold", "purchase", "held", "pending", "interested", "withdrawn");
    private static final Set<String> WORK = Set.of("UNREAD", "REVIEWED", "CONTACTING", "DONE");
    private String scope(Authentication auth, String alias, List<Object> args) {
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal admin) || !(admin.owner || admin.canLinks))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (admin.owner) return "1=1";
        args.add(admin.subject()); return alias + ".owner_sub=?";
    }
    public void check(long id, Authentication auth) {
        List<Object> args = new ArrayList<>();
        String where = scope(auth, "r", args); args.add(id);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM artwork_request r WHERE " + where + " AND r.id=?", Long.class, args.toArray()) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private String identity(String target, String source) {
        return "(" + target + ".recipient_id=" + source + ".recipient_id OR (" + source + ".holder_id IS NOT NULL AND "
            + target + ".holder_id=" + source + ".holder_id))";
    }
    public Map<String, Object> list(Filter f, Authentication auth) {
        if (!STATES.contains(f.filter()) || f.search().length() > 200 || f.page() < 0 || f.page() > 100000
                || !Set.of("oldest", "withdrawals").contains(f.sort()) || !f.processing().isEmpty() && !WORK.contains(f.processing())
                || f.from() != null && f.to() != null && f.from().isAfter(f.to())) throw bad();
        List<Object> args = new ArrayList<>();
        String where = scope(auth, "r", args);
        if (!f.search().isBlank()) {
            where += " AND (LOWER(r.artwork_title) LIKE ? ESCAPE '!' OR LOWER(r.recipient_email) LIKE ? ESCAPE '!' OR LOWER(r.link_title) LIKE ? ESCAPE '!')";
            String term = "%" + f.search().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.addAll(List.of(term, term, term));
        }
        if (f.linkId() != null) { where += " AND r.link_id=?"; args.add(f.linkId()); }
        if (f.artworkId() != null) { where += " AND r.artwork_id=?"; args.add(f.artworkId()); }
        if (!f.processing().isEmpty()) { where += " AND r.processing_state=?"; args.add(f.processing()); }
        if (!f.owner().isEmpty()) { where += " AND r.owner_sub=?"; args.add(f.owner()); }
        if (f.from() != null) { where += " AND " + TIME + ">=?"; args.add(Timestamp.valueOf(f.from().atStartOfDay())); }
        if (f.to() != null) { where += " AND " + TIME + "<?"; args.add(Timestamp.valueOf(f.to().plusDays(1).atStartOfDay())); }
        // Identity is the passkey holder, never a display name or unverified mailbox.
        List<Object> countArgs = new ArrayList<>();
        String countScope = scope(auth, "q", countArgs);
        String withdrawals = "(SELECT COUNT(*) FROM artwork_request_event e JOIN artwork_request q ON q.id=e.request_id WHERE "
            + countScope + " AND " + identity("q", "r") + " AND e.actor_type='RECIPIENT' AND e.action='WITHDRAW')";
        if (f.withdrawals()) { where += " AND " + withdrawals + ">0"; args.addAll(countArgs); }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : STATES) counts.put(state, 0L);
        jdbc.query("SELECT " + STATE + " AS state, COUNT(*) AS amount FROM artwork_request r WHERE " + where + " GROUP BY " + STATE,
            rs -> { counts.put(rs.getString("state"), rs.getLong("amount")); }, args.toArray());
        counts.put("active", counts.get("interested") + counts.get("pending") + counts.get("held") + counts.get("purchase"));
        counts.put("all", counts.get("active") + counts.get("inquiry") + counts.get("cancel") + counts.get("sold") + counts.get("withdrawn"));
        if (f.filter().equals("active")) where += " AND " + STATE + " IN ('interested','pending','held','purchase')";
        else if (!f.filter().equals("all")) { where += " AND " + STATE + "=?"; args.add(f.filter()); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM artwork_request r WHERE " + where, Long.class, args.toArray());
        List<Object> queryArgs = new ArrayList<>(countArgs); queryArgs.addAll(args); queryArgs.add(f.page() * 30);
        String sql = "SELECT r.*, " + STATE + " AS current_state, " + TIME + " AS queue_at, "
            + "(SELECT e.reason FROM artwork_request_event e WHERE e.request_id=r.id AND e.action='INQUIRE' ORDER BY e.id DESC LIMIT 1) AS inquiry_message, " + withdrawals
            + " AS withdrawal_count FROM artwork_request r WHERE " + where + " ORDER BY "
            + (f.sort().equals("withdrawals") ? "withdrawal_count DESC, " : "") + "queue_at ASC, r.id ASC LIMIT 30 OFFSET ?";
        var items = jdbc.query(sql, (r, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String key : List.of("id", "artwork_id", "recipient_id", "link_id", "recipient_email", "artwork_title", "interested", "hold_state",
                    "hold_until", "purchase_intent", "purchased", "cancellation_requested", "version", "updated_at", "first_requested_at", "queue_at",
                    "processing_state", "assignee", "internal_note", "link_title", "owner_sub", "withdrawal_count", "current_state",
                    "cancel_rejected", "inquiry_pending", "inquiry_message")) {
                StringBuilder camel = new StringBuilder(); boolean upper = false;
                for (char c : key.toCharArray()) { if (c == '_') upper = true; else { camel.append(upper ? Character.toUpperCase(c) : c); upper = false; } }
                m.put(camel.toString(), r.getObject(key));
            }
            return m;
        }, queryArgs.toArray());
        List<Object> optionsArgs = new ArrayList<>();
        String optionsScope = scope(auth, "r", optionsArgs);
        var links = jdbc.query("SELECT DISTINCT r.link_id,r.link_title FROM artwork_request r WHERE " + optionsScope + " ORDER BY r.link_id DESC LIMIT 500",
            (r, n) -> Map.of("value", Long.toString(r.getLong(1)), "label", Objects.toString(r.getString(2), "삭제된 링크") + " · #" + r.getLong(1)), optionsArgs.toArray());
        var artworks = jdbc.query("SELECT DISTINCT r.artwork_id,r.artwork_title FROM artwork_request r WHERE " + optionsScope + " ORDER BY r.artwork_id DESC LIMIT 500",
            (r, n) -> Map.of("value", Long.toString(r.getLong(1)), "label", r.getString(2) + " · #" + r.getLong(1)), optionsArgs.toArray());
        var owners = jdbc.query("SELECT DISTINCT r.owner_sub,COALESCE(a.display_name,a.email,'이전 계정') AS name FROM artwork_request r "
            + "LEFT JOIN admin_account a ON r.owner_sub=CONCAT('local:',CAST(a.id AS VARCHAR)) WHERE " + optionsScope + " AND r.owner_sub IS NOT NULL ORDER BY r.owner_sub LIMIT 500",
            (r, n) -> Map.of("value", r.getString(1), "label", r.getString(2)), optionsArgs.toArray());
        return Map.of("items", items, "total", total, "counts", counts, "links", links, "artworks", artworks, "owners", owners);
    }
    public Map<String, Object> recipientHistory(long id, int page, Authentication auth) {
        check(id, auth);
        if (page < 0 || page > 100000) throw bad();
        List<Object> args = new ArrayList<>();
        String where = scope(auth, "q", args); args.add(id);
        where += " AND " + identity("q", "r") + " AND r.id=?";
        String from = " FROM artwork_request_event e JOIN artwork_request q ON q.id=e.request_id CROSS JOIN artwork_request r WHERE " + where;
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        long purchases = jdbc.queryForObject("SELECT COUNT(*)" + from + " AND e.actor_type='RECIPIENT' AND e.action='PURCHASE'", Long.class, args.toArray());
        long withdrawals = jdbc.queryForObject("SELECT COUNT(*)" + from + " AND e.actor_type='RECIPIENT' AND e.action='WITHDRAW'", Long.class, args.toArray());
        args.add(page * 50);
        var items = jdbc.query("SELECT e.*,q.artwork_title,q.link_id" + from + " ORDER BY e.occurred_at DESC,e.id DESC LIMIT 50 OFFSET ?", (r, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getLong("id")); m.put("action", r.getString("action")); m.put("actorType", r.getString("actor_type"));
            m.put("actorId", r.getString("actor_id")); m.put("reason", r.getString("reason")); m.put("occurredAt", r.getTimestamp("occurred_at"));
            m.put("before", json.readTree(r.getString("before_state"))); m.put("after", json.readTree(r.getString("after_state")));
            m.put("artworkTitle", r.getString("artwork_title")); m.put("linkId", r.getLong("link_id")); return m;
        }, args.toArray());
        return Map.of("items", items, "total", total, "purchases", purchases, "withdrawals", withdrawals);
    }
    @Transactional
    public void updateWork(long id, Work work, Authentication auth) {
        check(id, auth);
        if (!WORK.contains(Objects.toString(work.processingState(), "")) || work.assignee() == null || work.assignee().length() > 200
                || work.internalNote() == null || work.internalNote().length() > 2000) throw bad();
        // Same lock order as recipient and sale transitions.
        long artwork = jdbc.queryForObject("SELECT artwork_id FROM artwork_request WHERE id=?", Long.class, id);
        jdbc.queryForList("SELECT id FROM artwork WHERE id=? FOR UPDATE", artwork);
        var before = workSnapshot(id);
        if (((Number) before.get("version")).longValue() != work.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "상태가 변경되었습니다. 새로고침 후 다시 시도해 주세요.");
        jdbc.update("UPDATE artwork_request SET processing_state=?,assignee=?,internal_note=?,version=version+1 WHERE id=?",
            work.processingState(), work.assignee().trim(), work.internalNote().trim(), id);
        jdbc.update("INSERT INTO artwork_request_work_event (request_id,actor,before_state,after_state) VALUES (?,?,?,?)",
            id, auth.getName(), json.writeValueAsString(before), json.writeValueAsString(workSnapshot(id)));
    }
    private Map<String, Object> workSnapshot(long id) {
        return jdbc.queryForObject("SELECT processing_state,assignee,internal_note,version FROM artwork_request WHERE id=?", (r, n) ->
            Map.of("processing_state", r.getString(1), "assignee", r.getString(2), "internal_note", r.getString(3), "version", r.getLong(4)), id);
    }
    public Map<String, Object> workHistory(long id, int page, Authentication auth) {
        check(id, auth); if (page < 0 || page > 100000) throw bad();
        var items = jdbc.query("SELECT * FROM artwork_request_work_event WHERE request_id=? ORDER BY id DESC LIMIT 50 OFFSET ?", (r, n) ->
            Map.of("id", r.getLong("id"), "actor", r.getString("actor"), "occurredAt", r.getTimestamp("occurred_at"),
                "before", json.readTree(r.getString("before_state")), "after", json.readTree(r.getString("after_state"))), id, page * 50);
        return Map.of("items", items, "total", jdbc.queryForObject("SELECT COUNT(*) FROM artwork_request_work_event WHERE request_id=?", Long.class, id));
    }
    private ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색 또는 처리 내용을 확인해 주세요."); }
}
