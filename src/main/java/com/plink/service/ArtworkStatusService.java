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
        JsonNode body = mapper.valueToTree(ArtworkPrice.normalizeBody(mapper.readValue(content.body, Object.class), false));
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

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "작품을 찾을 수 없어요.");
    }
}
