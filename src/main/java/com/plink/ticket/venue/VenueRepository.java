package com.plink.ticket.venue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The venue sections of an event, and the floor plans its maps show. */
@Repository
public class VenueRepository {
    private final JdbcTemplate jdbc;

    public VenueRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One section; {@code content} is its JSON as stored. */
    public record Section(long id, long sessionId, String kind, String title, int position, boolean visible,
            String content, Timestamp updatedAt) {}

    private static final RowMapper<Section> SECTION = (rs, row) -> new Section(rs.getLong("id"),
        rs.getLong("session_id"), rs.getString("kind"), rs.getString("title"), rs.getInt("position"),
        rs.getBoolean("visible"), rs.getString("content"), rs.getTimestamp("updated_at"));

    public List<Section> sections(long sessionId) {
        return jdbc.query("SELECT * FROM venue_section WHERE session_id = ? ORDER BY position, id", SECTION, sessionId);
    }

    public Optional<Section> section(long sessionId, long id) {
        return jdbc.query("SELECT * FROM venue_section WHERE session_id = ? AND id = ?", SECTION, sessionId, id)
            .stream().findFirst();
    }

    public long insert(long sessionId, String kind, String title, String content) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // A new section goes to the end of the list.
            PreparedStatement ps = connection.prepareStatement("INSERT INTO venue_section "
                + "(session_id, kind, title, position, visible, content, created_at, updated_at) VALUES (?, ?, ?, "
                + "(SELECT COALESCE(MAX(position), -1) + 1 FROM venue_section WHERE session_id = ?), FALSE, ?, ?, ?)",
                new String[] { "id" });
            Timestamp now = Timestamp.from(Instant.now());
            ps.setLong(1, sessionId);
            ps.setString(2, kind);
            ps.setString(3, title);
            ps.setLong(4, sessionId);
            ps.setString(5, content);
            ps.setTimestamp(6, now);
            ps.setTimestamp(7, now);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String title, String content) {
        jdbc.update("UPDATE venue_section SET title = ?, content = ?, updated_at = ? WHERE id = ?",
            title, content, Timestamp.from(Instant.now()), id);
    }

    public void setVisible(long id, boolean visible) {
        jdbc.update("UPDATE venue_section SET visible = ?, updated_at = ? WHERE id = ?",
            visible, Timestamp.from(Instant.now()), id);
    }

    public void setPosition(long sessionId, long id, int position) {
        jdbc.update("UPDATE venue_section SET position = ? WHERE session_id = ? AND id = ?", position, sessionId, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM venue_section WHERE id = ?", id);
    }

    // ---- Floor plans ----

    public record Image(String id, long sessionId, String key, String backend, String type, Timestamp createdAt) {}

    private static final RowMapper<Image> IMAGE = (rs, row) -> new Image(rs.getString("id"), rs.getLong("session_id"),
        rs.getString("storage_key"), rs.getString("storage_backend"), rs.getString("media_type"),
        rs.getTimestamp("created_at"));

    public void insertImage(String id, long sessionId, String key, String backend, String type, long size) {
        jdbc.update("INSERT INTO venue_image (id, session_id, storage_key, storage_backend, media_type, byte_size, "
            + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", id, sessionId, key, backend, type, size,
            Timestamp.from(Instant.now()));
    }

    public Optional<Image> image(String id) {
        return jdbc.query("SELECT * FROM venue_image WHERE id = ?", IMAGE, id).stream().findFirst();
    }

    public List<Image> imagesOf(long sessionId) {
        return jdbc.query("SELECT * FROM venue_image WHERE session_id = ?", IMAGE, sessionId);
    }

    public List<Image> imagesBefore(Timestamp before) {
        return jdbc.query("SELECT * FROM venue_image WHERE created_at < ?", IMAGE, before);
    }

    public void deleteImage(String id) {
        jdbc.update("DELETE FROM venue_image WHERE id = ?", id);
    }

    /** Whether any map or image section of the event names this image. Ids are UUIDs, so a match is the image. */
    public boolean imageInUse(long sessionId, String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM venue_section WHERE session_id = ? AND kind IN ('MAP', 'IMAGE') "
            + "AND content LIKE ?", Integer.class, sessionId, "%\"" + id + "\"%");
        return n != null && n > 0;
    }

    /** Whether a visible map or image section of the event names this image: what a holder may be sent. */
    public boolean imageShown(long sessionId, String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM venue_section WHERE session_id = ? AND kind IN ('MAP', 'IMAGE') "
            + "AND visible = TRUE AND content LIKE ?", Integer.class, sessionId, "%\"" + id + "\"%");
        return n != null && n > 0;
    }
}
