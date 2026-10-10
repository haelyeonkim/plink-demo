package com.plink.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ArtworkRepository {
    public record Artwork(long id, long sourceContentId, long position, String image, String artist,
            String title, String year, String medium, String width, String height, String depth,
            String unit, String description, String price, String saleStatus) {
        public Map<String, String> body() {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("image", text(image)); value.put("artist", text(artist));
            value.put("title", text(title)); value.put("year", text(year));
            value.put("medium", text(medium)); value.put("width", text(width));
            value.put("height", text(height)); value.put("depth", text(depth));
            value.put("unit", unit == null || unit.isBlank() ? "cm" : unit);
            value.put("description", text(description)); value.put("price", text(price));
            if (saleStatus != null) value.put("saleStatus", saleStatus);
            return value;
        }
        private static String text(String value) { return value == null ? "" : value; }
    }

    private static final RowMapper<Artwork> MAPPER = (rs, row) -> new Artwork(
        rs.getLong("id"), rs.getLong("source_content_id"), rs.getLong("position"),
        rs.getString("image_url"), rs.getString("artist"), rs.getString("title"),
        rs.getString("year_text"), rs.getString("medium"), rs.getString("width_text"),
        rs.getString("height_text"), rs.getString("depth_text"), rs.getString("unit_text"),
        rs.getString("description"), rs.getString("price_text"), rs.getString("sale_status"));

    private final JdbcTemplate jdbc;

    public ArtworkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Artwork> findByOwner(String ownerSub) {
        return jdbc.query("SELECT * FROM artwork WHERE owner_sub = ? AND archived = FALSE "
            + "ORDER BY updated_at DESC, source_content_id DESC, position", MAPPER, ownerSub);
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int pageSize) {}

    public Page findAll(String search, String status, int page, int pageSize) {
        return findAll(search, status, page, pageSize, null);
    }

    public Page findAll(String search, String status, int page, int pageSize, String ownerSub) {
        String from = " FROM artwork a JOIN link_content c ON c.id = a.source_content_id "
            + "LEFT JOIN admin_account u ON a.owner_sub = CONCAT('local:', u.id) "
            + "WHERE c.kind <> 'SELECTION'";
        List<Object> args = new ArrayList<>();
        if (ownerSub != null) { from += " AND a.owner_sub = ?"; args.add(ownerSub); }
        if (!search.isBlank()) {
            from += " AND (LOWER(a.title) LIKE ? ESCAPE '!' OR LOWER(a.artist) LIKE ? ESCAPE '!' "
                + "OR LOWER(c.title) LIKE ? ESCAPE '!' OR LOWER(u.email) LIKE ? ESCAPE '!' "
                + "OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(a.owner_sub) LIKE ? ESCAPE '!')";
            String pattern = "%" + search.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            for (int i = 0; i < 6; i++) args.add(pattern);
        }
        if (status.equals("unsold")) from += " AND a.sale_status IS NULL";
        else if (!status.equals("all")) { from += " AND a.sale_status = ?"; args.add(status); }
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        args.add(pageSize); args.add((long) page * pageSize);
        List<Map<String, Object>> items = jdbc.query("SELECT a.*, c.title AS content_title, "
            + "c.source_type, c.updated_at AS content_updated_at, u.email AS owner_email, "
            + "u.display_name AS owner_name" + from
            + " ORDER BY c.updated_at DESC, a.source_content_id DESC, a.position, a.id LIMIT ? OFFSET ?",
            (rs, index) -> {
                Artwork artwork = MAPPER.mapRow(rs, index);
                Map<String, Object> row = new LinkedHashMap<>(artwork.body());
                row.put("id", artwork.id()); row.put("sourceContentId", artwork.sourceContentId());
                row.put("saleStatus", artwork.saleStatus());
                row.put("archived", rs.getBoolean("archived"));
                row.put("ownerSub", rs.getString("owner_sub"));
                row.put("ownerEmail", rs.getString("owner_email"));
                row.put("ownerName", rs.getString("owner_name"));
                row.put("contentTitle", rs.getString("content_title"));
                row.put("sourceType", rs.getString("source_type"));
                var updated = rs.getTimestamp("content_updated_at");
                row.put("updatedAt", updated == null ? null : updated.toInstant().toString());
                return row;
            }, args.toArray());
        return new Page(items, total, page, pageSize);
    }

    public int countByContent(long contentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM artwork WHERE source_content_id = ?", Integer.class, contentId);
        return count == null ? 0 : count;
    }

    public void linkSnapshot(long contentId, List<Artwork> rows) {
        for (int index = 0; index < rows.size(); index++) {
            jdbc.update("INSERT INTO content_artwork (content_id, position, artwork_id) VALUES (?, ?, ?)",
                contentId, index, rows.get(index).id());
        }
    }

    public List<Artwork> findOwnedByIds(String ownerSub, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> values = new ArrayList<>();
        values.add(ownerSub); values.addAll(ids);
        List<Artwork> found = jdbc.query("SELECT * FROM artwork WHERE owner_sub = ? AND archived = FALSE AND id IN ("
            + marks + ")", MAPPER, values.toArray());
        Map<Long, Artwork> byId = new LinkedHashMap<>();
        for (Artwork artwork : found) byId.put(artwork.id(), artwork);
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /** The caller's transaction locks the source before replacing its active rows. */
    @org.springframework.transaction.annotation.Transactional
    public void replace(long contentId, String ownerSub, List<Map<String, String>> rows) {
        jdbc.queryForObject("SELECT id FROM link_content WHERE id = ? FOR UPDATE", Long.class, contentId);
        var existing = jdbc.query("SELECT * FROM artwork WHERE source_content_id = ? AND archived = FALSE",
            MAPPER, contentId).stream().collect(java.util.stream.Collectors.toMap(Artwork::id, row -> row));
        java.util.Set<Long> used = new java.util.HashSet<>();
        // Vacate the unique positions before swaps/reordering, preserving all IDs.
        jdbc.update("UPDATE artwork SET archived = TRUE, position = -id WHERE source_content_id = ?", contentId);
        jdbc.update("DELETE FROM content_artwork WHERE content_id = ?", contentId);
        for (int index = 0; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            String title = value(row, "title");
            if (title.isBlank()) title = "제목 없는 작품 " + (index + 1);
            String requestedId = value(row, "artworkId");
            long id;
            if (!requestedId.isBlank()) {
                try { id = Long.parseLong(requestedId); }
                catch (NumberFormatException invalid) { throw invalidIdentity(); }
                if (!existing.containsKey(id) || !used.add(id)) throw invalidIdentity();
                // Status is managed separately: saving a stale editor must never undo a sale.
                jdbc.update("UPDATE artwork SET position = ?, image_url = ?, artist = ?, title = ?, "
                    + "year_text = ?, medium = ?, width_text = ?, height_text = ?, depth_text = ?, "
                    + "unit_text = ?, description = ?, price_text = ?, archived = FALSE, "
                    + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND owner_sub = ?",
                    index, value(row, "image"), value(row, "artist"), title, value(row, "year"),
                    value(row, "medium"), value(row, "width"), value(row, "height"), value(row, "depth"),
                    value(row, "unit"), value(row, "description"), value(row, "price"), id, ownerSub);
            } else {
                jdbc.update("INSERT INTO artwork (owner_sub, source_content_id, position, image_url, "
                    + "artist, title, year_text, medium, width_text, height_text, depth_text, unit_text, "
                    + "description, price_text, sale_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ownerSub, contentId, index, value(row, "image"), value(row, "artist"), title,
                    value(row, "year"), value(row, "medium"), value(row, "width"), value(row, "height"),
                    value(row, "depth"), value(row, "unit"), value(row, "description"), value(row, "price"),
                    value(row, "saleStatus").isBlank() ? null : value(row, "saleStatus"));
                id = jdbc.queryForObject("SELECT id FROM artwork WHERE source_content_id = ? AND position = ?",
                    Long.class, contentId, index);
            }
            jdbc.update("INSERT INTO content_artwork (content_id, position, artwork_id) VALUES (?, ?, ?)",
                contentId, index, id);
        }
    }

    private org.springframework.web.server.ResponseStatusException invalidIdentity() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
            "작품 식별자가 올바르지 않습니다. 컨텐츠를 다시 열어 주세요.");
    }

    private static String value(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }
}
