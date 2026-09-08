package com.plink.repository;

import com.plink.model.ProtectedLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class LinkRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<ProtectedLink> ROW_MAPPER = (rs, rowNum) -> {
        ProtectedLink link = new ProtectedLink();
        link.setId(rs.getLong("id"));
        link.setShortCode(rs.getString("short_code"));
        link.setOriginalUrl(rs.getString("original_url"));
        link.setTitle(rs.getString("title"));
        link.setPasswordHash(rs.getString("password_hash"));
        link.setExpiresAt(rs.getTimestamp("expires_at"));
        link.setRecipientNames(rs.getString("recipient_names"));
        link.setMaxViews(rs.getInt("max_views"));
        link.setViewCount(rs.getInt("view_count"));
        link.setCreatedAt(rs.getTimestamp("created_at"));
        return link;
    };

    public LinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProtectedLink> findAll() {
        return jdbc.query("SELECT * FROM protected_link ORDER BY created_at DESC", ROW_MAPPER);
    }

    public Optional<ProtectedLink> findById(Long id) {
        List<ProtectedLink> results = jdbc.query(
                "SELECT * FROM protected_link WHERE id = ?", ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<ProtectedLink> findByShortCode(String shortCode) {
        List<ProtectedLink> results = jdbc.query(
                "SELECT * FROM protected_link WHERE short_code = ?", ROW_MAPPER, shortCode);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public ProtectedLink save(ProtectedLink link) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO protected_link (short_code, original_url, title, password_hash, expires_at, recipient_names, max_views) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[] { "id" });
            ps.setString(1, link.getShortCode());
            ps.setString(2, link.getOriginalUrl());
            ps.setString(3, link.getTitle());
            ps.setString(4, link.getPasswordHash());
            ps.setTimestamp(5, link.getExpiresAt());
            ps.setString(6, link.getRecipientNames());
            ps.setInt(7, link.getMaxViews());
            return ps;
        }, keyHolder);
        link.setId(keyHolder.getKey().longValue());
        return link;
    }

    public void incrementViewCount(Long id) {
        jdbc.update("UPDATE protected_link SET view_count = view_count + 1 WHERE id = ?", id);
    }

    public void deleteById(Long id) {
        jdbc.update("DELETE FROM protected_link WHERE id = ?", id);
    }
}
