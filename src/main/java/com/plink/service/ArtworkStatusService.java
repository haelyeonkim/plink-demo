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
    public record Status(int position, Long artworkId, String saleStatus, boolean linked, Map<String, String> fields) {}
    private final com.plink.repository.ArtworkRepository artworks;
    private final JdbcTemplate jdbc;
    private final ContentRepository contents;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArtworkStatusService(JdbcTemplate jdbc, ContentRepository contents, ApplicationEventPublisher events, com.plink.repository.ArtworkRepository artworks) {
        this.artworks = artworks; this.jdbc = jdbc; this.contents = contents; this.events = events;
    }

    public List<Status> statuses(long contentId) {
        LinkContent content = contents.findById(contentId).orElseThrow(this::missing);
        var rows = mapper.readTree(content.body).path("artworks");
        Map<Integer, Status> linked = new HashMap<>();
        artworks.findLinked(contentId).forEach((position, artwork) -> {
            Map<String, String> fields = new LinkedHashMap<>(artwork.body());
            // Images retain their delivery-specific access grants and references.
            fields.remove("image");
            fields.put("price", artwork.pricing().display());
            linked.put(position, new Status(position, artwork.id(), artwork.saleStatus(), true, fields));
        });
        List<Status> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) result.add(linked.getOrDefault(i, new Status(i, null, null, false, Map.of())));
        return result;
    }

    /** Resolve current linked artwork details without rewriting stored delivery snapshots. */
    public JsonNode body(LinkContent content) {
        JsonNode body = mapper.valueToTree(ArtworkPrice.normalizeBody(mapper.readValue(content.body, Object.class), false));
        var rows = body.path("artworks");
        for (Status status : statuses(content.id)) {
            if (!(rows.path(status.position()) instanceof ObjectNode row)) continue;
            status.fields().forEach(row::put);
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
