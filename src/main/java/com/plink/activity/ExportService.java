package com.plink.activity;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.service.EmailOtpService;
import com.plink.ticket.service.EmailSender;
import com.plink.ticket.service.Secrets;
import com.plink.ticket.service.TextCipher;
import com.plink.ticket.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Data handed to an organiser through a link that opens for a while.
 *
 * <p>The files are made when the export is created, so what the organiser downloads is
 * what the console chose at that moment, however the event goes on. Only the HMAC of
 * the link's token decides who gets in; a sealed copy lets the console show and mail
 * the link again while it is open. Every open, code and download is written down, and
 * once the link closes - by date, by count or by hand - the files are deleted.
 */
@Service
public class ExportService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Integer> DAYS = List.of(1, 7, 14, 30);
    static final String UNLOCKED = ExportService.class.getName() + ".unlocked.";

    private final JdbcTemplate jdbc;
    private final ExportDatasets datasets;
    private final TextCipher cipher;
    private final EmailOtpService otp;
    private final EmailSender mail;
    private final TicketProperties properties;
    private final String baseUrl;

    public ExportService(JdbcTemplate jdbc, ExportDatasets datasets, TextCipher cipher, EmailOtpService otp,
            EmailSender mail, TicketProperties properties, @Value("${plink.auth.base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.cipher = cipher;
        this.otp = otp;
        this.mail = mail;
        this.properties = properties;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // ---- Console ----

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String createdBy) {
        Long sessionId = number(body.get("sessionId"));
        if (sessionId == null) throw bad("이벤트를 골라 주세요.");
        String title = jdbc.query("SELECT name FROM event_session WHERE id = ?", (rs, n) -> rs.getString(1), sessionId)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없어요."));
        String pii = String.valueOf(body.getOrDefault("pii", ExportDatasets.RAW)).toUpperCase();
        if (!List.of(ExportDatasets.AGGREGATE, ExportDatasets.MASKED, ExportDatasets.RAW).contains(pii)) {
            throw bad("개인정보 수준을 골라 주세요.");
        }
        String note = text(body.get("note"), 1000);
        if (ExportDatasets.RAW.equals(pii) && note == null) {
            throw bad("원본을 담을 때는 요청 내용을 적어 주세요.");
        }
        String recipientName = text(body.get("recipientName"), 120);
        if (recipientName == null) throw bad("받는 곳을 적어 주세요.");
        String recipientEmail = EmailOtpService.normalize(text(body.get("recipientEmail"), 320));
        if (recipientEmail == null || !recipientEmail.contains("@")) throw bad("받는 사람 이메일을 확인해 주세요.");
        int days = number(body.get("days")) == null ? 7 : number(body.get("days")).intValue();
        if (!DAYS.contains(days)) throw bad("링크 유효 기간은 1, 7, 14, 30일 중에서 골라 주세요.");
        Long maxDownloads = number(body.get("maxDownloads"));
        if (maxDownloads != null && (maxDownloads < 1 || maxDownloads > 1000)) throw bad("내려받기 횟수를 확인해 주세요.");
        boolean requireCode = !Boolean.FALSE.equals(body.get("requireCode"));
        DataQueryService.Range range = DataQueryService.Range.of(sessionId,
            text(body.get("from"), 40), text(body.get("to"), 40));
        String tier = text(body.get("tier"), 32);

        List<?> wanted = body.get("datasets") instanceof List<?> list ? list : List.of();
        if (wanted.isEmpty()) throw bad("담을 데이터를 하나 이상 골라 주세요.");
        List<ExportDatasets.File> files = new ArrayList<>();
        List<Map<String, Object>> spec = new ArrayList<>();
        for (Object item : wanted) {
            if (!(item instanceof Map<?, ?> chosen)) continue;
            String key = String.valueOf(chosen.get("key"));
            List<String> columns = chosen.get("columns") instanceof List<?> cols
                ? cols.stream().map(String::valueOf).toList() : List.of();
            List<ExportDatasets.File> made = datasets.build(key, columns, pii, range, tier);
            files.addAll(made);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("label", ExportDatasets.require(key).label());
            entry.put("columns", columns);
            entry.put("rows", made.stream().mapToInt(ExportDatasets.File::rows).sum());
            spec.add(entry);
        }
        if (files.isEmpty()) throw bad("담을 데이터를 하나 이상 골라 주세요.");

        Map<String, Object> specification = new LinkedHashMap<>();
        specification.put("datasets", spec);
        specification.put("from", iso(range.from()));
        specification.put("to", iso(range.to()));
        specification.put("tier", tier);

        String token = Secrets.randomToken(24);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(Duration.ofDays(days)));
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO data_export (session_id, session_title, "
                + "recipient_name, recipient_email, spec, pii, note, token_hmac, token_cipher, expires_at, max_downloads, "
                + "require_code, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] { "id" });
            ps.setLong(1, sessionId);
            ps.setString(2, title);
            ps.setString(3, recipientName);
            ps.setString(4, recipientEmail);
            ps.setString(5, JSON.writeValueAsString(specification));
            ps.setString(6, pii);
            ps.setString(7, note);
            ps.setString(8, hash(token));
            ps.setString(9, cipher.seal(token));
            ps.setTimestamp(10, expiresAt);
            if (maxDownloads == null) ps.setNull(11, Types.INTEGER); else ps.setInt(11, maxDownloads.intValue());
            ps.setBoolean(12, requireCode);
            ps.setString(13, createdBy);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        for (ExportDatasets.File file : files) {
            jdbc.update("INSERT INTO data_export_file (export_id, name, label, description, rows_count, content) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, file.name(), file.label(), file.description(), file.rows(), file.content());
        }
        ActivityLog.emit(Activity.of(Activity.ADMIN, "export.created").session(sessionId)
            .summary(recipientName + " · " + recipientEmail).put("export", id).put("recipient", recipientEmail)
            .put("pii", pii).put("note", note).put("spec", specification).put("days", days)
            .put("maxDownloads", maxDownloads).put("requireCode", requireCode));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("url", urlFor(token));
        result.put("expiresAt", iso(expiresAt));
        return result;
    }

    public List<Map<String, Object>> list() {
        return jdbc.query("SELECT * FROM data_export ORDER BY created_at DESC, id DESC", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long id = rs.getLong("id");
            row.put("id", id);
            long sessionId = rs.getLong("session_id");
            row.put("sessionId", rs.wasNull() ? null : sessionId);
            row.put("sessionTitle", rs.getString("session_title"));
            row.put("recipientName", rs.getString("recipient_name"));
            row.put("recipientEmail", rs.getString("recipient_email"));
            Map<?, ?> spec = JSON.readValue(rs.getString("spec"), Map.class);
            row.put("datasets", spec.get("datasets"));
            row.put("from", spec.get("from"));
            row.put("to", spec.get("to"));
            row.put("tier", spec.get("tier"));
            row.put("pii", rs.getString("pii"));
            row.put("note", rs.getString("note"));
            row.put("createdAt", iso(rs.getTimestamp("created_at")));
            row.put("createdBy", rs.getString("created_by"));
            row.put("expiresAt", iso(rs.getTimestamp("expires_at")));
            int max = rs.getInt("max_downloads");
            row.put("maxDownloads", rs.wasNull() ? null : max);
            row.put("downloads", rs.getInt("downloads"));
            row.put("requireCode", rs.getBoolean("require_code"));
            String status = status(rs.getTimestamp("closed_at"), rs.getTimestamp("expires_at"),
                rs.wasNull() ? null : max, rs.getInt("downloads"));
            row.put("status", status);
            String token = "OPEN".equals(status) ? cipher.open(rs.getString("token_cipher")) : null;
            row.put("url", token == null ? null : urlFor(token));
            return row;
        });
    }

    /** Mails the link to the person it is for. */
    public Map<String, Object> send(long id) {
        Export export = require(id);
        if (!"OPEN".equals(export.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 닫힌 링크예요.");
        String token = cipher.open(export.tokenCipher);
        if (token == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "링크를 다시 읽을 수 없어요. 새로 만들어 주세요.");
        boolean sent = mail.send(export.recipientEmail, "[" + export.sessionTitle + "] 요청하신 데이터가 준비되었어요",
            export.recipientName + " 담당자님,\n\n요청하신 " + export.sessionTitle + " 데이터를 아래 링크에서 내려받을 수 있어요.\n\n"
            + urlFor(token) + "\n\n" + java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm")
                .withZone(DataQueryService.ZONE).format(export.expiresAt.toInstant())
            + "까지 열 수 있어요." + (export.requireCode ? " 열 때 이 주소로 확인 코드를 보내 드려요." : "")
            + "\n링크를 다른 사람에게 전달하지 말아 주세요.");
        ActivityLog.emit(Activity.of(Activity.ADMIN, "export.mailed").ok(sent).session(export.sessionId)
            .summary(export.recipientEmail).put("export", id).put("to", export.recipientEmail));
        if (!sent) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "메일을 보내지 못했어요. 링크를 복사해서 직접 보내 주세요.");
        return Map.of("sent", true);
    }

    @Transactional
    public void close(long id) {
        Export export = require(id);
        jdbc.update("UPDATE data_export SET closed_at = CURRENT_TIMESTAMP WHERE id = ? AND closed_at IS NULL", id);
        purge(id);
        ActivityLog.emit(Activity.of(Activity.ADMIN, "export.closed").session(export.sessionId)
            .summary(export.recipientName).put("export", id));
    }

    public List<Map<String, Object>> access(long id) {
        require(id);
        return jdbc.query("SELECT * FROM data_export_access WHERE export_id = ? ORDER BY occurred_at DESC, id DESC",
            (rs, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("at", iso(rs.getTimestamp("occurred_at")));
                row.put("action", rs.getString("action"));
                row.put("file", rs.getString("file"));
                row.put("ip", rs.getString("ip"));
                row.put("userAgent", rs.getString("user_agent"));
                return row;
            }, id);
    }

    /** Files of links that have closed are deleted; the record of the export stays. */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void purgeClosed() {
        List<Long> done = jdbc.queryForList("SELECT id FROM data_export WHERE purged_at IS NULL AND (closed_at IS NOT NULL "
            + "OR expires_at < CURRENT_TIMESTAMP OR (max_downloads IS NOT NULL AND downloads >= max_downloads))", Long.class);
        for (long id : done) {
            purge(id);
            ActivityLog.emit(Activity.of(Activity.ADMIN, "export.purged").actor("SYSTEM", null, null).put("export", id));
        }
    }

    private void purge(long id) {
        jdbc.update("UPDATE data_export_file SET content = NULL WHERE export_id = ?", id);
        jdbc.update("UPDATE data_export SET purged_at = CURRENT_TIMESTAMP WHERE id = ? AND purged_at IS NULL", id);
    }

    // ---- Recipient ----

    /** What the link's page shows. Files are listed only once the code has been checked. */
    public Map<String, Object> open(String token, HttpServletRequest request) {
        Export export = byToken(token);
        ActivityContext.recipient(export.id, export.recipientEmail);
        boolean unlocked = unlocked(export, request.getSession(false));
        accessed(export.id, "OPEN", null, request);
        return view(export, unlocked);
    }

    public Map<String, Object> sendCode(String token, HttpServletRequest request) {
        Export export = byToken(token);
        ActivityContext.recipient(export.id, export.recipientEmail);
        otp.issue(export.recipientEmail, purpose(export.id), "[" + export.sessionTitle + "] 데이터 링크 확인 코드",
            export.recipientName + " 담당자님, 데이터 링크를 열기 위한 확인 코드예요.");
        accessed(export.id, "CODE_SENT", null, request);
        return Map.of("sent", true, "maskedEmail", TicketService.mask(export.recipientEmail));
    }

    public Map<String, Object> verify(String token, String code, HttpServletRequest request) {
        Export export = byToken(token);
        ActivityContext.recipient(export.id, export.recipientEmail);
        try {
            otp.verify(export.recipientEmail, purpose(export.id), code);
        } catch (ResponseStatusException refused) {
            accessed(export.id, "REFUSED", null, request);
            throw refused;
        }
        request.getSession(true).setAttribute(UNLOCKED + export.id, Boolean.TRUE);
        accessed(export.id, "VERIFIED", null, request);
        return view(export, true);
    }

    public record Download(String name, byte[] bytes, String contentType) { }

    /** One file, or all of them as a zip with {@code name} null. Counts as one download. */
    @Transactional
    public Download download(String token, String name, HttpServletRequest request) {
        Export export = byToken(token);
        ActivityContext.recipient(export.id, export.recipientEmail);
        if (!unlocked(export, request.getSession(false))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "확인 코드를 먼저 넣어 주세요.");
        }
        int counted = jdbc.update("UPDATE data_export SET downloads = downloads + 1 WHERE id = ? "
            + "AND (max_downloads IS NULL OR downloads < max_downloads)", export.id);
        if (counted == 0) throw new ResponseStatusException(HttpStatus.GONE, "내려받기 횟수를 모두 썼어요.");
        List<Map<String, Object>> files = jdbc.queryForList(
            "SELECT name, content FROM data_export_file WHERE export_id = ? AND content IS NOT NULL ORDER BY id", export.id);
        Download result;
        if (name == null) {
            result = new Download(safe(export.sessionTitle) + "_데이터.zip", zip(files), "application/zip");
        } else {
            Map<String, Object> file = files.stream().filter(f -> name.equals(f.get("name"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없어요."));
            result = new Download(name, String.valueOf(file.get("content")).getBytes(StandardCharsets.UTF_8),
                "text/csv; charset=UTF-8");
        }
        accessed(export.id, "DOWNLOAD", name == null ? "(zip)" : name, request);
        return result;
    }

    private Map<String, Object> view(Export export, boolean unlocked) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("eventTitle", export.sessionTitle);
        view.put("recipientName", export.recipientName);
        view.put("expiresAt", iso(export.expiresAt));
        view.put("downloadsLeft", export.maxDownloads == null ? null : Math.max(0, export.maxDownloads - export.downloads));
        view.put("requireCode", export.requireCode);
        view.put("maskedEmail", TicketService.mask(export.recipientEmail));
        view.put("unlocked", unlocked);
        view.put("files", !unlocked ? List.of() : jdbc.query("SELECT name, label, description, rows_count, "
            + "LENGTH(content) AS size FROM data_export_file WHERE export_id = ? AND content IS NOT NULL ORDER BY id",
            (rs, n) -> {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("name", rs.getString("name"));
                file.put("description", firstOf(rs.getString("description"), rs.getString("label")));
                file.put("rows", rs.getInt("rows_count"));
                file.put("bytes", rs.getLong("size"));
                return file;
            }, export.id));
        return view;
    }

    private boolean unlocked(Export export, HttpSession session) {
        return !export.requireCode || (session != null && Boolean.TRUE.equals(session.getAttribute(UNLOCKED + export.id)));
    }

    private void accessed(long id, String action, String file, HttpServletRequest request) {
        jdbc.update("INSERT INTO data_export_access (export_id, action, file, ip, user_agent) VALUES (?, ?, ?, ?, ?)",
            id, action, file, request.getRemoteAddr(), ActivityLog.cut(request.getHeader("User-Agent"), 500));
        ActivityLog.emit(Activity.of(Activity.ADMIN, "export." + switch (action) {
                case "OPEN" -> "opened"; case "CODE_SENT" -> "code_sent"; case "VERIFIED" -> "verified";
                case "REFUSED" -> "verify_refused"; default -> "download"; })
            .ok(!"REFUSED".equals(action)).put("export", id).put("file", file).always());
    }

    // ---- Rows ----

    private record Export(long id, Long sessionId, String sessionTitle, String recipientName, String recipientEmail,
            String tokenCipher, Timestamp expiresAt, Integer maxDownloads, int downloads, boolean requireCode,
            Timestamp closedAt) {
        String status() { return ExportService.status(closedAt, expiresAt, maxDownloads, downloads); }
    }

    static String status(Timestamp closedAt, Timestamp expiresAt, Integer max, int downloads) {
        if (closedAt != null) return "CLOSED";
        if (expiresAt.toInstant().isBefore(Instant.now())) return "EXPIRED";
        if (max != null && downloads >= max) return "USED_UP";
        return "OPEN";
    }

    private Export require(long id) {
        return find("WHERE id = ?", id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "내보내기를 찾을 수 없어요."));
    }

    /** An open link's export; anything else is one message, so a guess learns nothing. */
    private Export byToken(String token) {
        ResponseStatusException gone = new ResponseStatusException(HttpStatus.NOT_FOUND,
            "이 링크는 열 수 없어요. 기간이 지났거나 닫혔어요. 보낸 곳에 다시 요청해 주세요.");
        if (token == null || token.length() < 20 || token.length() > 64) throw gone;
        Export export = find("WHERE token_hmac = ?", hash(token)).orElseThrow(() -> gone);
        String status = export.status();
        if ("USED_UP".equals(status)) {
            throw new ResponseStatusException(HttpStatus.GONE, "내려받기 횟수를 모두 썼어요. 보낸 곳에 다시 요청해 주세요.");
        }
        if (!"OPEN".equals(status)) throw gone;
        return export;
    }

    private java.util.Optional<Export> find(String where, Object arg) {
        return jdbc.query("SELECT * FROM data_export " + where, (rs, n) -> {
            long session = rs.getLong("session_id");
            Long sessionId = rs.wasNull() ? null : session;
            int max = rs.getInt("max_downloads");
            Integer maxDownloads = rs.wasNull() ? null : max;
            return new Export(rs.getLong("id"), sessionId, rs.getString("session_title"), rs.getString("recipient_name"),
                rs.getString("recipient_email"), rs.getString("token_cipher"), rs.getTimestamp("expires_at"),
                maxDownloads, rs.getInt("downloads"), rs.getBoolean("require_code"), rs.getTimestamp("closed_at"));
        }, arg).stream().findFirst();
    }

    private String hash(String token) {
        return Secrets.hmacHex(properties.getTokenSecret(), "export|" + token);
    }

    private String urlFor(String token) { return baseUrl + "/x/" + token; }

    private static String purpose(long id) { return "export:" + id; }

    private static byte[] zip(List<Map<String, Object>> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map<String, Object> file : files) {
                zip.putNextEntry(new ZipEntry(String.valueOf(file.get("name"))));
                zip.write(String.valueOf(file.get("content")).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    private static String safe(String title) {
        return title == null ? "데이터" : title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private static String iso(Timestamp at) { return DataQueryService.iso(at); }

    private static String firstOf(String a, String b) { return a != null && !a.isBlank() ? a : b; }

    private static Long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException notANumber) { return null; }
        }
        return null;
    }

    private static String text(Object value, int max) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : ActivityLog.cut(s, max);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
