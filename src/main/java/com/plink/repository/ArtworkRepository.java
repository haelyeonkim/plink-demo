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
    public record Artwork(long id, long sourceContentId, int position, String image, String artist,
            String title, String year, String medium, String width, String height, String depth,
            String unit, String description, String price) {
        public Map<String, String> body() {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("image", text(image)); value.put("artist", text(artist));
            value.put("title", text(title)); value.put("year", text(year));
            value.put("medium", text(medium)); value.put("width", text(width));
            value.put("height", text(height)); value.put("depth", text(depth));
            value.put("unit", unit == null || unit.isBlank() ? "cm" : unit);
            value.put("description", text(description)); value.put("price", text(price));
            return value;
        }
        private static String text(String value) { return value == null ? "" : value; }
    }

    private static final RowMapper<Artwork> MAPPER = (rs, row) -> new Artwork(
        rs.getLong("id"), rs.getLong("source_content_id"), rs.getInt("position"),
        rs.getString("image_url"), rs.getString("artist"), rs.getString("title"),
        rs.getString("year_text"), rs.getString("medium"), rs.getString("width_text"),
        rs.getString("height_text"), rs.getString("depth_text"), rs.getString("unit_text"),
        rs.getString("description"), rs.getString("price_text"));

    private final JdbcTemplate jdbc;

    public ArtworkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Artwork> findByOwner(String ownerSub) {
        return jdbc.query("SELECT * FROM artwork WHERE owner_sub = ? "
            + "ORDER BY updated_at DESC, source_content_id DESC, position", MAPPER, ownerSub);
    }

    public int countByContent(long contentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM artwork WHERE source_content_id = ?", Integer.class, contentId);
        return count == null ? 0 : count;
    }

    public List<Artwork> findOwnedByIds(String ownerSub, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> values = new ArrayList<>();
        values.add(ownerSub); values.addAll(ids);
        List<Artwork> found = jdbc.query("SELECT * FROM artwork WHERE owner_sub = ? AND id IN ("
            + marks + ")", MAPPER, values.toArray());
        Map<Long, Artwork> byId = new LinkedHashMap<>();
        for (Artwork artwork : found) byId.put(artwork.id(), artwork);
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    public void replace(long contentId, String ownerSub, List<Map<String, String>> rows) {
        jdbc.update("DELETE FROM artwork WHERE source_content_id = ?", contentId);
        for (int index = 0; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            String title = value(row, "title");
            if (title.isBlank()) title = "제목 없는 작품 " + (index + 1);
            jdbc.update("INSERT INTO artwork (owner_sub, source_content_id, position, image_url, "
                    + "artist, title, year_text, medium, width_text, height_text, depth_text, unit_text, "
                    + "description, price_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ownerSub, contentId, index, value(row, "image"), value(row, "artist"), title,
                value(row, "year"), value(row, "medium"), value(row, "width"), value(row, "height"),
                value(row, "depth"), value(row, "unit"), value(row, "description"), value(row, "price"));
        }
    }

    private static String value(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null ? "" : value.trim();
    }
}
