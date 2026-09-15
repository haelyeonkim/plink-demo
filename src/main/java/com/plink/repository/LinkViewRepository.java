package com.plink.repository;

import com.plink.model.LinkView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LinkViewRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<LinkView> ROW_MAPPER = (rs, rowNum) -> {
        LinkView view = new LinkView();
        view.setId(rs.getLong("id"));
        view.setLinkId(rs.getLong("link_id"));
        view.setViewerName(rs.getString("viewer_name"));
        view.setViewedAt(rs.getTimestamp("viewed_at"));
        view.setEventType(rs.getString("event_type"));
        return view;
    };

    public LinkViewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LinkView> findByLinkId(Long linkId) {
        return jdbc.query(
                "SELECT * FROM link_view WHERE link_id = ? ORDER BY viewed_at DESC",
                ROW_MAPPER, linkId);
    }

    public void save(Long linkId, String viewerName) {
        save(linkId, viewerName, "PASSKEY_AUTHENTICATED");
    }

    public void save(Long linkId, String viewerName, String eventType) {
        jdbc.update("INSERT INTO link_view (link_id, viewer_name, event_type) VALUES (?, ?, ?)",
                linkId, viewerName, eventType);
    }

    public void saveInitialOpen(Long linkId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM link_view WHERE link_id = ? AND event_type = 'INITIAL_OPEN'", Integer.class, linkId) == 0) {
            save(linkId, null, "INITIAL_OPEN");
        }
    }
}
