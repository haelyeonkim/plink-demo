package com.plink.ticket.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The event's chat and its board, as rows. Rules about who may do what live in the service. */
@Repository
public class CommunityRepository {
    private final JdbcTemplate jdbc;

    public CommunityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** One chat line, with the line it answers when it is a reply. */
    public record Message(long id, long sessionId, Long ticketId, boolean staff, boolean notice, String body,
            Long replyTo, int likes, int dislikes, int reports, boolean hidden, Timestamp pinnedUntil, Timestamp createdAt,
            Long replyTicketId, boolean replyStaff, String replyBody, boolean replyHidden, String ticketRef,
            Timestamp alertUntil) {}

    private static final String MESSAGE_SELECT =
        "SELECT m.*, r.ticket_id AS r_ticket, r.staff AS r_staff, r.body AS r_body, r.hidden AS r_hidden, "
        + "t.ticket_ref AS t_ref FROM chat_message m "
        + "LEFT JOIN chat_message r ON r.id = m.reply_to "
        + "LEFT JOIN ticket t ON t.id = m.ticket_id ";

    private static final RowMapper<Message> MESSAGE = (rs, row) -> new Message(
        rs.getLong("id"), rs.getLong("session_id"), (Long) rs.getObject("ticket_id", Long.class),
        rs.getBoolean("staff"), rs.getBoolean("notice"), rs.getString("body"),
        (Long) rs.getObject("reply_to", Long.class), rs.getInt("like_count"), rs.getInt("dislike_count"),
        rs.getInt("report_count"),
        rs.getBoolean("hidden"), rs.getTimestamp("pinned_until"), rs.getTimestamp("created_at"),
        (Long) rs.getObject("r_ticket", Long.class), rs.getBoolean("r_staff"), rs.getString("r_body"),
        rs.getBoolean("r_hidden"), rs.getString("t_ref"), rs.getTimestamp("alert_until"));

    public long insertMessage(long sessionId, Long ticketId, boolean staff, boolean notice, String body,
            Long replyTo, Timestamp pinnedUntil, Timestamp alertUntil, Timestamp createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO chat_message (session_id, ticket_id, staff, notice, body, reply_to, pinned_until, "
                + "alert_until, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] { "id" });
            ps.setLong(1, sessionId);
            if (ticketId == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, ticketId);
            ps.setBoolean(3, staff);
            ps.setBoolean(4, notice);
            ps.setString(5, body);
            if (replyTo == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, replyTo);
            ps.setTimestamp(7, pinnedUntil);
            ps.setTimestamp(8, alertUntil);
            ps.setTimestamp(9, createdAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Message> findMessage(long id) {
        return jdbc.query(MESSAGE_SELECT + "WHERE m.id = ?", MESSAGE, id).stream().findFirst();
    }

    /** The newest lines after {@code afterId}, oldest first, at most {@code limit}. */
    public List<Message> recent(long sessionId, long afterId, int limit, boolean withHidden) {
        List<Message> rows = new ArrayList<>(jdbc.query(MESSAGE_SELECT
            + "WHERE m.session_id = ? AND m.id > ?" + (withHidden ? "" : " AND m.hidden = FALSE")
            + " ORDER BY m.id DESC LIMIT ?", MESSAGE, sessionId, afterId, limit));
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** The console's view: everything, or one kind of line, newest first. */
    public List<Message> feed(long sessionId, String filter, int limit, Timestamp now) {
        String where = switch (filter) {
            case "REPORTED" -> " AND m.report_count > 0";
            case "HIDDEN" -> " AND m.hidden = TRUE";
            case "PINNED" -> " AND m.pinned_until > ?";
            default -> "";
        };
        return "PINNED".equals(filter)
            ? jdbc.query(MESSAGE_SELECT + "WHERE m.session_id = ?" + where + " ORDER BY m.id DESC LIMIT ?",
                MESSAGE, sessionId, now, limit)
            : jdbc.query(MESSAGE_SELECT + "WHERE m.session_id = ?" + where + " ORDER BY m.id DESC LIMIT ?",
                MESSAGE, sessionId, limit);
    }

    /** What is held at the top now: the latest pinned notice, and the most liked popular line. */
    public Optional<Message> pinnedNotice(long sessionId, Timestamp now) {
        return jdbc.query(MESSAGE_SELECT + "WHERE m.session_id = ? AND m.notice = TRUE AND m.hidden = FALSE "
            + "AND m.pinned_until > ? ORDER BY m.id DESC LIMIT 1", MESSAGE, sessionId, now)
            .stream().findFirst();
    }

    /** The organiser notice raised on every holder's ticket now, the latest if more than one. */
    public Optional<Message> activeAlert(long sessionId, Timestamp now) {
        return jdbc.query(MESSAGE_SELECT + "WHERE m.session_id = ? AND m.notice = TRUE AND m.hidden = FALSE "
            + "AND m.alert_until > ? ORDER BY m.id DESC LIMIT 1", MESSAGE, sessionId, now).stream().findFirst();
    }

    public void setAlertUntil(long id, Timestamp until) {
        jdbc.update("UPDATE chat_message SET alert_until = ? WHERE id = ?", until, id);
    }

    public Optional<Message> pinnedHot(long sessionId, Timestamp now) {
        return jdbc.query(MESSAGE_SELECT + "WHERE m.session_id = ? AND m.notice = FALSE AND m.hidden = FALSE "
            + "AND m.pinned_until > ? ORDER BY m.like_count DESC, m.id DESC LIMIT 1",
            MESSAGE, sessionId, now).stream().findFirst();
    }

    public long latestId(long sessionId) {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM chat_message WHERE session_id = ? AND hidden = FALSE",
            Long.class, sessionId);
        return id == null ? 0 : id;
    }

    public Optional<Timestamp> lastWrittenBy(long ticketId) {
        return Optional.ofNullable(jdbc.queryForObject(
            "SELECT MAX(created_at) FROM chat_message WHERE ticket_id = ?", Timestamp.class, ticketId));
    }

    public Set<Long> likedBy(long ticketId, Collection<Long> messageIds) {
        return reactedBy("chat_like", ticketId, messageIds);
    }

    public Set<Long> dislikedBy(long ticketId, Collection<Long> messageIds) {
        return reactedBy("chat_dislike", ticketId, messageIds);
    }

    private Set<Long> reactedBy(String table, long ticketId, Collection<Long> messageIds) {
        Set<Long> found = new HashSet<>();
        if (messageIds.isEmpty()) return found;
        List<Object> args = new ArrayList<>();
        args.add(ticketId);
        args.addAll(messageIds);
        jdbc.query("SELECT message_id FROM " + table + " WHERE ticket_id = ? AND message_id IN ("
            + String.join(",", java.util.Collections.nCopies(messageIds.size(), "?")) + ")",
            rs -> { found.add(rs.getLong("message_id")); }, args.toArray());
        return found;
    }

    /** Adds or takes back one like, and says which it was. A like takes back a dislike. */
    public boolean toggleLike(long messageId, long ticketId) {
        return toggle("chat_like", "like_count", "chat_dislike", "dislike_count", messageId, ticketId);
    }

    /** Adds or takes back one dislike, and says which it was. A dislike takes back a like. */
    public boolean toggleDislike(long messageId, long ticketId) {
        return toggle("chat_dislike", "dislike_count", "chat_like", "like_count", messageId, ticketId);
    }

    private boolean toggle(String table, String count, String otherTable, String otherCount, long messageId, long ticketId) {
        int removed = jdbc.update("DELETE FROM " + table + " WHERE message_id = ? AND ticket_id = ?", messageId, ticketId);
        if (removed > 0) {
            jdbc.update("UPDATE chat_message SET " + count + " = " + count + " - 1 WHERE id = ? AND " + count + " > 0", messageId);
            return false;
        }
        if (jdbc.update("DELETE FROM " + otherTable + " WHERE message_id = ? AND ticket_id = ?", messageId, ticketId) > 0) {
            jdbc.update("UPDATE chat_message SET " + otherCount + " = " + otherCount + " - 1 WHERE id = ? AND "
                + otherCount + " > 0", messageId);
        }
        jdbc.update("INSERT INTO " + table + " (message_id, ticket_id) VALUES (?, ?)", messageId, ticketId);
        jdbc.update("UPDATE chat_message SET " + count + " = " + count + " + 1 WHERE id = ?", messageId);
        return true;
    }

    /** Counts one report per ticket; a second report from the same ticket changes nothing. */
    public boolean report(long messageId, long ticketId) {
        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM chat_report WHERE message_id = ? AND ticket_id = ?", Integer.class, messageId, ticketId);
        if (already != null && already > 0) return false;
        jdbc.update("INSERT INTO chat_report (message_id, ticket_id) VALUES (?, ?)", messageId, ticketId);
        jdbc.update("UPDATE chat_message SET report_count = report_count + 1 WHERE id = ?", messageId);
        return true;
    }

    public void setHidden(long messageId, boolean hidden) {
        jdbc.update("UPDATE chat_message SET hidden = ? WHERE id = ?", hidden, messageId);
    }

    public void setPinnedUntil(long messageId, Timestamp until) {
        jdbc.update("UPDATE chat_message SET pinned_until = ? WHERE id = ?", until, messageId);
    }

    /** Pins a popular line once: a line that has had its turn at the top does not come back. */
    public boolean pinIfNeverPinned(long messageId, Timestamp until) {
        return jdbc.update("UPDATE chat_message SET pinned_until = ? WHERE id = ? AND pinned_until IS NULL",
            until, messageId) > 0;
    }

    public boolean muted(long sessionId, long ticketId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_mute WHERE session_id = ? AND ticket_id = ?",
            Integer.class, sessionId, ticketId);
        return n != null && n > 0;
    }

    public Set<Long> mutedTickets(long sessionId) {
        return new HashSet<>(jdbc.queryForList("SELECT ticket_id FROM chat_mute WHERE session_id = ?",
            Long.class, sessionId));
    }

    public void setMuted(long sessionId, long ticketId, boolean muted) {
        jdbc.update("DELETE FROM chat_mute WHERE session_id = ? AND ticket_id = ?", sessionId, ticketId);
        if (muted) jdbc.update("INSERT INTO chat_mute (session_id, ticket_id) VALUES (?, ?)", sessionId, ticketId);
    }

    /** The chat's rules; an event that never set them gets the defaults. */
    public record Settings(boolean enabled, int cooldownSeconds, int maxLength, int hotLikes, int hotMinutes,
            int reportHide) {
        static final Settings DEFAULT = new Settings(true, 60, 200, 20, 10, 3);
    }

    public Settings settings(long sessionId) {
        return jdbc.query("SELECT * FROM chat_setting WHERE session_id = ?", (rs, row) -> new Settings(
                rs.getBoolean("enabled"), rs.getInt("cooldown_seconds"), rs.getInt("max_length"),
                rs.getInt("hot_likes"), rs.getInt("hot_minutes"), rs.getInt("report_hide")), sessionId)
            .stream().findFirst().orElse(Settings.DEFAULT);
    }

    public void saveSettings(long sessionId, Settings s) {
        jdbc.update("DELETE FROM chat_setting WHERE session_id = ?", sessionId);
        jdbc.update("INSERT INTO chat_setting (session_id, enabled, cooldown_seconds, max_length, hot_likes, "
            + "hot_minutes, report_hide) VALUES (?, ?, ?, ?, ?, ?, ?)", sessionId, s.enabled(), s.cooldownSeconds(),
            s.maxLength(), s.hotLikes(), s.hotMinutes(), s.reportHide());
    }

    // ---- The board ----

    public record Ad(long id, long sessionId, Long boothId, String booth, String category, String title,
            String body, String place, Timestamp startsAt, Timestamp endsAt, boolean pinned, boolean hidden,
            int views, Timestamp createdAt, Timestamp updatedAt, String imageId) {}

    private static final String AD_SELECT = "SELECT a.*, b.name AS booth_name FROM ad_post a "
        + "LEFT JOIN booth b ON b.id = a.booth_id ";

    private static final RowMapper<Ad> AD = (rs, row) -> new Ad(rs.getLong("id"), rs.getLong("session_id"),
        (Long) rs.getObject("booth_id", Long.class), rs.getString("booth_name"), rs.getString("category"),
        rs.getString("title"), rs.getString("body"), rs.getString("place"), rs.getTimestamp("starts_at"),
        rs.getTimestamp("ends_at"), rs.getBoolean("pinned"), rs.getBoolean("hidden"), rs.getInt("view_count"),
        rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"), rs.getString("image_id"));

    /** What a holder sees now: shown, started and not yet over; pinned first, newest next. */
    public List<Ad> visibleAds(long sessionId, Timestamp now) {
        return jdbc.query(AD_SELECT + "WHERE a.session_id = ? AND a.hidden = FALSE AND a.starts_at <= ? "
            + "AND (a.ends_at IS NULL OR a.ends_at > ?) ORDER BY a.pinned DESC, a.starts_at DESC",
            AD, sessionId, now, now);
    }

    public List<Ad> allAds(long sessionId) {
        return jdbc.query(AD_SELECT + "WHERE a.session_id = ? ORDER BY a.pinned DESC, a.starts_at DESC", AD, sessionId);
    }

    public List<Ad> boothAds(long sessionId, long boothId) {
        return jdbc.query(AD_SELECT + "WHERE a.session_id = ? AND a.booth_id = ? ORDER BY a.starts_at DESC",
            AD, sessionId, boothId);
    }

    public Optional<Ad> findAd(long id) {
        return jdbc.query(AD_SELECT + "WHERE a.id = ?", AD, id).stream().findFirst();
    }

    public long insertAd(long sessionId, Long boothId, String category, String title, String body, String place,
            Timestamp startsAt, Timestamp endsAt, boolean pinned, String imageId) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ad_post (session_id, booth_id, category, title, body, place, starts_at, ends_at, pinned, "
                + "created_at, updated_at, image_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] { "id" });
            ps.setLong(1, sessionId);
            if (boothId == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, boothId);
            ps.setString(3, category);
            ps.setString(4, title);
            ps.setString(5, body);
            ps.setString(6, place);
            ps.setTimestamp(7, startsAt);
            ps.setTimestamp(8, endsAt);
            ps.setBoolean(9, pinned);
            ps.setTimestamp(10, now);
            ps.setTimestamp(11, now);
            ps.setString(12, imageId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateAd(long id, String category, String title, String body, String place, Timestamp startsAt,
            Timestamp endsAt, boolean pinned, String imageId) {
        jdbc.update("UPDATE ad_post SET category = ?, title = ?, body = ?, place = ?, starts_at = ?, ends_at = ?, "
            + "pinned = ?, image_id = ?, updated_at = ? WHERE id = ?",
            category, title, body, place, startsAt, endsAt, pinned, imageId, Timestamp.from(java.time.Instant.now()), id);
    }

    public void setAdHidden(long id, boolean hidden) {
        jdbc.update("UPDATE ad_post SET hidden = ?, updated_at = ? WHERE id = ?",
            hidden, Timestamp.from(java.time.Instant.now()), id);
    }

    public void endAd(long id) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        jdbc.update("UPDATE ad_post SET ends_at = ?, updated_at = ? WHERE id = ?", now, now, id);
    }

    public void deleteAd(long id) {
        jdbc.update("DELETE FROM ad_post WHERE id = ?", id);
    }

    public void countView(long id) {
        jdbc.update("UPDATE ad_post SET view_count = view_count + 1 WHERE id = ?", id);
    }

    // ---- Photos on the board ----

    public record Image(String id, long sessionId, String key, String backend, String type) {}

    private static final RowMapper<Image> IMAGE = (rs, row) -> new Image(rs.getString("id"), rs.getLong("session_id"),
        rs.getString("storage_key"), rs.getString("storage_backend"), rs.getString("media_type"));

    public void insertImage(String id, long sessionId, String key, String backend, String type, long size) {
        jdbc.update("INSERT INTO ad_image (id, session_id, storage_key, storage_backend, media_type, byte_size, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, sessionId, key, backend, type, size, Timestamp.from(java.time.Instant.now()));
    }

    public Optional<Image> findImage(String id) {
        return jdbc.query("SELECT * FROM ad_image WHERE id = ?", IMAGE, id).stream().findFirst();
    }

    public boolean imageInUse(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ad_post WHERE image_id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /** Photos uploaded before {@code before} that no notice carries. */
    public List<Image> looseImages(Timestamp before) {
        return jdbc.query("SELECT * FROM ad_image i WHERE i.created_at < ? AND NOT EXISTS "
            + "(SELECT 1 FROM ad_post a WHERE a.image_id = i.id)", IMAGE, before);
    }

    public void deleteImage(String id) {
        jdbc.update("DELETE FROM ad_image WHERE id = ?", id);
    }
}
