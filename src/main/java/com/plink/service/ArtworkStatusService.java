package com.plink.service;

import com.plink.model.LinkContent;
import com.plink.repository.ContentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

@Service
public class ArtworkStatusService {
    public record Changed() {}
    public record Status(int position, Long artworkId, String saleStatus, boolean linked) {}
    private final JdbcTemplate jdbc;
    private final ContentRepository contents;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArtworkStatusService(JdbcTemplate jdbc, ContentRepository contents, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.contents = contents; this.events = events;
    }

    public List<Status> statuses(long contentId) {
        LinkContent content = contents.findById(contentId).orElseThrow(this::missing);
        var rows = mapper.readTree(content.body).path("artworks");
        Map<Integer, Status> linked = new HashMap<>();
        jdbc.query("SELECT r.position, a.id, a.sale_status FROM content_artwork r "
            + "LEFT JOIN artwork a ON a.id = r.artwork_id WHERE r.content_id = ?", rs -> {
                long id = rs.getLong("id");
                boolean found = !rs.wasNull();
                int position = rs.getInt("position");
                linked.put(position, new Status(position, found ? id : null, rs.getString("sale_status"), found));
            }, contentId);
        List<Status> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) result.add(linked.getOrDefault(i, new Status(i, null, null, false)));
        return result;
    }

    /** Overlay only identity/status; snapshot descriptions and prices remain unchanged. */
    public JsonNode body(LinkContent content) {
        var body = mapper.readTree(content.body);
        var rows = body.path("artworks");
        for (Status status : statuses(content.id)) {
            if (!(rows.path(status.position()) instanceof ObjectNode row)) continue;
            row.put("saleStatus", status.saleStatus() == null ? "" : status.saleStatus());
            row.put("saleStatusLinked", Boolean.toString(status.linked()));
            if (status.artworkId() != null) row.put("artworkId", status.artworkId().toString());
            else row.remove("artworkId");
        }
        return body;
    }

    @Transactional
    public void change(long artworkId, Object value) {
        if (value != null && !value.equals("hold") && !value.equals("sold")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "판매 상태를 확인해 주세요.");
        }
        if (jdbc.update("UPDATE artwork SET sale_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                value, artworkId) != 1) throw missing();
        events.publishEvent(new Changed());
    }

    public record PendingPage(List<Map<String, Object>> items, int total) {}

    /** No guessing: the administrator explicitly associates pre-identity snapshots. */
    public PendingPage pending(int page) {
        var snapshots = jdbc.queryForList("SELECT c.id FROM link_content c WHERE c.kind = 'SELECTION' "
            + "AND EXISTS (SELECT 1 FROM protected_link l WHERE l.content_id = c.id) ORDER BY c.id DESC", Long.class);
        List<Map<String, Object>> pending = new ArrayList<>();
        for (long id : snapshots) {
            var content = contents.findById(id).orElseThrow(this::missing);
            var rows = mapper.readTree(content.body).path("artworks");
            for (Status status : statuses(id)) {
                if (status.linked()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("contentId", id); row.put("position", status.position());
                row.put("contentTitle", content.title); row.put("ownerSub", content.ownerSub);
                row.put("title", rows.path(status.position()).path("title").asText(""));
                row.put("artist", rows.path(status.position()).path("artist").asText(""));
                pending.add(row);
            }
        }
        int start = (int) Math.min((long) page * 20, pending.size());
        return new PendingPage(pending.subList(start, Math.min(start + 20, pending.size())), pending.size());
    }

    @Transactional
    public void connect(long contentId, int position, long artworkId) {
        if (jdbc.queryForList("SELECT id FROM link_content WHERE id = ? FOR UPDATE", Long.class, contentId).isEmpty()) {
            throw missing();
        }
        var content = contents.findById(contentId).orElseThrow(this::missing);
        if (!"SELECTION".equals(content.kind) || position < 0
                || position >= mapper.readTree(content.body).path("artworks").size()) throw missing();
        int valid = jdbc.queryForObject("SELECT COUNT(*) FROM artwork WHERE id = ? AND owner_sub = ?",
            Integer.class, artworkId, content.ownerSub);
        if (valid != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동일 계정의 원본 작품을 선택해 주세요.");
        if (statuses(contentId).get(position).linked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 연결된 작품입니다. 목록을 새로고침해 주세요.");
        }
        jdbc.update("DELETE FROM content_artwork WHERE content_id = ? AND position = ?", contentId, position);
        jdbc.update("INSERT INTO content_artwork (content_id, position, artwork_id) VALUES (?, ?, ?)",
            contentId, position, artworkId);
        events.publishEvent(new Changed());
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "작품을 찾을 수 없어요.");
    }
}
