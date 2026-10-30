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
            Timestamp alertUntil, boolean banned, boolean replyBanned) {}

    /** Whether the writer of line {@code alias} is banned from that line's event. */
    private static String bannedWriter(String alias) {
        return "EXISTS (SELECT 1 FROM chat_ban b WHERE b.session_id = " + alias + ".session_id AND b.ticket_id = "
            + alias + ".ticket_id)";
    }

    /** What holders may see of a line beyond its own flag: nothing from a banned ticket. */
    private static final String SHOWN = " AND m.hidden = FALSE AND NOT " + bannedWriter("m");

    private static final String MESSAGE_SELECT =
        "SELECT m.*, r.ticket_id AS r_ticket, r.staff AS r_staff, r.body AS r_body, r.hidden AS r_hidden, "
        + "t.ticket_ref AS t_ref, " + bannedWriter("m") + " AS m_banned, " + bannedWriter("r") + " AS r_banned "
        + "FROM chat_message m "
        + "LEFT JOIN chat_message r ON r.id = m.reply_to "
        + "LEFT JOIN ticket t ON t.id = m.ticket_id ";

    private static final RowMapper<Message> MESSAGE = (rs, row) -> new Message(
        rs.getLong("id"), rs.getLong("session_id"), (Long) rs.getObject("ticket_id", Long.class),
        rs.getBoolean("staff"), rs.getBoolean("notice"), rs.getString("body"),
        (Long) rs.getObject("reply_to", Long.class), rs.getInt("like_count"), rs.getInt("dislike_count"),
        rs.getInt("report_count"),
        rs.getBoolean("hidden"), rs.getTimestamp("pinned_until"), rs.getTimestamp("created_at"),
        (Long) rs.getObject("r_ticket", Long.class), rs.getBoolean("r_staff"), rs.getString("r_body"),
        rs.getBoolean("r_hidden"), rs.getString("t_ref"), rs.getTimestamp("alert_until"),
        rs.getBoolean("m_banned"), rs.getBoolean("r_banned"));

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

    /**
     * Lines oldest first, at most {@code limit}: from the start the newest ones, and after
     * {@code afterId} the next ones - taking the newest there would skip whatever lies
     * between {@code afterId} and them.
     */
    public List<Message> recent(long sessionId, long afterId, int limit, boolean withHidden) {
        String where = "WHERE m.session_id = ? AND m.id > ?" + (withHidden ? "" : SHOWN);
        if (afterId > 0) {
            return jdbc.query(MESSAGE_SELECT + where + " ORDER BY m.id ASC LIMIT ?", MESSAGE, sessionId, afterId, limit);
        }
        List<Message> rows = new ArrayList<>(jdbc.query(MESSAGE_SELECT + where + " ORDER BY m.id DESC LIMIT ?",
            MESSAGE, sessionId, afterId, limit));
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

    /** Lines with a report that are still showing: what waits for the organiser to look at. */
    public int countReported(long sessionId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id = ? AND report_count > 0 "
            + "AND hidden = FALSE", Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    public int countHidden(long sessionId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id = ? AND hidden = TRUE",
            Integer.class, sessionId);
        return n == null ? 0 : n;
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
        return jdbc.query(MESSAGE_SELECT + "WHERE m.session_id = ? AND m.notice = FALSE "
            + "AND m.pinned_until > ?" + SHOWN + " ORDER BY m.like_count DESC, m.id DESC LIMIT 1",
            MESSAGE, sessionId, now).stream().findFirst();
    }

    public long latestId(long sessionId) {
        Long id = jdbc.queryForObject("SELECT MAX(m.id) FROM chat_message m WHERE m.session_id = ?" + SHOWN,
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

    /**
     * Each reaction first holds the line's row, so taps on one line take turns: a double
     * tap is two toggles rather than a duplicate key, and a like racing a dislike from the
     * same ticket cannot leave both. Counters move only with a row actually added or
     * removed. Callers run this inside a transaction, which is what keeps the hold.
     */
    private boolean toggle(String table, String count, String otherTable, String otherCount, long messageId, long ticketId) {
        lockMessage(messageId);
        int removed = jdbc.update("DELETE FROM " + table + " WHERE message_id = ? AND ticket_id = ?", messageId, ticketId);
        if (removed > 0) {
            jdbc.update("UPDATE chat_message SET " + count + " = " + count + " - 1 WHERE id = ? AND " + count + " > 0", messageId);
            return false;
        }
        if (jdbc.update("DELETE FROM " + otherTable + " WHERE message_id = ? AND ticket_id = ?", messageId, ticketId) > 0) {
            jdbc.update("UPDATE chat_message SET " + otherCount + " = " + otherCount + " - 1 WHERE id = ? AND "
                + otherCount + " > 0", messageId);
        }
        if (insertOnce(table, messageId, ticketId)) {
            jdbc.update("UPDATE chat_message SET " + count + " = " + count + " + 1 WHERE id = ?", messageId);
        }
        return true;
    }

    private void lockMessage(long messageId) {
        jdbc.query("SELECT id FROM chat_message WHERE id = ? FOR UPDATE", rs -> { }, messageId);
    }

    /**
     * Adds the pair unless it is there. Written so a row already present is no error: in
     * Postgres a failed insert would spoil the whole transaction, not just this statement.
     */
    private boolean insertOnce(String table, long messageId, long ticketId) {
        return jdbc.update("INSERT INTO " + table + " (message_id, ticket_id) SELECT CAST(? AS BIGINT), CAST(? AS BIGINT) "
            + "WHERE NOT EXISTS (SELECT 1 FROM " + table + " WHERE message_id = ? AND ticket_id = ?)",
            messageId, ticketId, messageId, ticketId) > 0;
    }

    /** Counts one report per ticket; a second report from the same ticket changes nothing. */
    public boolean report(long messageId, long ticketId) {
        lockMessage(messageId);
        if (!insertOnce("chat_report", messageId, ticketId)) return false;
        jdbc.update("UPDATE chat_message SET report_count = report_count + 1 WHERE id = ?", messageId);
        return true;
    }

    /**
     * Forgets a line's reports: the organiser has looked at it and put it back, so it
     * takes as many fresh reports to hide again as it did the first time.
     */
    public void clearReports(long messageId) {
        jdbc.update("DELETE FROM chat_report WHERE message_id = ?", messageId);
        jdbc.update("UPDATE chat_message SET report_count = 0 WHERE id = ?", messageId);
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

    public boolean banned(long sessionId, long ticketId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_ban WHERE session_id = ? AND ticket_id = ?",
            Integer.class, sessionId, ticketId);
        return n != null && n > 0;
    }

    /** Bans or lifts the ban, and says whether anything changed. */
    public boolean setBanned(long sessionId, long ticketId, boolean banned) {
        if (!banned) return jdbc.update("DELETE FROM chat_ban WHERE session_id = ? AND ticket_id = ?", sessionId, ticketId) > 0;
        return jdbc.update("INSERT INTO chat_ban (session_id, ticket_id) SELECT CAST(? AS BIGINT), CAST(? AS BIGINT) "
            + "WHERE NOT EXISTS (SELECT 1 FROM chat_ban WHERE session_id = ? AND ticket_id = ?)",
            sessionId, ticketId, sessionId, ticketId) > 0;
    }

    /** A ticket as the console's list of chat people sees it. The emails are for searching only. */
    public record Person(long ticketId, String ticketRef, String issuedTo, String holder, int messages,
            Timestamp lastAt, boolean muted, boolean banned) {}

    /**
     * Everyone who has written in the event's chat, or has been muted or banned before
     * writing, latest writer first. All of them: the name holders see is made in Java, so
     * searching by it cannot happen in the query, and one event's writers are few enough.
     */
    public List<Person> people(long sessionId) {
        return jdbc.query("SELECT t.id, t.ticket_ref, t.issued_to_email, t.holder_email, c.n, c.last_at, "
            + "EXISTS (SELECT 1 FROM chat_mute u WHERE u.session_id = t.session_id AND u.ticket_id = t.id) AS muted, "
            + "EXISTS (SELECT 1 FROM chat_ban b WHERE b.session_id = t.session_id AND b.ticket_id = t.id) AS banned "
            + "FROM ticket t LEFT JOIN (SELECT ticket_id, COUNT(*) AS n, MAX(created_at) AS last_at FROM chat_message "
            + "WHERE session_id = ? AND ticket_id IS NOT NULL GROUP BY ticket_id) c ON c.ticket_id = t.id "
            + "WHERE t.session_id = ? AND (c.ticket_id IS NOT NULL "
            + "OR EXISTS (SELECT 1 FROM chat_mute u WHERE u.session_id = t.session_id AND u.ticket_id = t.id) "
            + "OR EXISTS (SELECT 1 FROM chat_ban b WHERE b.session_id = t.session_id AND b.ticket_id = t.id)) "
            + "ORDER BY c.last_at DESC NULLS LAST, t.id DESC",
            (rs, row) -> new Person(rs.getLong("id"), rs.getString("ticket_ref"), rs.getString("issued_to_email"),
                rs.getString("holder_email"), rs.getInt("n"), rs.getTimestamp("last_at"), rs.getBoolean("muted"),
                rs.getBoolean("banned")),
            sessionId, sessionId);
    }

    public Set<Long> bannedTickets(long sessionId) {
        return new HashSet<>(jdbc.queryForList("SELECT ticket_id FROM chat_ban WHERE session_id = ?",
            Long.class, sessionId));
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

    /**
     * Updates the event's row, or makes it the first time. Never a delete first: between
     * the two a phone would read the defaults - an open chat - for a chat just closed.
     */
    public void saveSettings(long sessionId, Settings s) {
        int updated = jdbc.update("UPDATE chat_setting SET enabled = ?, cooldown_seconds = ?, max_length = ?, "
            + "hot_likes = ?, hot_minutes = ?, report_hide = ? WHERE session_id = ?", s.enabled(), s.cooldownSeconds(),
            s.maxLength(), s.hotLikes(), s.hotMinutes(), s.reportHide(), sessionId);
        if (updated > 0) return;
        jdbc.update("INSERT INTO chat_setting (session_id, enabled, cooldown_seconds, max_length, hot_likes, "
            + "hot_minutes, report_hide) VALUES (?, ?, ?, ?, ?, ?, ?)", sessionId, s.enabled(), s.cooldownSeconds(),
            s.maxLength(), s.hotLikes(), s.hotMinutes(), s.reportHide());
    }

    // ---- The board ----

    public record Ad(long id, long sessionId, Long boothId, String booth, String category, String title,
            String body, String place, Timestamp startsAt, Timestamp endsAt, boolean pinned, boolean hidden,
            int views, Timestamp createdAt, Timestamp updatedAt, String imageId,
            String review, String rejectReason, Timestamp reviewedAt, String requestedBy) {}

    /** Where a notice stands with the organiser. Only a PUBLISHED one reaches holders. */
    public static final String PUBLISHED = "PUBLISHED", PENDING = "PENDING", REJECTED = "REJECTED";

    private static final String AD_SELECT = "SELECT a.*, b.name AS booth_name FROM ad_post a "
        + "LEFT JOIN booth b ON b.id = a.booth_id ";

    private static final RowMapper<Ad> AD = (rs, row) -> new Ad(rs.getLong("id"), rs.getLong("session_id"),
        (Long) rs.getObject("booth_id", Long.class), rs.getString("booth_name"), rs.getString("category"),
        rs.getString("title"), rs.getString("body"), rs.getString("place"), rs.getTimestamp("starts_at"),
        rs.getTimestamp("ends_at"), rs.getBoolean("pinned"), rs.getBoolean("hidden"), rs.getInt("view_count"),
        rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"), rs.getString("image_id"),
        rs.getString("review_status"), rs.getString("reject_reason"), rs.getTimestamp("reviewed_at"),
        rs.getString("requested_by"));

    /** What a holder sees now: approved, shown, started and not yet over; pinned first, newest next. */
    public List<Ad> visibleAds(long sessionId, Timestamp now) {
        return jdbc.query(AD_SELECT + "WHERE a.session_id = ? AND a.review_status = 'PUBLISHED' "
            + "AND a.hidden = FALSE AND a.starts_at <= ? "
            + "AND (a.ends_at IS NULL OR a.ends_at > ?) ORDER BY a.pinned DESC, a.starts_at DESC",
            AD, sessionId, now, now);
    }

    /** One notice, if a holder may see it now. */
    public Optional<Ad> visibleAd(long sessionId, long id, Timestamp now) {
        return jdbc.query(AD_SELECT + "WHERE a.id = ? AND a.session_id = ? AND a.review_status = 'PUBLISHED' "
            + "AND a.hidden = FALSE AND a.starts_at <= ? "
            + "AND (a.ends_at IS NULL OR a.ends_at > ?)", AD, id, sessionId, now, now).stream().findFirst();
    }

    public List<Ad> allAds(long sessionId) {
        return jdbc.query(AD_SELECT + "WHERE a.session_id = ? ORDER BY a.pinned DESC, a.starts_at DESC", AD, sessionId);
    }

    /** The console's list under one review status; the oldest request first, as a queue reads. */
    public List<Ad> adsUnderReview(long sessionId, String review) {
        return jdbc.query(AD_SELECT + "WHERE a.session_id = ? AND a.review_status = ? ORDER BY a.updated_at, a.id",
            AD, sessionId, review);
    }

    /** How many of the event's notices stand under each review status. */
    public java.util.Map<String, Integer> reviewCounts(long sessionId) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        jdbc.query("SELECT review_status, COUNT(*) AS n FROM ad_post WHERE session_id = ? GROUP BY review_status",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(rs.getString("review_status"),
                rs.getInt("n")), sessionId);
        return counts;
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
        return insertAd(sessionId, boothId, category, title, body, place, startsAt, endsAt, pinned, imageId,
            PUBLISHED, null);
    }

    /** @param requestedBy the booth's counter that wrote it, or null for the organiser */
    public long insertAd(long sessionId, Long boothId, String category, String title, String body, String place,
            Timestamp startsAt, Timestamp endsAt, boolean pinned, String imageId, String review, String requestedBy) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ad_post (session_id, booth_id, category, title, body, place, starts_at, ends_at, pinned, "
                + "created_at, updated_at, image_id, review_status, reviewed_at, requested_by) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] { "id" });
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
            ps.setString(13, review);
            ps.setTimestamp(14, PUBLISHED.equals(review) ? now : null);
            ps.setString(15, requestedBy);
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

    /**
     * Where a notice stands with the organiser now. Going back to PENDING forgets when it
     * was last decided; a REJECTED one keeps its reason for the booth to read.
     *
     * @param requestedBy who at the booth sent it in again, or null to leave that as it is
     */
    public void setReview(long id, String review, String reason, String requestedBy) {
        Timestamp decided = PENDING.equals(review) ? null : Timestamp.from(java.time.Instant.now());
        jdbc.update("UPDATE ad_post SET review_status = ?, reject_reason = ?, reviewed_at = ?, "
            + "requested_by = COALESCE(?, requested_by) WHERE id = ?", review, reason, decided, requestedBy, id);
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

    /** A stored photo; {@code boothId} is the booth that put it up, null for the console. */
    public record Image(String id, long sessionId, String key, String backend, String type, Long boothId) {}

    private static final RowMapper<Image> IMAGE = (rs, row) -> new Image(rs.getString("id"), rs.getLong("session_id"),
        rs.getString("storage_key"), rs.getString("storage_backend"), rs.getString("media_type"),
        (Long) rs.getObject("booth_id", Long.class));

    public void insertImage(String id, long sessionId, Long boothId, String key, String backend, String type, long size) {
        jdbc.update("INSERT INTO ad_image (id, session_id, booth_id, storage_key, storage_backend, media_type, byte_size, "
            + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, sessionId, boothId, key, backend, type, size,
            Timestamp.from(java.time.Instant.now()));
    }

    public List<Image> imagesOf(long sessionId) {
        return jdbc.query("SELECT * FROM ad_image WHERE session_id = ?", IMAGE, sessionId);
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
