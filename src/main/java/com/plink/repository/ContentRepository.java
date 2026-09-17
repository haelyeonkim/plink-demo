package com.plink.repository;

import com.plink.model.LinkContent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class ContentRepository {
    private final JdbcTemplate jdbc;

    public ContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LinkContent> MAPPER = (rs, row) -> {
        LinkContent content = new LinkContent();
        content.id = rs.getLong("id");
        content.ownerSub = rs.getString("owner_sub");
        content.title = rs.getString("title");
        content.kind = rs.getString("kind");
        content.body = rs.getString("body");
        content.sourceType = rs.getString("source_type");
        content.sourceRef = rs.getString("source_ref");
        content.createdAt = rs.getTimestamp("created_at");
        content.updatedAt = rs.getTimestamp("updated_at");
        content.sourceImportedAt = rs.getTimestamp("source_imported_at");
        return content;
    };

    public List<LinkContent> findByOwner(String ownerSub) {
        return jdbc.query("SELECT * FROM link_content WHERE owner_sub = ? AND kind <> 'SELECTION' "
            + "ORDER BY updated_at DESC",
            MAPPER, ownerSub);
    }

    public Optional<LinkContent> findById(long id) {
        return jdbc.query("SELECT * FROM link_content WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long insert(String ownerSub, String title, String kind, String body) {
        return insert(ownerSub, title, kind, body, "MANUAL", null);
    }

    public long insert(String ownerSub, String title, String kind, String body,
            String sourceType, String sourceRef) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO link_content (owner_sub, title, kind, body, source_type, source_ref, "
                    + "source_imported_at) VALUES (?, ?, ?, ?, ?, ?, "
                    + "CASE WHEN ? IN ('URL', 'PDF') THEN CURRENT_TIMESTAMP ELSE NULL END)",
                new String[] { "id" });
            ps.setString(1, ownerSub);
            ps.setString(2, title);
            ps.setString(3, kind);
            ps.setString(4, body);
            ps.setString(5, sourceType);
            ps.setString(6, sourceRef);
            ps.setString(7, sourceType);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String title, String body) {
        jdbc.update("UPDATE link_content SET title = ?, body = ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ?", title, body, id);
    }

    public void update(long id, String title, String body, String sourceType, String sourceRef) {
        jdbc.update("UPDATE link_content SET title = ?, body = ?, source_type = ?, source_ref = ?, "
                + "source_imported_at = CASE WHEN ? IN ('URL', 'PDF') THEN "
                + "COALESCE(source_imported_at, CURRENT_TIMESTAMP) ELSE NULL END, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            title, body, sourceType, sourceRef, sourceType, id);
    }

    /** Refused while a link still points at it: the recipients would find nothing. */
    public int linksUsing(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM protected_link WHERE content_id = ?",
            Integer.class, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM link_content WHERE id = ?", id);
    }
}
