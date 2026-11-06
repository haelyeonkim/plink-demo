package com.plink.activity;

import com.plink.ticket.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What can be handed to an organiser, and how each becomes a CSV file.
 *
 * <p>Each dataset names its columns; a column marked personal is shown as is under
 * "원본", masked under "가린 정보", and the whole dataset is refused under "숫자만"
 * (only the summary has no people in it). The two internal datasets - the full log and
 * its errors - are there for when an organiser's question can only be answered by them.
 */
@Component
public class ExportDatasets {
    public static final String AGGREGATE = "AGGREGATE", MASKED = "MASKED", RAW = "RAW";
    private static final DateTimeFormatter CSV_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DataQueryService.ZONE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Column(String key, String label, boolean pii, boolean defaultOn) { }

    public record Dataset(String key, String label, String description, boolean internal, List<Column> columns) { }

    /** A file made from a dataset. */
    public record File(String name, String label, String description, int rows, String content) { }

    private static Column col(String key, String label) { return new Column(key, label, false, true); }
    private static Column pii(String key, String label) { return new Column(key, label, true, true); }
    private static Column extra(String key, String label) { return new Column(key, label, false, false); }

    public static final List<Dataset> ALL = List.of(
        new Dataset("SUMMARY", "통계 요약", "핵심 숫자 · 시간대별 · 게이트별 · 부스별", false, List.of()),
        new Dataset("ADMISSION", "입장 · 퇴장 기록", "게이트를 지난 모든 기록", false, List.of(
            col("at", "시각"), col("ticketRef", "입장권 번호"), pii("email", "이메일"), col("tier", "등급"),
            col("seat", "좌석"), col("gate", "게이트"), col("direction", "방향"), col("result", "결과"),
            col("method", "확인 방법"), col("reason", "사유"), extra("operator", "처리한 직원"),
            extra("offline", "오프라인 기록"))),
        new Dataset("COUPON", "부스 · 쿠폰 사용", "쿠폰을 받고 쓴 기록", false, List.of(
            col("issuedAt", "받은 시각"), col("booth", "부스"), col("title", "쿠폰"), col("ticketRef", "입장권 번호"),
            pii("email", "이메일"), col("status", "상태"), col("redeemedAt", "사용 시각"), col("redeemedGate", "사용한 단말"))),
        new Dataset("TICKETS", "발급 · 등록 현황", "입장권 한 장에 한 줄", false, List.of(
            col("ticketRef", "입장권 번호"), pii("email", "받은 이메일"), pii("holderEmail", "등록한 이메일"),
            pii("phone", "휴대폰"), col("tier", "등급"), col("seat", "좌석"), col("status", "상태"),
            col("createdAt", "발급 시각"), col("boundAt", "등록 시각"), col("deliveredVia", "전달 방법"),
            col("invitedBy", "초대한 입장권"), col("entries", "입장 횟수"), col("firstEntry", "첫 입장"),
            col("lastExit", "마지막 퇴장"), extra("attributes", "추가 항목"))),
        new Dataset("INVITES", "초대 관계", "누가 누구를 초대했는지", false, List.of(
            col("inviterRef", "초대한 입장권"), pii("inviterEmail", "초대한 사람"), col("invitedRef", "초대받은 입장권"),
            pii("invitedEmail", "초대받은 사람"), col("name", "부른 이름"), col("status", "상태"),
            col("createdAt", "초대 시각"), col("boundAt", "등록 시각"))),
        new Dataset("CHAT", "채팅 · 홍보글", "메시지 원문 · 반응 · 신고", false, List.of(
            col("at", "시각"), col("ticketRef", "입장권 번호"), pii("email", "이메일"), col("kind", "종류"),
            col("body", "내용"), col("likes", "좋아요"), col("dislikes", "싫어요"), col("reports", "신고"),
            col("hidden", "숨김"))),
        new Dataset("ACTIVITY", "전체 활동 로그", "요청 · 로그인 · 관리자 작업까지 모든 기록", true, List.of(
            col("at", "시각"), col("category", "종류"), col("action", "행동"), col("ok", "성공"), col("result", "결과"),
            col("actorType", "누가"), pii("actorEmail", "이메일"), col("ticketRef", "입장권 번호"), col("gate", "게이트"),
            col("summary", "요약"), extra("request", "요청"), pii("ip", "IP"), extra("userAgent", "브라우저"),
            extra("detail", "원본 JSON"))),
        new Dataset("ERRORS", "오류 기록", "서버와 브라우저에서 난 오류", true, List.of(
            col("at", "시각"), col("action", "어디서"), col("summary", "내용"), extra("request", "요청"),
            pii("ip", "IP"), extra("detail", "원본 JSON"))));

    private final JdbcTemplate jdbc;
    private final DataQueryService data;

    public ExportDatasets(JdbcTemplate jdbc, DataQueryService data) {
        this.jdbc = jdbc;
        this.data = data;
    }

    public static Dataset require(String key) {
        return ALL.stream().filter(d -> d.key().equals(key)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 데이터예요: " + key));
    }

    /** The catalogue for the console, with how many rows each would have for the event. */
    public List<Map<String, Object>> catalogue(Long sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dataset d : ALL) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", d.key());
            row.put("label", d.label());
            row.put("description", d.description());
            row.put("internal", d.internal());
            row.put("rows", rows(d.key(), sessionId));
            List<Map<String, Object>> columns = new ArrayList<>();
            for (Column c : d.columns()) {
                columns.add(Map.of("key", c.key(), "label", c.label(), "pii", c.pii(), "defaultOn", c.defaultOn()));
            }
            row.put("columns", columns);
            out.add(row);
        }
        return out;
    }

    private long rows(String key, Long sessionId) {
        String scope = sessionId == null ? "" : " WHERE session_id = " + sessionId;
        String sql = switch (key) {
            case "ADMISSION" -> "SELECT COUNT(*) FROM admission_event" + scope;
            case "COUPON" -> "SELECT COUNT(*) FROM coupon" + scope;
            case "TICKETS" -> "SELECT COUNT(*) FROM ticket" + scope;
            case "INVITES" -> "SELECT COUNT(*) FROM ticket" + (scope.isEmpty() ? " WHERE" : scope + " AND") + " invited_by IS NOT NULL";
            case "CHAT" -> "SELECT COUNT(*) FROM chat_message" + scope;
            case "ACTIVITY" -> "SELECT COUNT(*) FROM activity_log" + scope;
            case "ERRORS" -> "SELECT COUNT(*) FROM activity_log" + (scope.isEmpty() ? " WHERE" : scope + " AND") + " category = 'ERROR'";
            default -> null;
        };
        if (sql == null) return 4;
        Number n = jdbc.queryForObject(sql, Number.class);
        return n == null ? 0 : n.longValue();
    }

    /** The files one dataset becomes. */
    public List<File> build(String key, List<String> wanted, String pii, DataQueryService.Range range, String tier) {
        Dataset dataset = require(key);
        if (AGGREGATE.equals(pii) && !"SUMMARY".equals(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'숫자만'으로는 " + dataset.label() + "을(를) 담을 수 없어요.");
        }
        if ("SUMMARY".equals(key)) return summary(range);
        List<Column> columns = dataset.columns().stream()
            .filter(c -> wanted == null || wanted.isEmpty() ? c.defaultOn() : wanted.contains(c.key())).toList();
        if (columns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, dataset.label() + "에 담을 열을 골라 주세요.");
        }
        Csv csv = new Csv(columns, MASKED.equals(pii));
        switch (key) {
            case "ADMISSION" -> admissions(csv, range, tier);
            case "COUPON" -> coupons(csv, range, tier);
            case "TICKETS" -> tickets(csv, range, tier, false);
            case "INVITES" -> tickets(csv, range, tier, true);
            case "CHAT" -> chat(csv, range);
            case "ACTIVITY" -> activity(csv, range, null);
            case "ERRORS" -> activity(csv, range, List.of(Activity.ERROR));
            default -> throw new IllegalStateException(key);
        }
        return List.of(new File(fileName(dataset.label()), dataset.label(), dataset.description(), csv.rows, csv.text()));
    }

    private static String fileName(String label) {
        return label.replace(" · ", "_").replace(" ", "") + ".csv";
    }

    // ---- Datasets ----

    private void admissions(Csv csv, DataQueryService.Range range, String tier) {
        List<Object> args = new ArrayList<>();
        String where = range.where("a.session_id", "a.occurred_at", args) + tierFilter("t", tier, args);
        jdbc.query("SELECT a.*, t.ticket_ref, t.tier, t.seat, COALESCE(t.holder_email, t.issued_to_email) AS email, "
            + "g.label AS gate_label FROM admission_event a LEFT JOIN ticket t ON t.id = a.ticket_id "
            + "LEFT JOIN gate g ON g.id = a.gate_id" + where + " ORDER BY a.occurred_at, a.id", rs -> {
                csv.add(Map.ofEntries(
                    entry("at", time(rs.getTimestamp("occurred_at"))), entry("ticketRef", rs.getString("ticket_ref")),
                    entry("email", rs.getString("email")), entry("tier", rs.getString("tier")),
                    entry("seat", rs.getString("seat")),
                    entry("gate", firstOf(rs.getString("gate_label"), rs.getString("gate_id"))),
                    entry("direction", "IN".equals(rs.getString("direction")) ? "입장" : "OUT".equals(rs.getString("direction")) ? "퇴장" : rs.getString("direction")),
                    entry("result", rs.getString("result")), entry("method", rs.getString("method")),
                    entry("reason", rs.getString("reason")), entry("operator", rs.getString("operator")),
                    entry("offline", rs.getBoolean("offline") ? "예" : "")));
            }, args.toArray());
    }

    private void coupons(Csv csv, DataQueryService.Range range, String tier) {
        List<Object> args = new ArrayList<>();
        String where = range.where("c.session_id", "c.issued_at", args) + tierFilter("t", tier, args);
        Map<String, String> status = Map.of("ISSUED", "미사용", "REDEEMED", "사용함", "VOID", "무효");
        jdbc.query("SELECT c.*, b.name AS booth, t.ticket_ref, COALESCE(t.holder_email, t.issued_to_email) AS email, "
            + "g.label AS gate_label FROM coupon c LEFT JOIN booth b ON b.id = c.booth_id LEFT JOIN ticket t ON t.id = c.ticket_id "
            + "LEFT JOIN gate g ON g.id = c.redeemed_gate" + where + " ORDER BY c.issued_at, c.id", rs -> {
                csv.add(Map.ofEntries(
                    entry("issuedAt", time(rs.getTimestamp("issued_at"))), entry("booth", rs.getString("booth")),
                    entry("title", rs.getString("title")), entry("ticketRef", rs.getString("ticket_ref")),
                    entry("email", rs.getString("email")),
                    entry("status", status.getOrDefault(rs.getString("status"), rs.getString("status"))),
                    entry("redeemedAt", time(rs.getTimestamp("redeemed_at"))),
                    entry("redeemedGate", rs.getTimestamp("redeemed_at") == null ? ""
                        : firstOf(rs.getString("gate_label"), rs.getString("redeemed_gate"), "운영자 처리"))));
            }, args.toArray());
    }

    private void tickets(Csv csv, DataQueryService.Range range, String tier, boolean invitesOnly) {
        List<Object> args = new ArrayList<>();
        String where = range.where("t.session_id", "t.created_at", args) + tierFilter("t", tier, args)
            + (invitesOnly ? " AND t.invited_by IS NOT NULL" : "");
        jdbc.query("SELECT t.*, i.ticket_ref AS inviter_ref, COALESCE(i.holder_email, i.issued_to_email) AS inviter_email, "
            + "p.entry_count, (SELECT MIN(e.occurred_at) FROM admission_event e WHERE e.ticket_id = t.id AND e.result = 'ADMITTED') AS first_entry, "
            + "p.last_exit_at FROM ticket t LEFT JOIN ticket i ON i.id = t.invited_by "
            + "LEFT JOIN ticket_presence p ON p.ticket_id = t.id" + where + " ORDER BY t.created_at, t.id", rs -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("ticketRef", rs.getString("ticket_ref"));
                row.put("email", rs.getString("issued_to_email"));
                row.put("holderEmail", rs.getString("holder_email"));
                row.put("phone", rs.getString("phone"));
                row.put("tier", rs.getString("tier"));
                row.put("seat", rs.getString("seat"));
                row.put("status", rs.getString("status"));
                row.put("createdAt", time(rs.getTimestamp("created_at")));
                row.put("boundAt", time(rs.getTimestamp("bound_at")));
                row.put("deliveredVia", rs.getString("delivered_via"));
                row.put("invitedBy", rs.getString("inviter_ref"));
                int entries = rs.getInt("entry_count");
                row.put("entries", rs.wasNull() ? "0" : String.valueOf(entries));
                row.put("firstEntry", time(rs.getTimestamp("first_entry")));
                row.put("lastExit", time(rs.getTimestamp("last_exit_at")));
                row.put("attributes", rs.getString("attributes"));
                row.put("inviterRef", rs.getString("inviter_ref"));
                row.put("inviterEmail", rs.getString("inviter_email"));
                row.put("invitedRef", rs.getString("ticket_ref"));
                row.put("invitedEmail", rs.getString("issued_to_email"));
                row.put("name", rs.getString("invite_name"));
                csv.add(row);
            }, args.toArray());
    }

    private void chat(Csv csv, DataQueryService.Range range) {
        List<Object> args = new ArrayList<>();
        String where = range.where("m.session_id", "m.created_at", args);
        jdbc.query("SELECT m.*, t.ticket_ref, COALESCE(t.holder_email, t.issued_to_email) AS email FROM chat_message m "
            + "LEFT JOIN ticket t ON t.id = m.ticket_id" + where + " ORDER BY m.created_at, m.id", rs -> {
                csv.add(Map.ofEntries(
                    entry("at", time(rs.getTimestamp("created_at"))), entry("ticketRef", rs.getString("ticket_ref")),
                    entry("email", rs.getString("email")),
                    entry("kind", rs.getBoolean("notice") ? "공지" : rs.getBoolean("staff") ? "운영팀" : "참가자"),
                    entry("body", rs.getString("body")), entry("likes", String.valueOf(rs.getInt("like_count"))),
                    entry("dislikes", String.valueOf(rs.getInt("dislike_count"))),
                    entry("reports", String.valueOf(rs.getInt("report_count"))),
                    entry("hidden", rs.getBoolean("hidden") ? "예" : "")));
            }, args.toArray());
    }

    private void activity(Csv csv, DataQueryService.Range range, List<String> categories) {
        data.each(range, categories, row -> {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("at", time(DataQueryService.time((String) row.get("at"))));
            out.put("category", String.valueOf(row.get("categoryLabel")));
            out.put("action", String.valueOf(row.get("actionLabel")));
            out.put("ok", Boolean.TRUE.equals(row.get("ok")) ? "예" : "아니요");
            out.put("result", text(row.get("result")));
            out.put("actorType", text(row.get("actorType")));
            out.put("actorEmail", text(row.get("actorEmail")));
            out.put("ticketRef", text(row.get("ticketRef")));
            out.put("gate", text(row.get("gateId")));
            out.put("summary", text(row.get("summary")));
            out.put("request", row.get("path") == null ? "" : row.get("httpMethod") + " " + row.get("path")
                + (row.get("status") == null ? "" : " → " + row.get("status")));
            out.put("ip", text(row.get("ip")));
            out.put("userAgent", text(row.get("userAgent")));
            out.put("detail", row.get("detail") == null ? "" : JSON.writeValueAsString(row.get("detail")));
            csv.add(out);
        });
    }

    private List<File> summary(DataQueryService.Range range) {
        Map<String, Object> overview = data.overview(range);
        @SuppressWarnings("unchecked") Map<String, Object> kpis = (Map<String, Object>) overview.get("kpis");
        String[][] labels = {
            {"issued", "발급"}, {"invited", "초대로 발급"}, {"bound", "등록"}, {"opened", "입장권 화면을 연 사람"},
            {"admittedPeople", "입장한 사람"}, {"reentries", "재입장"}, {"peakInside", "최대 동시 체류"},
            {"peakAt", "최대 동시 체류 시각"}, {"avgStayMinutes", "평균 체류(분)"}, {"couponsIssued", "쿠폰 발행"},
            {"couponsRedeemed", "쿠폰 사용"}, {"chatMessages", "채팅 메시지"}, {"chatReports", "채팅 신고"},
            {"adViews", "홍보글 조회"}, {"invitesCancelled", "초대 취소"}, {"mailSent", "메일 보냄"},
            {"mailFailed", "메일 실패"}};
        Csv numbers = new Csv(List.of(col("name", "지표"), col("value", "값")), false);
        for (String[] l : labels) {
            Object value = kpis.get(l[0]);
            numbers.add(Map.of("name", l[1], "value", value == null ? "" : l[0].equals("peakAt")
                ? time(DataQueryService.time(String.valueOf(value))) : String.valueOf(value)));
        }
        Csv hourly = new Csv(List.of(col("at", "시간"), col("entries", "입장"), col("exits", "퇴장")), false);
        for (Object o : (List<?>) overview.get("hourly")) {
            Map<?, ?> h = (Map<?, ?>) o;
            hourly.add(Map.of("at", time(DataQueryService.time(String.valueOf(h.get("at")))),
                "entries", String.valueOf(h.get("entries")), "exits", String.valueOf(h.get("exits"))));
        }
        Csv gates = new Csv(List.of(col("name", "게이트"), col("admitted", "입장"), col("denied", "거절"),
            col("face", "얼굴 확인 비율")), false);
        for (Object o : (List<?>) overview.get("gates")) {
            Map<?, ?> g = (Map<?, ?>) o;
            gates.add(Map.of("name", text(g.get("name")), "admitted", String.valueOf(g.get("admitted")),
                "denied", String.valueOf(g.get("denied")),
                "face", Math.round(((Number) g.get("faceShare")).doubleValue() * 100) + "%"));
        }
        Csv booths = new Csv(List.of(col("name", "부스"), col("redeemed", "쿠폰 사용")), false);
        for (Object o : (List<?>) overview.get("booths")) {
            Map<?, ?> b = (Map<?, ?>) o;
            booths.add(Map.of("name", text(b.get("name")), "redeemed", String.valueOf(b.get("redeemed"))));
        }
        return List.of(
            new File("요약_핵심숫자.csv", "핵심 숫자", "발급부터 쿠폰까지", numbers.rows, numbers.text()),
            new File("요약_시간대별.csv", "시간대별", "한 시간 단위 입장 · 퇴장", hourly.rows, hourly.text()),
            new File("요약_게이트별.csv", "게이트별", "게이트마다 입장 · 거절", gates.rows, gates.text()),
            new File("요약_부스별.csv", "부스별", "부스마다 쿠폰 사용", booths.rows, booths.text()));
    }

    // ---- Helpers ----

    private static String tierFilter(String alias, String tier, List<Object> args) {
        if (tier == null || tier.isBlank()) return "";
        args.add(tier.trim());
        return " AND " + alias + ".tier = ?";
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value == null ? "" : value);
    }

    private static String firstOf(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    static String time(Timestamp at) { return at == null ? "" : CSV_TIME.format(at.toInstant()); }

    /** Masks every address in a cell, and the last part of an IP. */
    static String mask(String key, String value) {
        if (value == null || value.isEmpty()) return value;
        if ("ip".equals(key)) {
            int dot = value.lastIndexOf('.');
            int colon = value.lastIndexOf(':');
            int cut = Math.max(dot, colon);
            return cut > 0 ? value.substring(0, cut + 1) + "*" : "*";
        }
        if ("phone".equals(key)) {
            return value.length() < 5 ? "***" : value.substring(0, 3) + "****" + value.substring(value.length() - 2);
        }
        return EMAIL.matcher(value).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(TicketService.mask(m.group())));
    }

    /** One CSV under construction: Excel-readable (BOM, CRLF), formulas defused. */
    private static final class Csv {
        final List<Column> columns;
        final boolean masked;
        final StringBuilder out = new StringBuilder("﻿");
        int rows;

        Csv(List<Column> columns, boolean masked) {
            this.columns = columns;
            this.masked = masked;
            out.append(String.join(",", columns.stream().map(c -> cell(c.label())).toList())).append("\r\n");
        }

        void add(Map<String, String> row) {
            List<String> cells = new ArrayList<>(columns.size());
            for (Column c : columns) {
                String value = row.getOrDefault(c.key(), "");
                // Masking reaches into free text too: an address inside a log's JSON is still an address.
                if (masked && (c.pii() || "detail".equals(c.key()) || "summary".equals(c.key()) || "body".equals(c.key()))) {
                    value = mask(c.key(), value);
                }
                cells.add(cell(value == null ? "" : value));
            }
            out.append(String.join(",", cells)).append("\r\n");
            rows++;
        }

        String text() { return out.toString(); }

        static String cell(String value) {
            String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
            boolean quote = safe.indexOf('"') >= 0 || safe.indexOf(',') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0;
            return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
        }
    }
}
