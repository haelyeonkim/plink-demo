package com.plink.activity;

import com.plink.account.AdminPrincipal;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes {@link Activity} rows to {@code activity_log}.
 *
 * <p>Recording must never slow down or break the thing being recorded, so a row is put
 * on a queue and written in batches about once a second, off the request thread. Who,
 * where from and which request are read when the row is recorded, while the request is
 * still there to read.
 *
 * <p>Inside a transaction the row waits for it to end: a commit writes it as recorded,
 * a rollback writes it as failed - the attempt still happened. Rows marked
 * {@link Activity#always()} skip the wait.
 */
@Component
public class ActivityLog {
    private static final Logger log = LoggerFactory.getLogger(ActivityLog.class);
    private static final int QUEUE_LIMIT = 200_000;
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentLinkedQueue<Activity> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final boolean enabled;

    /** The running log, for code that records without having it injected (see {@link #emit}). */
    private static volatile ActivityLog current;

    public ActivityLog(JdbcTemplate jdbc, @Value("${plink.activity.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        current = this;
    }

    /**
     * Records through the running log, like a logger: the rows come from dozens of
     * services and repositories, and threading a collaborator through each of them (and
     * every test that builds one by hand) would buy nothing. Before the application has
     * started, and in tests that build a service by hand, this does nothing.
     */
    public static void emit(Activity activity) {
        ActivityLog log = current;
        if (log != null) log.record(activity);
    }

    public void record(Activity activity) {
        if (!enabled || activity == null) return;
        try {
            fill(activity);
        } catch (RuntimeException unreadable) {
            // A missing request detail is no reason to lose the row.
            log.debug("Could not read the request for an activity row: {}", unreadable.getMessage());
        }
        if (activity.always || !TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueue(activity);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED && activity.ok) {
                    activity.ok = false;
                    activity.put("rolledBack", true);
                    if (activity.result == null) activity.result = "rolled_back";
                }
                enqueue(activity);
            }
        });
    }

    private void enqueue(Activity activity) {
        if (queued.incrementAndGet() > QUEUE_LIMIT) {
            queued.decrementAndGet();
            if (dropped.incrementAndGet() % 1000 == 1) {
                log.warn("Activity queue is full; {} rows dropped so far", dropped.get());
            }
            return;
        }
        queue.add(activity);
    }

    /** Fills in when, who and which request from the current thread. */
    private void fill(Activity a) {
        if (a.at == null) a.at = new Timestamp(System.currentTimeMillis());
        HttpServletRequest request = ActivityContext.current();
        ActivityContext.Actor actor = ActivityContext.actor(request);
        if (a.actorType == null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object principal = auth == null ? null : auth.getPrincipal();
            if (principal instanceof AdminPrincipal admin) {
                a.actorType = "ADMIN"; a.actorId = String.valueOf(admin.id); a.actorEmail = admin.email;
            } else if (principal instanceof OidcUser google) {
                a.actorType = "ADMIN"; a.actorId = "google:" + google.getSubject(); a.actorEmail = google.getEmail();
            } else if (actor != null && actor.type() != null) {
                a.actorType = actor.type(); a.actorId = actor.id(); a.actorEmail = actor.email();
            } else if (request == null) {
                a.actorType = "SYSTEM";
            } else {
                a.actorType = "ANON";
            }
        }
        if (actor != null) {
            if (a.sessionId == null) a.sessionId = actor.sessionId();
            if (a.ticketId == null && "HOLDER".equals(a.actorType)) a.ticketId = actor.ticketId();
            if (a.gateId == null) a.gateId = actor.gateId();
            if (a.boothId == null) a.boothId = actor.boothId();
        }
        if (request != null) {
            if (a.httpMethod == null) a.httpMethod = request.getMethod();
            if (a.path == null) a.path = Redact.path(request.getRequestURI());
            if (a.ip == null) a.ip = request.getRemoteAddr();
            if (a.userAgent == null) a.userAgent = request.getHeader("User-Agent");
            if (a.requestId == null) a.requestId = (String) request.getAttribute(ActivityContext.REQUEST_ID);
        }
    }

    /** Writes what is waiting. Scheduled, and called on shutdown and by tests. */
    @Scheduled(fixedDelay = 1000)
    public synchronized void flush() {
        List<Activity> batch = new ArrayList<>(BATCH);
        Activity next;
        while ((next = queue.poll()) != null) {
            queued.decrementAndGet();
            batch.add(next);
            if (batch.size() == BATCH) {
                write(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) write(batch);
    }

    @PreDestroy
    void close() {
        try { flush(); } catch (RuntimeException ignored) { /* shutting down */ }
    }

    private void write(List<Activity> rows) {
        try {
            jdbc.batchUpdate("INSERT INTO activity_log (occurred_at, category, action, ok, result, session_id, "
                + "actor_type, actor_id, actor_email, ticket_id, gate_id, booth_id, summary, detail, http_method, "
                + "path, status, duration_ms, ip, user_agent, request_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows, rows.size(),
                (ps, a) -> {
                    ps.setTimestamp(1, a.at);
                    ps.setString(2, cut(a.category, 16));
                    ps.setString(3, cut(a.action, 64));
                    ps.setBoolean(4, a.ok);
                    ps.setString(5, cut(a.result, 40));
                    setLong(ps, 6, a.sessionId);
                    ps.setString(7, cut(a.actorType, 12));
                    ps.setString(8, cut(a.actorId, 80));
                    ps.setString(9, cut(a.actorEmail, 320));
                    setLong(ps, 10, a.ticketId);
                    ps.setString(11, cut(a.gateId, 32));
                    setLong(ps, 12, a.boothId);
                    ps.setString(13, cut(a.summary, 500));
                    ps.setString(14, a.detail.isEmpty() ? null : json(a));
                    ps.setString(15, cut(a.httpMethod, 8));
                    ps.setString(16, cut(a.path, 500));
                    if (a.status == null) ps.setNull(17, Types.INTEGER); else ps.setInt(17, a.status);
                    if (a.durationMs == null) ps.setNull(18, Types.INTEGER); else ps.setInt(18, a.durationMs);
                    ps.setString(19, cut(a.ip, 64));
                    ps.setString(20, cut(a.userAgent, 500));
                    ps.setString(21, cut(a.requestId, 40));
                });
        } catch (RuntimeException failed) {
            log.error("Could not write {} activity rows: {}", rows.size(), failed.getMessage());
        }
    }

    private String json(Activity a) {
        try {
            return json.writeValueAsString(a.detail);
        } catch (RuntimeException unserialisable) {
            return "{\"unserialisable\":" + json.writeValueAsString(String.valueOf(a.detail)) + "}";
        }
    }

    private static void setLong(java.sql.PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT); else ps.setLong(index, value);
    }

    static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
