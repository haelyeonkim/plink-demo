package com.plink.ticket.venue;

import com.plink.service.ContentImageStorage;
import com.plink.ticket.community.AdImageService;
import com.plink.ticket.live.LiveEvents;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.service.TicketPasskeyService;
import com.plink.ticket.service.TicketService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The venue tab: what the organiser writes about the place, and what a holder reads.
 *
 * <p>A holder sees it once their ticket is registered on their phone - before they come
 * in, since parking and the way there are needed on the way. The organiser's sections
 * start hidden, so one half written never reaches a ticket.
 */
@Service
public class VenueService {
    /** A floor plan is read zoomed in, so it keeps more detail than a board photo. */
    private static final int PLAN_EDGE = 2400;
    private static final int MAX_SECTIONS = 30;

    private final VenueRepository repo;
    private final TicketService tickets;
    private final TicketPasskeyService passkeys;
    private final AdImageService drawing;
    private final ContentImageStorage storage;
    private final LiveEvents live;
    private final ObjectMapper mapper = new ObjectMapper();

    public VenueService(VenueRepository repo, TicketService tickets, TicketPasskeyService passkeys,
            AdImageService drawing, ContentImageStorage storage, LiveEvents live) {
        this.repo = repo;
        this.tickets = tickets;
        this.passkeys = passkeys;
        this.drawing = drawing;
        this.storage = storage;
        this.live = live;
    }

    public record Download(byte[] bytes, String type) {}

    // ---- The organiser ----

    public Map<String, Object> console(long sessionId) {
        EventSession event = tickets.requireSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("venue", event.venue);
        result.put("sections", repo.sections(sessionId).stream().map(this::describe).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> create(long sessionId, Map<String, Object> body) {
        tickets.requireSession(sessionId);
        String kind = String.valueOf(body.get("kind")).toUpperCase(java.util.Locale.ROOT);
        if (!VenueContent.KINDS.contains(kind)) throw VenueContent.bad("섹션 종류를 확인해 주세요.");
        if (repo.sections(sessionId).size() >= MAX_SECTIONS) {
            throw VenueContent.bad("섹션은 " + MAX_SECTIONS + "개까지 만들 수 있어요.");
        }
        String title = title(body.get("title"));
        long id = repo.insert(sessionId, kind, title, write(VenueContent.empty(kind)));
        changed(sessionId);
        return describe(require(sessionId, id));
    }

    @Transactional
    public Map<String, Object> update(long sessionId, long sectionId, Map<String, Object> body) {
        VenueRepository.Section section = require(sessionId, sectionId);
        Map<String, Object> content = VenueContent.clean(section.kind(), body.get("content"),
            imageId -> repo.image(imageId).filter(image -> image.sessionId() == sessionId).isPresent(),
            mapId -> repo.section(sessionId, mapId).filter(s -> VenueContent.MAP.equals(s.kind())).isPresent());
        Set<String> before = planIds(section);
        repo.update(section.id(), title(body.get("title")), write(content));
        VenueRepository.Section saved = require(sessionId, sectionId);
        // A plan taken off this map goes once no other map of the event shows it.
        before.removeAll(planIds(saved));
        before.forEach(imageId -> release(sessionId, imageId));
        changed(sessionId);
        return describe(saved);
    }

    public Map<String, Object> setVisible(long sessionId, long sectionId, boolean visible) {
        repo.setVisible(require(sessionId, sectionId).id(), visible);
        changed(sessionId);
        return describe(require(sessionId, sectionId));
    }

    /** Puts the sections in the order given, which must name each of them once. */
    @Transactional
    public Map<String, Object> reorder(long sessionId, List<?> ids) {
        List<VenueRepository.Section> sections = repo.sections(sessionId);
        Set<Long> current = new HashSet<>();
        sections.forEach(section -> current.add(section.id()));
        List<Long> order = new ArrayList<>();
        for (Object id : ids == null ? List.of() : ids) {
            if (id instanceof Number n) order.add(n.longValue());
        }
        if (order.size() != current.size() || !current.equals(new HashSet<>(order))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "섹션 목록이 바뀌었어요. 새로고침한 뒤 다시 옮겨 주세요.");
        }
        for (int i = 0; i < order.size(); i++) repo.setPosition(sessionId, order.get(i), i);
        changed(sessionId);
        return console(sessionId);
    }

    @Transactional
    public void delete(long sessionId, long sectionId) {
        VenueRepository.Section section = require(sessionId, sectionId);
        Set<String> plans = planIds(section);
        repo.delete(section.id());
        plans.forEach(imageId -> release(sessionId, imageId));
        changed(sessionId);
    }

    public Map<String, Object> upload(long sessionId, MultipartFile file) {
        tickets.requireSession(sessionId);
        byte[] bytes = drawing.draw(file, PLAN_EDGE);
        String id = UUID.randomUUID().toString();
        String key = id + ".jpg";
        storage.put(key, bytes, "image/jpeg");
        try {
            repo.insertImage(id, sessionId, key, storage.backend(), "image/jpeg", bytes.length);
        } catch (RuntimeException failure) {
            storage.delete(storage.backend(), key);
            throw failure;
        }
        return Map.of("imageId", id);
    }

    public Download consoleImage(long sessionId, String imageId) {
        return read(repo.image(imageId).filter(image -> image.sessionId() == sessionId)
            .orElseThrow(VenueService::noImage));
    }

    // ---- The holder ----

    /** What the venue tab shows: the visible sections, in the organiser's order. */
    public Map<String, Object> holder(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = viewer(resolved, session);
        EventSession event = tickets.requireSession(ticket.sessionId);
        List<VenueRepository.Section> shown = repo.sections(ticket.sessionId).stream()
            .filter(VenueRepository.Section::visible).toList();
        Set<Long> maps = new HashSet<>();
        shown.stream().filter(s -> VenueContent.MAP.equals(s.kind())).forEach(s -> maps.add(s.id()));
        List<Map<String, Object>> sections = new ArrayList<>();
        for (VenueRepository.Section section : shown) {
            Map<String, Object> content = read(section);
            // A list pointing at a map the holder cannot open is a list without a map.
            if (VenueContent.PLACES.equals(section.kind()) && content.get("mapId") instanceof Number mapId
                    && !maps.contains(mapId.longValue())) {
                content.put("mapId", null);
            }
            sections.add(VenueContent.mapOf("id", section.id(), "kind", section.kind(), "title", section.title(),
                "content", content));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("venue", event.venue);
        result.put("sections", sections);
        return result;
    }

    public Download holderImage(TicketService.Resolved resolved, HttpSession session, String imageId) {
        Ticket ticket = viewer(resolved, session);
        if (!repo.imageShown(ticket.sessionId, imageId)) throw noImage();
        return read(repo.image(imageId).filter(image -> image.sessionId() == ticket.sessionId)
            .orElseThrow(VenueService::noImage));
    }

    /** The holder on a phone that has proved it, as for the rest of the ticket's tabs. */
    private Ticket viewer(TicketService.Resolved resolved, HttpSession session) {
        Ticket ticket = resolved.ticket;
        if (resolved.viaTransfer() || !ticket.claimed() || ticket.revoked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "등록을 마친 입장권에서 볼 수 있어요.");
        }
        if (!passkeys.canView(session, ticket)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키로 본인 확인을 먼저 해 주세요.");
        }
        return ticket;
    }

    // ---- Floor plan files ----

    /** Every plan the event holds, read before the event goes: the rows go with it, the files would not. */
    public List<VenueRepository.Image> ofSession(long sessionId) {
        return repo.imagesOf(sessionId);
    }

    public void removeFiles(List<VenueRepository.Image> images) {
        for (VenueRepository.Image image : images) {
            try { storage.delete(image.backend(), image.key()); }
            catch (RuntimeException unavailable) {
                LoggerFactory.getLogger(VenueService.class).warn("Could not remove floor plan {} of a deleted event", image.id());
            }
        }
    }

    /** Plans uploaded and never put on a map - a form left half done - go after a day. */
    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    public void cleanup() {
        for (VenueRepository.Image image : repo.imagesBefore(Timestamp.from(Instant.now().minus(Duration.ofDays(1))))) {
            release(image.sessionId(), image.id());
        }
    }

    private void release(long sessionId, String imageId) {
        if (repo.imageInUse(sessionId, imageId)) return;
        repo.image(imageId).ifPresent(image -> {
            try { storage.delete(image.backend(), image.key()); }
            catch (RuntimeException unavailable) { return; } // The next sweep tries again.
            repo.deleteImage(image.id());
        });
    }

    private Download read(VenueRepository.Image image) {
        return new Download(storage.get(image.backend(), image.key()), image.type());
    }

    // ---- Shared ----

    private VenueRepository.Section require(long sessionId, long sectionId) {
        return repo.section(sessionId, sectionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "섹션을 찾을 수 없어요."));
    }

    private Map<String, Object> describe(VenueRepository.Section section) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", section.id());
        row.put("kind", section.kind());
        row.put("title", section.title());
        row.put("visible", section.visible());
        row.put("content", read(section));
        row.put("updatedAt", section.updatedAt().toInstant().toString());
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(VenueRepository.Section section) {
        return new LinkedHashMap<>(mapper.readValue(section.content(), Map.class));
    }

    private String write(Map<String, Object> content) {
        return mapper.writeValueAsString(content);
    }

    private Set<String> planIds(VenueRepository.Section section) {
        Set<String> ids = new HashSet<>();
        if (!VenueContent.MAP.equals(section.kind())) return ids;
        if (read(section).get("floors") instanceof List<?> floors) {
            for (Object floor : floors) {
                if (floor instanceof Map<?, ?> f && f.get("imageId") instanceof String id) ids.add(id);
            }
        }
        return ids;
    }

    private static String title(Object value) {
        String title = value == null ? "" : value.toString().strip();
        if (title.isEmpty()) throw VenueContent.bad("섹션 이름을 적어 주세요.");
        if (title.length() > 40) throw VenueContent.bad("섹션 이름은 40자까지 적을 수 있어요.");
        return title;
    }

    /** Tells every open ticket and console that the venue tab changed. */
    private void changed(long sessionId) {
        live.publish(sessionId, "VENUE", Map.of());
    }

    private static ResponseStatusException noImage() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "지도 이미지를 찾을 수 없어요.");
    }
}
