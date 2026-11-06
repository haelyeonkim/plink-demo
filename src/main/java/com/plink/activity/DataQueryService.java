package com.plink.activity;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the data console reads: the numbers of an event, and the log itself.
 *
 * <p>The numbers come from the feature tables, which hold everything since the start;
 * the log adds what only it knows (pages opened, mail that failed). Either may be asked
 * for one event or for all of them, and for a time range.
 */
@Service
public class DataQueryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The service's people and events are in Korea, whatever zone the server runs in. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;

    public DataQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A filter as the console sends it. */
    public record Range(Long sessionId, Timestamp from, Timestamp to) {
        public static Range of(Long sessionId, String from, String to) {
            return new Range(sessionId, time(from), time(to));
        }

        /** {@code column} inside the range, as SQL with its arguments appended to {@code args}. */
        String where(String sessionColumn, String timeColumn, List<Object> args) {
            StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
            if (sessionId != null && sessionColumn != null) { sql.append(" AND ").append(sessionColumn).append(" = ?"); args.add(sessionId); }
            if (from != null && timeColumn != null) { sql.append(" AND ").append(timeColumn).append(" >= ?"); args.add(from); }
            if (to != null && timeColumn != null) { sql.append(" AND ").append(timeColumn).append(" < ?"); args.add(to); }
            return sql.toString();
        }
    }

    /** ISO with or without an offset, or a bare date; blank is no bound. */
    public static Timestamp time(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try { return Timestamp.from(OffsetDateTime.parse(value).toInstant()); } catch (DateTimeParseException ignored) { }
        try { return Timestamp.from(Instant.parse(value)); } catch (DateTimeParseException ignored) { }
        try { return Timestamp.from(LocalDateTime.parse(value).atZone(ZONE).toInstant()); } catch (DateTimeParseException ignored) { }
        try { return Timestamp.from(LocalDate.parse(value).atStartOfDay(ZONE).toInstant()); } catch (DateTimeParseException ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "날짜를 읽을 수 없어요: " + value);
    }

    public static String iso(Object value) {
        if (value == null) return null;
        Instant instant = value instanceof Timestamp t ? t.toInstant()
            : value instanceof java.util.Date d ? d.toInstant()
            : value instanceof LocalDateTime l ? l.atZone(ZoneId.systemDefault()).toInstant()
            : value instanceof OffsetDateTime o ? o.toInstant() : null;
        if (instant == null) return String.valueOf(value);
        return instant.atZone(ZONE).toOffsetDateTime().toString();
    }

    // ---- Overview ----

    public Map<String, Object> overview(Range range) {
        Map<String, Object> kpis = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        String tickets = range.where("session_id", "created_at", args);
        kpis.put("issued", count("SELECT COUNT(*) FROM ticket" + tickets, args));
        kpis.put("invited", count("SELECT COUNT(*) FROM ticket" + tickets + " AND invited_by IS NOT NULL", args));
        kpis.put("bound", count("SELECT COUNT(*) FROM ticket" + tickets + " AND bound_at IS NOT NULL", args));
        args = new ArrayList<>();
        kpis.put("opened", count("SELECT COUNT(DISTINCT ticket_id) FROM activity_log"
            + range.where("session_id", "occurred_at", args) + " AND action = 'ticket.page'", args));

        Movement movement = movements(range);
        kpis.put("admittedPeople", movement.people);
        kpis.put("reentries", movement.reentries);
        kpis.put("peakInside", movement.peak);
        kpis.put("peakAt", iso(movement.peakAt));
        kpis.put("avgStayMinutes", movement.stays == 0 ? null : Math.round(movement.stayMinutes / (double) movement.stays));

        args = new ArrayList<>();
        String coupons = range.where("session_id", "issued_at", args);
        kpis.put("couponsIssued", count("SELECT COUNT(*) FROM coupon" + coupons + " AND status <> 'VOID'", args));
        kpis.put("couponsRedeemed", count("SELECT COUNT(*) FROM coupon" + coupons + " AND status = 'REDEEMED'", args));
        args = new ArrayList<>();
        String chat = range.where("session_id", "created_at", args);
        kpis.put("chatMessages", count("SELECT COUNT(*) FROM chat_message" + chat, args));
        kpis.put("chatReports", count("SELECT COALESCE(SUM(report_count), 0) FROM chat_message" + chat, args));
        args = new ArrayList<>();
        kpis.put("adViews", count("SELECT COALESCE(SUM(view_count), 0) FROM ad_post" + range.where("session_id", null, args), args));
        args = new ArrayList<>();
        kpis.put("invitesCancelled", count("SELECT COALESCE(SUM(invites_cancelled), 0) FROM ticket"
            + range.where("session_id", null, args), args));
        args = new ArrayList<>();
        String mail = range.where("session_id", "occurred_at", args) + " AND category = 'DELIVERY'";
        kpis.put("mailSent", count("SELECT COUNT(*) FROM activity_log" + mail + " AND ok = TRUE", args));
        kpis.put("mailFailed", count("SELECT COUNT(*) FROM activity_log" + mail + " AND ok = FALSE", args));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kpis", kpis);
        result.put("hourly", movement.hourly());
        result.put("gates", gates(range, movement));
        result.put("booths", booths(range));
        result.put("logs", logCounts(range));
        return result;
    }

    /** What a pass over the admission ledger finds, in time order. */
    private static final class Movement {
        long people, reentries, peak, stays, stayMinutes;
        Timestamp peakAt;
        final TreeMap<Instant, long[]> hours = new TreeMap<>();
        final Map<String, long[]> byGate = new HashMap<>();

        List<Map<String, Object>> hourly() {
            List<Map<String, Object>> rows = new ArrayList<>();
            hours.forEach((hour, counts) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("at", iso(Timestamp.from(hour)));
                row.put("entries", counts[0]);
                row.put("exits", counts[1]);
                rows.add(row);
            });
            return rows;
        }
    }

    private Movement movements(Range range) {
        Movement m = new Movement();
        List<Object> args = new ArrayList<>();
        Map<Long, Instant> insideSince = new HashMap<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long[] inside = {0};
        jdbc.query("SELECT ticket_id, direction, gate_id, method, result, occurred_at FROM admission_event"
            + range.where("session_id", "occurred_at", args) + " ORDER BY occurred_at, id", rs -> {
                long ticket = rs.getLong("ticket_id");
                boolean known = !rs.wasNull();
                String result = rs.getString("result");
                String gate = rs.getString("gate_id");
                Timestamp at = rs.getTimestamp("occurred_at");
                long[] perGate = m.byGate.computeIfAbsent(String.valueOf(gate), key -> new long[3]);
                if ("DENIED".equals(result) || "DUPLICATE".equals(result)) { perGate[1]++; return; }
                Instant hour = at.toInstant().truncatedTo(ChronoUnit.HOURS);
                long[] counts = m.hours.computeIfAbsent(hour, key -> new long[2]);
                if ("ADMITTED".equals(result)) {
                    counts[0]++;
                    perGate[0]++;
                    if ("FACE".equals(rs.getString("method"))) perGate[2]++;
                    if (known) {
                        if (!seen.add(ticket)) m.reentries++;
                        insideSince.put(ticket, at.toInstant());
                    }
                    inside[0]++;
                    if (inside[0] > m.peak) { m.peak = inside[0]; m.peakAt = at; }
                } else if ("EXITED".equals(result)) {
                    counts[1]++;
                    if (inside[0] > 0) inside[0]--;
                    Instant since = known ? insideSince.remove(ticket) : null;
                    if (since != null) {
                        m.stays++;
                        m.stayMinutes += Math.max(0, Duration.between(since, at.toInstant()).toMinutes());
                    }
                }
            }, args.toArray());
        m.people = seen.size();
        return m;
    }

    private List<Map<String, Object>> gates(Range range, Movement movement) {
        Map<String, String> labels = new HashMap<>();
        List<Object> args = new ArrayList<>();
        jdbc.query("SELECT id, label FROM gate" + range.where("session_id", null, args),
            rs -> { labels.put(rs.getString("id"), rs.getString("label")); }, args.toArray());
        List<Map<String, Object>> rows = new ArrayList<>();
        movement.byGate.forEach((gate, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gateId", "null".equals(gate) ? null : gate);
            row.put("name", labels.getOrDefault(gate, "null".equals(gate) ? "직원 확인" : gate));
            row.put("admitted", counts[0]);
            row.put("denied", counts[1]);
            row.put("faceShare", counts[0] == 0 ? 0 : counts[2] / (double) counts[0]);
            rows.add(row);
        });
        rows.sort((a, b) -> Long.compare((Long) b.get("admitted"), (Long) a.get("admitted")));
        return rows;
    }

    private List<Map<String, Object>> booths(Range range) {
        List<Object> args = new ArrayList<>();
        return jdbc.query("SELECT b.id, b.name, COUNT(c.id) AS redeemed FROM booth b "
            + "LEFT JOIN coupon c ON c.booth_id = b.id AND c.status = 'REDEEMED'"
            + (range.from() != null ? " AND c.redeemed_at >= ?" : "") + (range.to() != null ? " AND c.redeemed_at < ?" : "")
            + (range.sessionId() != null ? " WHERE b.session_id = ?" : "")
            + " GROUP BY b.id, b.name ORDER BY COUNT(c.id) DESC, b.name", (rs, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("boothId", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("redeemed", rs.getLong("redeemed"));
                return row;
            }, boothArgs(range, args).toArray());
    }

    private static List<Object> boothArgs(Range range, List<Object> args) {
        if (range.from() != null) args.add(range.from());
        if (range.to() != null) args.add(range.to());
        if (range.sessionId() != null) args.add(range.sessionId());
        return args;
    }

    private Map<String, Object> logCounts(Range range) {
        Map<String, Object> logs = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        String scope = range.sessionId() == null ? " WHERE 1 = 1" : " WHERE session_id = ?";
        if (range.sessionId() != null) args.add(range.sessionId());
        logs.put("total", count("SELECT COUNT(*) FROM activity_log" + scope, args));
        logs.put("errors", count("SELECT COUNT(*) FROM activity_log" + scope + " AND category = 'ERROR'", args));
        List<Object> today = new ArrayList<>(args);
        today.add(Timestamp.from(LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant()));
        logs.put("today", count("SELECT COUNT(*) FROM activity_log" + scope + " AND occurred_at >= ?", today));
        return logs;
    }

    private long count(String sql, List<Object> args) {
        Number n = jdbc.queryForObject(sql, Number.class, args.toArray());
        return n == null ? 0 : n.longValue();
    }

    // ---- The log ----

    public List<Map<String, Object>> categories(Range range) {
        List<Object> args = new ArrayList<>();
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("SELECT category, COUNT(*) AS n FROM activity_log" + range.where("session_id", "occurred_at", args)
            + " GROUP BY category", rs -> { counts.put(rs.getString("category"), rs.getLong("n")); }, args.toArray());
        List<Map<String, Object>> rows = new ArrayList<>();
        ActivityLabels.CATEGORIES.forEach((key, label) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", label);
            row.put("count", counts.getOrDefault(key, 0L));
            rows.add(row);
        });
        return rows;
    }

    /** One page of the log, newest first. */
    public Map<String, Object> logs(Range range, List<String> categories, String result, String q, int page, int size) {
        int pageSize = Math.max(1, Math.min(500, size));
        int pageNo = Math.max(0, page);
        List<Object> args = new ArrayList<>();
        String where = filter(range, categories, result, q, args);
        long total = count("SELECT COUNT(*) FROM activity_log a" + where, args);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) pageNo * pageSize);
        List<Map<String, Object>> rows = jdbc.query(SELECT + where + " ORDER BY a.occurred_at DESC, a.id DESC "
            + "LIMIT ? OFFSET ?", (rs, n) -> row(rs), pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", pageNo);
        out.put("size", pageSize);
        out.put("rows", rows);
        return out;
    }

    static final String SELECT = "SELECT a.*, t.ticket_ref FROM activity_log a LEFT JOIN ticket t ON t.id = a.ticket_id";

    /** Every row the filter matches, oldest first, a page at a time: for exports. */
    public void each(Range range, List<String> categories, java.util.function.Consumer<Map<String, Object>> sink) {
        List<Object> args = new ArrayList<>();
        String where = filter(range, categories, "ALL", null, args);
        jdbc.query(SELECT + where + " ORDER BY a.occurred_at, a.id", rs -> { sink.accept(row(rs)); }, args.toArray());
    }

    private String filter(Range range, List<String> categories, String result, String q, List<Object> args) {
        StringBuilder where = new StringBuilder(range.where("a.session_id", "a.occurred_at", args));
        if (categories != null && !categories.isEmpty()) {
            where.append(" AND a.category IN (").append(String.join(", ", categories.stream().map(c -> "?").toList())).append(")");
            args.addAll(categories);
        }
        if ("OK".equalsIgnoreCase(result)) where.append(" AND a.ok = TRUE");
        else if ("FAIL".equalsIgnoreCase(result)) where.append(" AND a.ok = FALSE");
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (LOWER(a.summary) LIKE ? OR LOWER(a.actor_email) LIKE ? OR LOWER(a.path) LIKE ? "
                + "OR LOWER(a.action) LIKE ? OR LOWER(a.gate_id) LIKE ? OR a.request_id = ? OR a.ip = ? "
                + "OR a.ticket_id IN (SELECT id FROM ticket WHERE LOWER(ticket_ref) LIKE ? OR LOWER(issued_to_email) LIKE ? "
                + "OR LOWER(holder_email) LIKE ?))");
            for (int i = 0; i < 5; i++) args.add(like);
            args.add(q.trim());
            args.add(q.trim());
            for (int i = 0; i < 3; i++) args.add(like);
        }
        return where.toString();
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        String category = rs.getString("category");
        String action = rs.getString("action");
        row.put("id", rs.getLong("id"));
        row.put("at", iso(rs.getTimestamp("occurred_at")));
        row.put("category", category);
        row.put("categoryLabel", ActivityLabels.category(category));
        row.put("action", action);
        row.put("actionLabel", ActivityLabels.action(category, action));
        row.put("ok", rs.getBoolean("ok"));
        row.put("result", rs.getString("result"));
        row.put("summary", rs.getString("summary"));
        row.put("actorType", rs.getString("actor_type"));
        row.put("actorId", rs.getString("actor_id"));
        row.put("actorEmail", rs.getString("actor_email"));
        row.put("ticketId", nullableLong(rs, "ticket_id"));
        row.put("ticketRef", rs.getString("ticket_ref"));
        row.put("sessionId", nullableLong(rs, "session_id"));
        row.put("gateId", rs.getString("gate_id"));
        row.put("boothId", nullableLong(rs, "booth_id"));
        row.put("httpMethod", rs.getString("http_method"));
        row.put("path", rs.getString("path"));
        int status = rs.getInt("status");
        row.put("status", rs.wasNull() ? null : status);
        int duration = rs.getInt("duration_ms");
        row.put("durationMs", rs.wasNull() ? null : duration);
        row.put("ip", rs.getString("ip"));
        String agent = rs.getString("user_agent");
        row.put("userAgent", agent);
        row.put("device", device(agent));
        row.put("requestId", rs.getString("request_id"));
        String detail = rs.getString("detail");
        Object parsed = null;
        if (detail != null) {
            try { parsed = JSON.readValue(detail, Map.class); } catch (RuntimeException unreadable) { parsed = detail; }
        }
        row.put("detail", parsed);
        return row;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // ---- Saved views ----

    public List<Map<String, Object>> views() {
        return jdbc.query("SELECT * FROM data_view ORDER BY id", (rs, n) -> view(rs.getLong("id"),
            rs.getString("name"), rs.getString("filters")));
    }

    public Map<String, Object> saveView(String name, Object filters, String createdBy) {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "보기 이름은 1~60자로 적어 주세요.");
        }
        String json = JSON.writeValueAsString(filters instanceof Map<?, ?> ? filters : Map.of());
        org.springframework.jdbc.support.KeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO data_view (name, filters, created_by) VALUES (?, ?, ?)", new String[] { "id" });
            ps.setString(1, label);
            ps.setString(2, json);
            ps.setString(3, createdBy);
            return ps;
        }, keys);
        return view(keys.getKey().longValue(), label, json);
    }

    public void deleteView(long id) {
        if (jdbc.update("DELETE FROM data_view WHERE id = ?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "보기를 찾을 수 없어요.");
        }
    }

    private static Map<String, Object> view(long id, String name, String filters) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("name", name);
        Object parsed;
        try { parsed = JSON.readValue(filters, Map.class); } catch (RuntimeException unreadable) { parsed = Map.of(); }
        view.put("filters", parsed);
        return view;
    }

    /** A short name for the kind of device a User-Agent describes. */
    public static String device(String agent) {
        if (agent == null || agent.isBlank()) return null;
        String a = agent.toLowerCase();
        String os = a.contains("iphone") ? "iPhone" : a.contains("ipad") ? "iPad"
            : a.contains("android") ? (a.contains("samsung") || a.contains("sm-") ? "Galaxy" : "Android")
            : a.contains("mac os x") ? "Mac" : a.contains("windows") ? "Windows" : a.contains("linux") ? "Linux" : null;
        String browser = a.contains("samsungbrowser") ? "삼성 인터넷" : a.contains("kakaotalk") ? "카카오톡"
            : a.contains("naver") ? "네이버" : a.contains("edg/") ? "Edge" : a.contains("crios") || a.contains("chrome/") ? "Chrome"
            : a.contains("fxios") || a.contains("firefox") ? "Firefox" : a.contains("safari") ? "Safari"
            : a.contains("curl") || a.contains("java") || a.contains("python") ? "프로그램" : null;
        if (os == null && browser == null) return "기타";
        return os == null ? browser : browser == null ? os : os + " · " + browser;
    }
}
