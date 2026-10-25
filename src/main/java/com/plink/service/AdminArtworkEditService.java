package com.plink.service;

import com.plink.repository.ContentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class AdminArtworkEditService {
    private final JdbcTemplate jdbc;
    private final ContentRepository contents;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, String> COLUMNS = Map.ofEntries(
        Map.entry("title", "title"), Map.entry("artist", "artist"), Map.entry("year", "year_text"),
        Map.entry("medium", "medium"), Map.entry("width", "width_text"), Map.entry("height", "height_text"),
        Map.entry("depth", "depth_text"), Map.entry("unit", "unit_text"),
        Map.entry("description", "description"), Map.entry("price", "price_text"),
        Map.entry("priceAmount", "price_amount"), Map.entry("priceCurrency", "price_currency"),
        Map.entry("priceMode", "price_mode"), Map.entry("priceNote", "price_note"),
        Map.entry("priceKrwRate", "price_krw_rate"), Map.entry("priceRateDate", "price_rate_date"));
    public AdminArtworkEditService(JdbcTemplate jdbc, ContentRepository contents) {
        this.jdbc = jdbc; this.contents = contents;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void update(long id, Map<String, Object> request) {
        Set<String> allowed = new HashSet<>(COLUMNS.keySet()); allowed.add("sourceRef");
        if (request.isEmpty() || !allowed.containsAll(request.keySet()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수정할 항목을 확인해 주세요.");
        Map<String, String> changes = new LinkedHashMap<>();
        request.forEach((key, value) -> {
            if (key.equals("sourceRef")) return;
            int limit = switch (key) {
                case "title" -> 500; case "artist", "price", "priceNote" -> 300; case "unit" -> 20;
                case "medium", "description" -> 10000; default -> 100;
            };
            if (!(value instanceof String text) || text.length() > limit
                    || (key.equals("title") && text.isBlank()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력한 내용의 길이와 작품명을 확인해 주세요.");
            changes.put(key, ((String) value).trim());
        });
        ArtworkPrice.Value price = null;
        if (request.containsKey("price") || ArtworkPrice.KEYS.stream().anyMatch(request::containsKey)) {
            price = ArtworkPrice.read(request);
            changes.putAll(price.fields());
            changes.put("price", price.display());
        }
        var sources = jdbc.queryForList("SELECT source_content_id FROM artwork WHERE id = ?", Long.class, id);
        if (sources.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long source = sources.get(0);
        // Match the source-first locking order used by content edits and reordering.
        jdbc.queryForList("SELECT id FROM link_content WHERE id = ? FOR UPDATE", Long.class, source);
        var positions = jdbc.queryForList("SELECT position FROM artwork WHERE id = ? FOR UPDATE", Long.class, id);
        if (positions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var content = contents.findById(source).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String sourceRef = content.sourceRef;
        if (request.containsKey("sourceRef")) {
            if (!"URL".equals(content.sourceType) || !(request.get("sourceRef") instanceof String))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "웹페이지 출처만 수정할 수 있어요.");
            sourceRef = ContentSource.webReference((String) request.get("sourceRef"));
        }
        Map<String, Object> body = mapper.readValue(content.body, Map.class);
        long position = positions.get(0);
        if (position >= 0) {
            if (!(body.get("artworks") instanceof List<?> rows) || position >= rows.size()
                    || !(rows.get((int) position) instanceof Map<?, ?>))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "원본 컨텐츠를 확인해 주세요.");
            ((Map<String, Object>) ((List<?>) body.get("artworks")).get((int) position)).putAll(changes);
        }
        Map<String, Object> stored = new LinkedHashMap<>(changes);
        if (price != null) {
            stored.put("priceAmount", price.amount()); stored.put("priceKrwRate", price.krwRate());
            stored.put("priceRateDate", price.rateDate());
        }
        List<Object> args = new ArrayList<>(stored.values()); args.add(id);
        String assignments = String.join(", ", changes.keySet().stream().map(key -> COLUMNS.get(key) + " = ?").toList());
        if (!changes.isEmpty()) jdbc.update("UPDATE artwork SET " + assignments + ", updated_at = CURRENT_TIMESTAMP WHERE id = ?", args.toArray());
        contents.update(source, content.title, mapper.writeValueAsString(body), content.sourceType, sourceRef);
    }
}
