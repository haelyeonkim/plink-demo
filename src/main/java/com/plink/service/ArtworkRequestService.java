package com.plink.service;

import com.plink.activity.Activity;
import com.plink.activity.ActivityLog;
import com.plink.model.LinkRecipient;
import com.plink.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Every transition locks the artwork first, including expiry and legacy status edits. */
@Service
public class ArtworkRequestService {
    public record Command(String action, Long version, String key, Instant holdUntil, String reason, Boolean releaseHold) {}
    public record State(long id, long artworkId, long recipientId, long linkId, String recipientEmail,
            String artworkTitle, boolean interested, String holdState, Timestamp holdUntil,
            boolean purchaseIntent, boolean purchased, boolean cancellationRequested, long version, Timestamp updatedAt) {}
    private final JdbcTemplate jdbc;
    private final LinkRecipientRepository recipients;
    private final LinkRepository links;
    private final ArtworkRepository artworks;
    private final ContentImageService grants;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();

    public ArtworkRequestService(JdbcTemplate jdbc, LinkRecipientRepository recipients, LinkRepository links,
            ArtworkRepository artworks, ContentImageService grants, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.recipients = recipients; this.links = links; this.artworks = artworks;
        this.grants = grants; this.events = events;
    }
    private final org.springframework.jdbc.core.RowMapper<State> row = (r, n) -> new State(
        r.getLong("id"), r.getLong("artwork_id"), r.getLong("recipient_id"), r.getLong("link_id"),
        r.getString("recipient_email"), r.getString("artwork_title"), r.getBoolean("interested"),
        r.getString("hold_state"), r.getTimestamp("hold_until"), r.getBoolean("purchase_intent"),
        r.getBoolean("purchased"), r.getBoolean("cancellation_requested"), r.getLong("version"), r.getTimestamp("updated_at"));

    private LinkRecipient authorize(String code, HttpSession session) {
        var recipient = recipients.findByCode(code).orElseThrow(() -> denied());
        var link = links.findById(recipient.linkId).orElseThrow(() -> denied());
        if (link.getContentId() == null || !grants.hasContentGrant(link.getContentId(), link.getId(), recipient.id, session)) throw denied();
        return recipient;
    }
    public List<State> mine(String code, HttpSession session) {
        var recipient = authorize(code, session);
        return jdbc.query("SELECT * FROM artwork_request WHERE recipient_id = ? ORDER BY id", row, recipient.id);
    }
    public Map<String, Object> list(String filter, int page, String search) {
        if (page < 0 || page > 100000 || search.length() > 200) throw bad("검색 조건을 확인해 주세요.");
        String where = switch (filter) {
            case "all" -> "1=1";
            case "pending" -> "hold_state = 'PENDING'";
            case "held" -> "hold_state = 'HELD'";
            case "purchase" -> "purchase_intent = TRUE AND purchased = FALSE";
            case "cancel" -> "cancellation_requested = TRUE";
            case "interested" -> "interested = TRUE";
            default -> throw bad("검색 조건을 확인해 주세요.");
        };
        where += " AND (LOWER(artwork_title) LIKE ? OR LOWER(recipient_email) LIKE ?)";
        String term = "%" + search.toLowerCase(Locale.ROOT) + "%";
        return Map.of("items", jdbc.query("SELECT * FROM artwork_request WHERE " + where
            + " ORDER BY updated_at DESC, id DESC LIMIT 30 OFFSET ?", row, term, term, page * 30),
            "total", jdbc.queryForObject("SELECT COUNT(*) FROM artwork_request WHERE " + where, Long.class, term, term));
    }
    public List<Map<String, Object>> history(long id) {
        return jdbc.query("SELECT * FROM artwork_request_event WHERE request_id = ? ORDER BY id DESC LIMIT 200", (r, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getLong("id")); item.put("action", r.getString("action"));
            item.put("actorType", r.getString("actor_type")); item.put("actorId", r.getString("actor_id"));
            item.put("before", json.readTree(r.getString("before_state"))); item.put("after", json.readTree(r.getString("after_state")));
            item.put("reason", r.getString("reason")); item.put("occurredAt", r.getTimestamp("occurred_at"));
            return item;
        }, id);
    }
    private String lock(long artworkId) {
        var values = jdbc.query("SELECT sale_status FROM artwork WHERE id = ? FOR UPDATE", (r, n) -> r.getString(1), artworkId);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return values.get(0);
    }
    private State get(long id) {
        return jdbc.query("SELECT * FROM artwork_request WHERE id = ?", row, id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    public State recipient(String code, long artworkId, Command command, HttpSession session) {
        var recipient = authorize(code, session);
        lock(artworkId);
        // Recheck after waiting for another transition's lock.
        authorize(code, session);
        long contentId = links.findById(recipient.linkId).orElseThrow(() -> denied()).getContentId();
        if (artworks.findLinked(contentId).values().stream().noneMatch(a -> a.id() == artworkId)) throw denied();
        var states = jdbc.query("SELECT * FROM artwork_request WHERE artwork_id = ? AND recipient_id = ?", row, artworkId, recipient.id);
        if (states.isEmpty()) {
            jdbc.update("INSERT INTO artwork_request (artwork_id, recipient_id, link_id, recipient_email, artwork_title) "
                + "SELECT id, ?, ?, ?, title FROM artwork WHERE id = ?", recipient.id, recipient.linkId, recipient.email, artworkId);
            states = jdbc.query("SELECT * FROM artwork_request WHERE artwork_id = ? AND recipient_id = ?", row, artworkId, recipient.id);
        }
        return apply(states.get(0), command, "RECIPIENT", Long.toString(recipient.id));
    }
    @Transactional
    public State admin(long id, Command command, String actor) {
        State state = get(id);
        lock(state.artworkId());
        return apply(get(id), command, "ADMIN", actor);
    }
    private State apply(State initial, Command c, String actorType, String actorId) {
        if (c.action() == null || c.version() == null || c.key() == null || !c.key().matches("[A-Za-z0-9-]{16,80}")
                || c.reason() != null && c.reason().length() > 1000) throw bad("요청 내용을 확인해 주세요.");
        String commandBody = json.writeValueAsString(c);
        var previous = jdbc.queryForList("SELECT command_body, actor_type, actor_id FROM artwork_request_event WHERE request_id = ? AND command_key = ?", initial.id(), c.key());
        if (!previous.isEmpty()) {
            var p = previous.get(0);
            if (!Objects.equals(p.get("command_body"), commandBody) || !Objects.equals(p.get("actor_type"), actorType)
                    || !Objects.equals(p.get("actor_id"), actorId)) throw conflict("이미 사용한 요청 식별자입니다.");
            return get(initial.id());
        }
        expireLocked(initial.artworkId());
        State s = get(initial.id());
        if (s.version() != c.version()) throw conflict("상태가 변경되었습니다. 새로고침 후 다시 시도해 주세요.");
        boolean admin = actorType.equals("ADMIN");
        Set<String> allowed = admin ? Set.of("APPROVE", "REJECT", "RELEASE", "SELL", "CANCEL_SALE", "KEEP_SALE")
            : Set.of("INTEREST", "UNINTEREST", "REQUEST_HOLD", "CANCEL_HOLD", "PURCHASE", "WITHDRAW", "REQUEST_CANCEL");
        if (!allowed.contains(c.action())) throw bad("허용되지 않는 액션입니다.");
        String sale = jdbc.queryForObject("SELECT sale_status FROM artwork WHERE id = ?", String.class, s.artworkId());
        boolean interested = s.interested(), purchase = s.purchaseIntent(), purchased = s.purchased(), cancel = s.cancellationRequested();
        String hold = s.holdState(); Timestamp until = s.holdUntil();
        if (s.purchased() && !Set.of("REQUEST_CANCEL", "CANCEL_SALE", "KEEP_SALE").contains(c.action())) throw conflict("판매 확정 후에는 취소 요청을 이용해 주세요.");
        switch (c.action()) {
            case "INTEREST" -> interested = true;
            case "UNINTEREST" -> interested = false;
            case "REQUEST_HOLD" -> {
                if (sale != null || hold.equals("PENDING")) throw conflict("현재 홀딩 요청을 할 수 없는 작품입니다.");
                hold = "PENDING"; until = null;
            }
            case "CANCEL_HOLD", "RELEASE" -> {
                if (!Set.of("PENDING", "HELD").contains(hold)) throw conflict("취소할 홀딩이 없습니다.");
                release(s); hold = "CANCELLED"; until = null;
            }
            case "PURCHASE" -> {
                if ("sold".equals(sale) || "hold".equals(sale) && !hold.equals("HELD")) throw conflict("현재 구매 의사를 전달할 수 없는 작품입니다.");
                purchase = true;
            }
            case "WITHDRAW" -> {
                if (!purchase) throw conflict("철회할 구매 의사가 없습니다.");
                purchase = false;
                if (Boolean.TRUE.equals(c.releaseHold()) && Set.of("HELD", "PENDING").contains(hold)) {
                    release(s); hold = "CANCELLED"; until = null;
                }
            }
            case "APPROVE" -> {
                if (!hold.equals("PENDING") || sale != null) throw conflict("승인할 수 없는 홀딩 요청입니다.");
                if (c.holdUntil() == null || !c.holdUntil().isAfter(Instant.now())) throw bad("미래의 홀딩 종료 시각을 입력해 주세요.");
                jdbc.update("INSERT INTO artwork_reservation (artwork_id, request_id) VALUES (?, ?)", s.artworkId(), s.id());
                setSale(s.artworkId(), "hold"); hold = "HELD"; until = Timestamp.from(c.holdUntil());
                closeOthers(s, "홀딩 승인으로 다른 요청 종료", false);
            }
            case "REJECT" -> {
                if (!hold.equals("PENDING")) throw conflict("승인 대기 중인 요청만 거절할 수 있습니다.");
                requireReason(c); hold = "REJECTED";
            }
            case "SELL" -> {
                if (!purchase || "sold".equals(sale) || "hold".equals(sale) && !hold.equals("HELD")) throw conflict("판매 확정할 수 없는 요청입니다.");
                release(s); setSale(s.artworkId(), "sold"); purchased = true; hold = "CLOSED"; until = null;
                closeOthers(s, "판매 확정으로 요청 종료", true);
            }
            case "REQUEST_CANCEL" -> {
                if (!purchased || cancel) throw conflict("취소 요청할 수 없는 상태입니다.");
                requireReason(c); cancel = true;
            }
            case "CANCEL_SALE" -> {
                if (!purchased || !cancel) throw conflict("판매 취소 요청이 없습니다.");
                requireReason(c); purchased = false; purchase = false; cancel = false; setSale(s.artworkId(), null);
            }
            case "KEEP_SALE" -> {
                if (!purchased || !cancel) throw conflict("판매 취소 요청이 없습니다.");
                requireReason(c); cancel = false;
            }
            default -> throw bad("요청 내용을 확인해 주세요.");
        }
        jdbc.update("UPDATE artwork_request SET interested=?, hold_state=?, hold_until=?, purchase_intent=?, purchased=?, "
            + "cancellation_requested=?, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?",
            interested, hold, until, purchase, purchased, cancel, s.id());
        State after = get(s.id());
        record(s, after, actorType, actorId, c.action(), c.reason(), c.key(), commandBody);
        return after;
    }
    private void requireReason(Command c) {
        if (c.reason() == null || c.reason().isBlank()) throw bad("사유를 입력해 주세요.");
    }
    private void release(State s) {
        if (jdbc.update("DELETE FROM artwork_reservation WHERE artwork_id=? AND request_id=?", s.artworkId(), s.id()) > 0) setSale(s.artworkId(), null);
    }
    private void setSale(long id, String value) {
        jdbc.update("UPDATE artwork SET sale_status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", value, id);
        events.publishEvent(new ArtworkStatusService.Changed());
    }
    private void closeOthers(State selected, String reason, boolean sold) {
        for (State other : jdbc.query("SELECT * FROM artwork_request WHERE artwork_id=? AND id<>? AND (hold_state='PENDING' OR purchase_intent=TRUE)", row, selected.artworkId(), selected.id())) {
            jdbc.update("UPDATE artwork_request SET hold_state=CASE WHEN hold_state='PENDING' THEN 'CLOSED' ELSE hold_state END, "
                + "purchase_intent=CASE WHEN ? THEN FALSE ELSE purchase_intent END, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?", sold, other.id());
            record(other, get(other.id()), "SYSTEM", "system", "CLOSED", reason, null, null);
        }
    }
    @Transactional
    public void expire(long artworkId) { lock(artworkId); expireLocked(artworkId); }
    private void expireLocked(long artworkId) {
        for (State s : jdbc.query("SELECT * FROM artwork_request WHERE artwork_id=? AND hold_state='HELD' AND hold_until<=CURRENT_TIMESTAMP", row, artworkId)) {
            release(s);
            jdbc.update("UPDATE artwork_request SET hold_state='EXPIRED', hold_until=NULL, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?", s.id());
            record(s, get(s.id()), "SYSTEM", "system", "EXPIRED", "홀딩 기한 만료", null, null);
        }
    }
    /** Legacy edits cannot silently override a recipient's reservation or completed sale. */
    @Transactional
    public void changeSaleStatus(long artworkId, Object value) {
        if (value != null && !value.equals("hold") && !value.equals("sold")) throw bad("판매 상태를 확인해 주세요.");
        String before = lock(artworkId);
        if (Objects.equals(before, value)) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM artwork_request WHERE artwork_id=? AND (hold_state='HELD' OR purchased=TRUE)", Integer.class, artworkId) > 0)
            throw conflict("수신자 요청 목록에서 홀딩 해제 또는 판매·취소 처리를 먼저 진행해 주세요.");
        setSale(artworkId, (String) value);
        if (value != null) {
            for (State s : jdbc.query("SELECT * FROM artwork_request WHERE artwork_id=? AND (hold_state='PENDING' OR purchase_intent=TRUE)", row, artworkId)) {
                jdbc.update("UPDATE artwork_request SET hold_state=CASE WHEN hold_state='PENDING' THEN 'CLOSED' ELSE hold_state END, purchase_intent=FALSE, version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?", s.id());
                record(s, get(s.id()), "ADMIN", currentActor(), "CLOSED", "관리자 작품 상태 변경", null, null);
            }
        }
        jdbc.update("INSERT INTO artwork_request_event (artwork_id, actor_type, actor_id, action, before_state, after_state, reason) VALUES (?, 'ADMIN', ?, 'SALE_STATUS', ?, ?, '')",
            artworkId, currentActor(), json.writeValueAsString(before), json.writeValueAsString(value));
    }
    public static String currentActor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.plink.account.AdminPrincipal admin) return Long.toString(admin.id);
        return auth == null ? "system" : auth.getName();
    }
    private void record(State before, State after, String type, String actor, String action, String reason, String key, String command) {
        jdbc.update("INSERT INTO artwork_request_event (request_id, artwork_id, actor_type, actor_id, action, before_state, after_state, reason, command_key, command_body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            before.id(), before.artworkId(), type, actor, action, json.writeValueAsString(before), json.writeValueAsString(after), reason == null ? "" : reason, key, command);
        ActivityLog.emit(Activity.of(Activity.LINK, "artwork." + action.toLowerCase(Locale.ROOT)).actor(type, actor, null)
            .put("request", before.id()).put("artwork", before.artworkId()).put("recipient", before.recipientId()).put("link", before.linkId()));
    }
    private ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키로 링크를 다시 열어 주세요."); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
