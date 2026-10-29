package com.plink.ticket.community;

import com.plink.ticket.live.LiveEvents;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Gate;
import com.plink.ticket.model.Presence;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.AdmissionRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The room's own channels: a chat among the people who have come in, and a board of
 * booth and organiser notices.
 *
 * <p>Both open only to a holder who has proved the ticket is theirs on this browser and
 * has been through a gate. Writing to the chat further needs them inside now, not muted,
 * and not to have written in the last cooldown. Holders appear under a name made from
 * their ticket ("초록 여우"), never their seat or address; the console sees the ticket.
 */
@Service
public class CommunityService {
    /** What a board notice is about. NOTICE is the organiser's own. */
    public static final List<String> CATEGORIES = List.of("SALE", "PROGRAM", "NOTICE");
    private static final int PAGE = 80;
    /** A notice pinned "until taken down" is pinned this long, which outlasts any event. */
    private static final Duration NOTICE_PIN = Duration.ofDays(365);

    private final CommunityRepository repo;
    private final TicketService tickets;
    private final TicketPasskeyService passkeys;
    private final AdmissionRepository admissions;
    private final TicketRepository ticketRows;
    private final LiveEvents live;
    private final AdImageService images;

    public CommunityService(CommunityRepository repo, TicketService tickets, TicketPasskeyService passkeys,
            AdmissionRepository admissions, TicketRepository ticketRows, LiveEvents live, AdImageService images) {
        this.images = images;
        this.repo = repo;
        this.tickets = tickets;
        this.passkeys = passkeys;
        this.admissions = admissions;
        this.ticketRows = ticketRows;
        this.live = live;
    }

    // ---- Who may read and write ----

    /** A holder let into the room's channels, and whether they may write to the chat now. */
    public record Member(Ticket ticket, boolean canWrite, String writeBlocked) {}

    /** The holder themselves, on a browser that has proved it: who an organiser's alert reaches. */
    private boolean viewer(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = resolved.ticket;
        return !resolved.viaTransfer() && ticket.claimed() && !ticket.revoked() && passkeys.canView(session, ticket);
    }

    /** Whether this holder has come in yet, without refusing: what the ticket page asks first. */
    public boolean entered(TicketService.Resolved resolved, HttpSession session) {
        if (!viewer(resolved, session)) return false;
        return admissions.find(resolved.ticket.id).map(p -> p.entryCount > 0).orElse(false);
    }

    public Member member(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = resolved.ticket;
        if (resolved.viaTransfer() || !ticket.claimed() || ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "등록을 마친 입장권에서 볼 수 있어요.");
        }
        if (!passkeys.canView(session, ticket)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키로 본인 확인을 먼저 해 주세요.");
        }
        Presence presence = admissions.find(ticket.id).orElse(null);
        if (presence == null || presence.entryCount == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "입장한 뒤에 볼 수 있어요.");
        }
        CommunityRepository.Settings settings = repo.settings(ticket.sessionId);
        String blocked = !settings.enabled() ? "지금은 채팅이 닫혀 있어요."
            : !presence.inside() ? "퇴장한 뒤에는 읽기만 할 수 있어요."
            : repo.muted(ticket.sessionId, ticket.id) ? "운영팀이 이 입장권의 채팅 쓰기를 막았어요."
            : null;
        return new Member(ticket, blocked == null, blocked);
    }

    /**
     * The name a holder writes under: two words picked from the ticket, the same every
     * time and unrelated to the seat or the address.
     */
    public static String nickname(long sessionId, long ticketId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("chat:" + sessionId + ":" + ticketId).getBytes(StandardCharsets.UTF_8));
            String color = COLORS[Math.floorMod(digest[0], COLORS.length)];
            String animal = ANIMALS[Math.floorMod(digest[1] * 31 + digest[2], ANIMALS.length)];
            return color + " " + animal;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final String[] COLORS = {
        "초록", "파란", "노란", "분홍", "보라", "하얀", "검은", "빨간", "주황", "하늘", "연두", "회색", "갈색", "남색", "민트", "금빛",
    };
    private static final String[] ANIMALS = {
        "여우", "고래", "다람쥐", "고양이", "수달", "펭귄", "부엉이", "토끼", "사슴", "판다", "코알라", "돌고래",
        "거북이", "고슴도치", "너구리", "물개", "햄스터", "앵무새", "알파카", "기린", "코끼리", "얼룩말", "참새", "오리",
        "강아지", "곰", "늑대", "사자", "호랑이", "두더지", "청설모", "비버",
    };

    private String author(CommunityRepository.Message m) {
        return m.staff() ? "운영팀" : m.ticketId() == null ? "알 수 없음" : nickname(m.sessionId(), m.ticketId());
    }

    // ---- The chat, from a holder's ticket ----

    public Map<String, Object> chat(TicketService.Resolved resolved, HttpSession session, long afterId) {
        Member member = member(resolved, session);
        long sessionId = member.ticket().sessionId;
        CommunityRepository.Settings settings = repo.settings(sessionId);
        Timestamp now = Timestamp.from(Instant.now());
        List<CommunityRepository.Message> rows = repo.recent(sessionId, Math.max(afterId, 0), PAGE, false);
        Set<Long> liked = repo.likedBy(member.ticket().id, rows.stream().map(CommunityRepository.Message::id).toList());

        Map<String, Object> me = new LinkedHashMap<>();
        me.put("nickname", nickname(sessionId, member.ticket().id));
        me.put("canWrite", member.canWrite());
        me.put("blocked", member.writeBlocked());
        me.put("nextAt", nextAt(member.ticket().id, settings).map(Instant::toString).orElse(null));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("me", me);
        result.put("cooldownSeconds", settings.cooldownSeconds());
        result.put("maxLength", settings.maxLength());
        result.put("hotLikes", settings.hotLikes());
        result.put("inside", admissions.occupancy(sessionId).getOrDefault("inside", 0));
        result.put("notice", repo.pinnedNotice(sessionId, now).map(m -> holderView(m, member, Set.of())).orElse(null));
        result.put("hot", repo.pinnedHot(sessionId, now).map(m -> holderView(m, member, Set.of())).orElse(null));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (CommunityRepository.Message m : rows) messages.add(holderView(m, member, liked));
        result.put("messages", messages);
        return result;
    }

    private Map<String, Object> holderView(CommunityRepository.Message m, Member member, Set<Long> liked) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.id());
        row.put("author", author(m));
        row.put("staff", m.staff());
        row.put("notice", m.notice());
        row.put("mine", m.ticketId() != null && m.ticketId() == member.ticket().id);
        row.put("body", m.body());
        row.put("likes", m.likes());
        row.put("liked", liked.contains(m.id()));
        row.put("createdAt", m.createdAt().toInstant().toString());
        row.put("pinnedUntil", m.pinnedUntil() == null ? null : m.pinnedUntil().toInstant().toString());
        if (m.replyTo() != null) {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("id", m.replyTo());
            reply.put("author", m.replyStaff() ? "운영팀"
                : m.replyTicketId() == null ? "알 수 없음" : nickname(m.sessionId(), m.replyTicketId()));
            reply.put("body", m.replyHidden() ? "가려진 메시지예요." : m.replyBody());
            row.put("reply", reply);
        }
        return row;
    }

    private java.util.Optional<Instant> nextAt(long ticketId, CommunityRepository.Settings settings) {
        return repo.lastWrittenBy(ticketId)
            .map(last -> last.toInstant().plusSeconds(settings.cooldownSeconds()))
            .filter(next -> next.isAfter(Instant.now()));
    }

    @Transactional
    public Map<String, Object> post(TicketService.Resolved resolved, HttpSession session, String rawBody, Long replyTo) {
        Member member = member(resolved, session);
        if (!member.canWrite()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, member.writeBlocked());
        long sessionId = member.ticket().sessionId;
        CommunityRepository.Settings settings = repo.settings(sessionId);
        String body = rawBody == null ? "" : rawBody.strip();
        if (body.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "메시지를 입력해 주세요.");
        if (body.codePointCount(0, body.length()) > settings.maxLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, settings.maxLength() + "자까지 보낼 수 있어요.");
        }
        // The ticket row is the lock: two taps at once cannot both get past the cooldown.
        ticketRows.lockById(member.ticket().id);
        java.util.Optional<Instant> next = nextAt(member.ticket().id, settings);
        if (next.isPresent()) {
            long wait = Math.max(1, Duration.between(Instant.now(), next.get()).toSeconds());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, wait + "초 뒤에 보낼 수 있어요.");
        }
        if (replyTo != null) {
            CommunityRepository.Message target = repo.findMessage(replyTo)
                .filter(m -> m.sessionId() == sessionId && !m.hidden())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "답장할 메시지를 찾을 수 없어요."));
            replyTo = target.id();
        }
        long id = repo.insertMessage(sessionId, member.ticket().id, false, false, body, replyTo, null, null,
            Timestamp.from(Instant.now()));
        ping(sessionId, id);
        return holderView(repo.findMessage(id).orElseThrow(), member, Set.of());
    }

    @Transactional
    public Map<String, Object> like(TicketService.Resolved resolved, HttpSession session, long messageId) {
        Member member = member(resolved, session);
        CommunityRepository.Message m = visibleMessage(member, messageId);
        boolean liked = repo.toggleLike(m.id(), member.ticket().id);
        CommunityRepository.Message now = repo.findMessage(m.id()).orElseThrow();
        CommunityRepository.Settings settings = repo.settings(m.sessionId());
        boolean pinned = false;
        if (liked && !now.notice() && now.likes() >= settings.hotLikes()) {
            pinned = repo.pinIfNeverPinned(now.id(),
                Timestamp.from(Instant.now().plus(settings.hotMinutes(), ChronoUnit.MINUTES)));
        }
        if (pinned) ping(m.sessionId(), repo.latestId(m.sessionId()));
        else live.publish(m.sessionId(), "CHAT_LIKE", Map.of("messageId", now.id(), "likes", now.likes()));
        return Map.of("messageId", now.id(), "likes", now.likes(), "liked", liked);
    }

    @Transactional
    public Map<String, Object> report(TicketService.Resolved resolved, HttpSession session, long messageId) {
        Member member = member(resolved, session);
        CommunityRepository.Message m = visibleMessage(member, messageId);
        if (m.staff()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "운영팀 메시지는 신고할 수 없어요.");
        if (m.ticketId() != null && m.ticketId() == member.ticket().id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내 메시지는 신고할 수 없어요.");
        }
        repo.report(m.id(), member.ticket().id);
        CommunityRepository.Message now = repo.findMessage(m.id()).orElseThrow();
        if (!now.hidden() && now.reports() >= repo.settings(m.sessionId()).reportHide()) {
            repo.setHidden(now.id(), true);
            live.publish(m.sessionId(), "CHAT_HIDE", Map.of("messageId", now.id()));
        }
        live.publish(m.sessionId(), "CHAT_REPORT", Map.of("messageId", now.id()));
        return Map.of("reported", true);
    }

    private CommunityRepository.Message visibleMessage(Member member, long messageId) {
        return repo.findMessage(messageId)
            .filter(m -> m.sessionId() == member.ticket().sessionId && !m.hidden())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없어요."));
    }

    /** Tells every phone and console on the event there is something new, without saying what. */
    private void ping(long sessionId, long lastId) {
        live.publish(sessionId, "CHAT", Map.of("lastId", lastId));
    }

    // ---- The board, from a holder's ticket ----

    public List<Map<String, Object>> ads(TicketService.Resolved resolved, HttpSession session) {
        Member member = member(resolved, session);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CommunityRepository.Ad ad : repo.visibleAds(member.ticket().sessionId, Timestamp.from(Instant.now()))) {
            rows.add(adView(ad, false));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> ad(TicketService.Resolved resolved, HttpSession session, long adId) {
        Member member = member(resolved, session);
        Timestamp now = Timestamp.from(Instant.now());
        CommunityRepository.Ad ad = repo.visibleAds(member.ticket().sessionId, now).stream()
            .filter(a -> a.id() == adId).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "글을 찾을 수 없어요."));
        repo.countView(ad.id());
        return adView(repo.findAd(ad.id()).orElseThrow(), true);
    }

    /**
     * What the ticket page says before the channels are opened: whether they are open to
     * this holder, and enough to show on the tiles. Never refuses; a holder who has not
     * come in hears only that.
     */
    public Map<String, Object> summary(TicketService.Resolved resolved, HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean viewer = viewer(resolved, session);
        boolean entered = viewer && admissions.find(resolved.ticket.id).map(p -> p.entryCount > 0).orElse(false);
        long sessionId = resolved.ticket.sessionId;
        Timestamp now = Timestamp.from(Instant.now());
        result.put("entered", entered);
        // An alert is for everyone holding a ticket to the event, in or not yet.
        if (viewer) {
            repo.activeAlert(sessionId, now).ifPresent(m -> result.put("alert", Map.of(
                "id", m.id(), "body", m.body(), "createdAt", m.createdAt().toInstant().toString())));
        }
        if (!entered) return result;
        result.put("latestChatId", repo.latestId(sessionId));
        result.put("inside", admissions.occupancy(sessionId).getOrDefault("inside", 0));
        result.put("notice", repo.pinnedNotice(sessionId, now).map(CommunityRepository.Message::body).orElse(null));
        // The latest thing someone said; a notice already has its own line on the tile.
        List<CommunityRepository.Message> last = repo.recent(sessionId, 0, 10, false);
        for (int i = last.size() - 1; i >= 0; i--) {
            if (last.get(i).notice()) continue;
            result.put("lastMessage", Map.of("author", author(last.get(i)), "body", last.get(i).body()));
            break;
        }
        List<CommunityRepository.Ad> ads = repo.visibleAds(sessionId, now);
        result.put("adCount", ads.size());
        result.put("latestAdId", ads.stream().mapToLong(CommunityRepository.Ad::id).max().orElse(0));
        if (!ads.isEmpty()) {
            CommunityRepository.Ad top = ads.stream().filter(a -> !a.pinned()).findFirst().orElse(ads.get(0));
            result.put("topAd", Map.of("booth", top.booth() == null ? "운영팀" : top.booth(), "title", top.title()));
        }
        return result;
    }

    private Map<String, Object> adView(CommunityRepository.Ad ad, boolean withBody) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ad.id());
        row.put("category", ad.category());
        row.put("title", ad.title());
        if (withBody) row.put("body", ad.body());
        row.put("booth", ad.booth() == null ? "운영팀" : ad.booth());
        row.put("boothId", ad.boothId());
        row.put("place", ad.place());
        row.put("startsAt", ad.startsAt().toInstant().toString());
        row.put("endsAt", ad.endsAt() == null ? null : ad.endsAt().toInstant().toString());
        row.put("pinned", ad.pinned());
        row.put("views", ad.views());
        row.put("createdAt", ad.createdAt().toInstant().toString());
        // The id names the version of the photo, so a replaced one is not served from cache.
        row.put("imageId", ad.imageId());
        return row;
    }

    // ---- The console ----

    public Map<String, Object> consoleChat(long sessionId, String filter) {
        tickets.requireSession(sessionId);
        Timestamp now = Timestamp.from(Instant.now());
        Set<Long> muted = repo.mutedTickets(sessionId);
        String which = filter == null ? "ALL" : filter.toUpperCase(java.util.Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CommunityRepository.Message m : repo.feed(sessionId, which, 200, now)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("author", author(m));
            row.put("ticketId", m.ticketId());
            row.put("ticketRef", m.ticketRef());
            row.put("staff", m.staff());
            row.put("notice", m.notice());
            row.put("body", m.body());
            row.put("likes", m.likes());
            row.put("reports", m.reports());
            row.put("hidden", m.hidden());
            row.put("pinned", m.pinnedUntil() != null && m.pinnedUntil().after(now));
            row.put("pinnedUntil", m.pinnedUntil() == null ? null : m.pinnedUntil().toInstant().toString());
            row.put("muted", m.ticketId() != null && muted.contains(m.ticketId()));
            row.put("alert", m.alertUntil() != null && m.alertUntil().after(now));
            row.put("createdAt", m.createdAt().toInstant().toString());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", settingsView(repo.settings(sessionId)));
        result.put("messages", rows);
        result.put("reported", repo.feed(sessionId, "REPORTED", 500, now).stream().filter(m -> !m.hidden()).count());
        result.put("hiddenCount", repo.feed(sessionId, "HIDDEN", 500, now).size());
        return result;
    }

    @Transactional
    public Map<String, Object> notice(long sessionId, String rawBody, boolean pin, boolean alsoAd, boolean alert) {
        EventSession event = tickets.requireSession(sessionId);
        String body = rawBody == null ? "" : rawBody.strip();
        if (body.isEmpty() || body.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공지는 1자부터 500자까지예요.");
        }
        Instant now = Instant.now();
        long id = repo.insertMessage(event.id, null, true, true, body, null,
            pin ? Timestamp.from(now.plus(NOTICE_PIN)) : null, alert ? Timestamp.from(now.plus(NOTICE_PIN)) : null,
            Timestamp.from(now));
        if (alsoAd) {
            String title = body.length() > 80 ? body.substring(0, 79) + "…" : body;
            repo.insertAd(event.id, null, "NOTICE", title, body, null, Timestamp.from(now), null, true, null);
            live.publish(event.id, "ADS", Map.of());
        }
        ping(event.id, id);
        if (alert) live.publish(event.id, "ALERT", Map.of());
        return Map.of("messageId", id);
    }

    /** Takes a notice off the holders' tickets, or puts it back. */
    public void setAlert(long sessionId, long messageId, boolean alert) {
        CommunityRepository.Message m = consoleMessage(sessionId, messageId);
        if (!m.notice()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공지만 입장권 화면에 알릴 수 있어요.");
        Instant now = Instant.now();
        repo.setAlertUntil(m.id(), Timestamp.from(alert ? now.plus(NOTICE_PIN) : now));
        live.publish(sessionId, "ALERT", Map.of());
    }

    public void setHidden(long sessionId, long messageId, boolean hidden) {
        CommunityRepository.Message m = consoleMessage(sessionId, messageId);
        repo.setHidden(m.id(), hidden);
        live.publish(sessionId, hidden ? "CHAT_HIDE" : "CHAT", hidden ? Map.of("messageId", m.id()) : Map.of("lastId", m.id()));
        if (m.alertUntil() != null) live.publish(sessionId, "ALERT", Map.of());
    }

    public void setPinned(long sessionId, long messageId, boolean pinned) {
        CommunityRepository.Message m = consoleMessage(sessionId, messageId);
        Instant now = Instant.now();
        repo.setPinnedUntil(m.id(), Timestamp.from(pinned
            ? now.plus(m.notice() ? NOTICE_PIN : Duration.ofMinutes(repo.settings(sessionId).hotMinutes()))
            : now));
        ping(sessionId, repo.latestId(sessionId));
    }

    public void setMuted(long sessionId, long ticketId, boolean muted) {
        tickets.requireSession(sessionId);
        Ticket ticket = ticketRows.findById(ticketId).filter(t -> t.sessionId == sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권을 찾을 수 없어요."));
        repo.setMuted(sessionId, ticket.id, muted);
    }

    private CommunityRepository.Message consoleMessage(long sessionId, long messageId) {
        return repo.findMessage(messageId).filter(m -> m.sessionId() == sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없어요."));
    }

    public Map<String, Object> saveSettings(long sessionId, Map<String, Object> body) {
        tickets.requireSession(sessionId);
        CommunityRepository.Settings current = repo.settings(sessionId);
        CommunityRepository.Settings next = new CommunityRepository.Settings(
            body.get("enabled") == null ? current.enabled() : Boolean.parseBoolean(body.get("enabled").toString()),
            bounded(body.get("cooldownSeconds"), current.cooldownSeconds(), 0, 3600, "입력 간격"),
            bounded(body.get("maxLength"), current.maxLength(), 10, 500, "최대 글자"),
            bounded(body.get("hotLikes"), current.hotLikes(), 1, 10000, "인기 고정 기준"),
            bounded(body.get("hotMinutes"), current.hotMinutes(), 1, 600, "고정 시간"),
            bounded(body.get("reportHide"), current.reportHide(), 1, 1000, "자동 가림 기준"));
        repo.saveSettings(sessionId, next);
        return settingsView(next);
    }

    private static int bounded(Object raw, int fallback, int min, int max, String label) {
        if (raw == null || raw.toString().isBlank()) return fallback;
        int value;
        try { value = Integer.parseInt(raw.toString().trim()); }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "는 숫자여야 해요."); }
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "는 " + min + "부터 " + max + "까지예요.");
        }
        return value;
    }

    private static Map<String, Object> settingsView(CommunityRepository.Settings s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", s.enabled());
        view.put("cooldownSeconds", s.cooldownSeconds());
        view.put("maxLength", s.maxLength());
        view.put("hotLikes", s.hotLikes());
        view.put("hotMinutes", s.hotMinutes());
        view.put("reportHide", s.reportHide());
        return view;
    }

    // ---- The board, from the console and from a booth's terminal ----

    public List<Map<String, Object>> consoleAds(long sessionId) {
        tickets.requireSession(sessionId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CommunityRepository.Ad ad : repo.allAds(sessionId)) rows.add(withState(ad));
        return rows;
    }

    /** An ad as the console and the booth see it: the holder's view plus where it stands. */
    private Map<String, Object> withState(CommunityRepository.Ad ad) {
        Map<String, Object> row = adView(ad, true);
        Instant now = Instant.now();
        row.put("hidden", ad.hidden());
        row.put("state", ad.hidden() ? "HIDDEN"
            : ad.startsAt().toInstant().isAfter(now) ? "SCHEDULED"
            : ad.endsAt() != null && !ad.endsAt().toInstant().isAfter(now) ? "ENDED" : "LIVE");
        return row;
    }

    /** What an ad is made of, checked once for the console and the booth alike. */
    private record AdInput(String category, String title, String body, String place, Timestamp startsAt,
            Timestamp endsAt, boolean pinned, String imageId) {}

    private AdInput input(long sessionId, Map<String, Object> body, boolean staff) {
        String category = text(body.get("category"));
        if (category == null || !CATEGORIES.contains(category) || (!staff && "NOTICE".equals(category))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "분류를 골라 주세요.");
        }
        String title = text(body.get("title"));
        String text = text(body.get("body"));
        if (title == null || title.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목은 1자부터 80자까지예요.");
        if (text == null || text.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용은 1자부터 1,000자까지예요.");
        String place = text(body.get("place"));
        if (place != null && place.length() > 60) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "위치는 60자까지예요.");
        Timestamp startsAt = instant(body.get("startsAt"), Instant.now());
        Timestamp endsAt = body.get("endsAt") == null || body.get("endsAt").toString().isBlank() ? null
            : instant(body.get("endsAt"), null);
        if (endsAt != null && !endsAt.after(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "끝나는 시각은 시작보다 뒤여야 해요.");
        }
        boolean pinned = staff && Boolean.TRUE.equals(body.get("pinned"));
        return new AdInput(category, title, text, place, startsAt, endsAt, pinned,
            images.attachable(sessionId, text(body.get("imageId"))));
    }

    private static String text(Object raw) {
        if (raw == null) return null;
        String value = raw.toString().strip();
        return value.isEmpty() ? null : value;
    }

    private static Timestamp instant(Object raw, Instant fallback) {
        if (raw == null || raw.toString().isBlank()) return fallback == null ? null : Timestamp.from(fallback);
        try { return Timestamp.from(Instant.parse(raw.toString())); }
        catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시각 형식이 올바르지 않아요.");
        }
    }

    public Map<String, Object> createAd(long sessionId, Map<String, Object> body) {
        tickets.requireSession(sessionId);
        AdInput in = input(sessionId, body, true);
        Long boothId = body.get("boothId") instanceof Number n ? n.longValue() : null;
        long id = repo.insertAd(sessionId, boothId, in.category(), in.title(), in.body(), in.place(),
            in.startsAt(), in.endsAt(), in.pinned(), in.imageId());
        live.publish(sessionId, "ADS", Map.of());
        return withState(repo.findAd(id).orElseThrow());
    }

    public Map<String, Object> updateAd(long sessionId, long adId, Map<String, Object> body) {
        CommunityRepository.Ad ad = consoleAd(sessionId, adId);
        AdInput in = input(sessionId, body, true);
        repo.updateAd(ad.id(), in.category(), in.title(), in.body(), in.place(), in.startsAt(), in.endsAt(), in.pinned(),
            in.imageId());
        images.release(ad.imageId());
        live.publish(sessionId, "ADS", Map.of());
        return withState(repo.findAd(ad.id()).orElseThrow());
    }

    public void setAdHidden(long sessionId, long adId, boolean hidden) {
        repo.setAdHidden(consoleAd(sessionId, adId).id(), hidden);
        live.publish(sessionId, "ADS", Map.of());
    }

    public void deleteAd(long sessionId, long adId) {
        CommunityRepository.Ad ad = consoleAd(sessionId, adId);
        repo.deleteAd(ad.id());
        images.release(ad.imageId());
        live.publish(sessionId, "ADS", Map.of());
    }

    private CommunityRepository.Ad consoleAd(long sessionId, long adId) {
        return repo.findAd(adId).filter(a -> a.sessionId() == sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "글을 찾을 수 없어요."));
    }

    /** A booth terminal works on its own booth's notices and no one else's. */
    private long boothOf(Gate gate) {
        if (!"BOOTH".equals(gate.role) || gate.boothId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "부스 단말에서만 광고를 올릴 수 있어요.");
        }
        return gate.boothId;
    }

    public List<Map<String, Object>> boothAds(Gate gate) {
        long boothId = boothOf(gate);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CommunityRepository.Ad ad : repo.boothAds(gate.sessionId, boothId)) rows.add(withState(ad));
        return rows;
    }

    public Map<String, Object> boothCreate(Gate gate, Map<String, Object> body) {
        long boothId = boothOf(gate);
        AdInput in = input(gate.sessionId, body, false);
        long id = repo.insertAd(gate.sessionId, boothId, in.category(), in.title(), in.body(), in.place(),
            in.startsAt(), in.endsAt(), false, in.imageId());
        live.publish(gate.sessionId, "ADS", Map.of());
        return withState(repo.findAd(id).orElseThrow());
    }

    public Map<String, Object> boothUpdate(Gate gate, long adId, Map<String, Object> body) {
        CommunityRepository.Ad ad = boothAd(gate, adId);
        AdInput in = input(gate.sessionId, body, false);
        repo.updateAd(ad.id(), in.category(), in.title(), in.body(), in.place(), in.startsAt(), in.endsAt(), ad.pinned(),
            in.imageId());
        images.release(ad.imageId());
        live.publish(gate.sessionId, "ADS", Map.of());
        return withState(repo.findAd(ad.id()).orElseThrow());
    }

    public void boothEnd(Gate gate, long adId) {
        repo.endAd(boothAd(gate, adId).id());
        live.publish(gate.sessionId, "ADS", Map.of());
    }

    private CommunityRepository.Ad boothAd(Gate gate, long adId) {
        long boothId = boothOf(gate);
        return repo.findAd(adId).filter(a -> a.sessionId() == gate.sessionId && a.boothId() != null && a.boothId() == boothId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "우리 부스 글이 아니에요."));
    }

    // ---- Photos ----

    public Map<String, Object> consoleUpload(long sessionId, org.springframework.web.multipart.MultipartFile file) {
        tickets.requireSession(sessionId);
        return Map.of("imageId", images.upload(sessionId, file));
    }

    public Map<String, Object> boothUpload(Gate gate, org.springframework.web.multipart.MultipartFile file) {
        boothOf(gate);
        return Map.of("imageId", images.upload(gate.sessionId, file));
    }

    /** A notice's photo, to a holder who may see the notice now. */
    public AdImageService.Download holderImage(TicketService.Resolved resolved, HttpSession session, long adId) {
        Member member = member(resolved, session);
        CommunityRepository.Ad ad = repo.visibleAds(member.ticket().sessionId, Timestamp.from(Instant.now())).stream()
            .filter(a -> a.id() == adId).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없어요."));
        return photoOf(ad);
    }

    public AdImageService.Download consoleImage(long sessionId, long adId) {
        return photoOf(consoleAd(sessionId, adId));
    }

    public AdImageService.Download boothImage(Gate gate, long adId) {
        return photoOf(boothAd(gate, adId));
    }

    private AdImageService.Download photoOf(CommunityRepository.Ad ad) {
        if (ad.imageId() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사진이 없는 글이에요.");
        return images.read(ad.imageId());
    }
}
