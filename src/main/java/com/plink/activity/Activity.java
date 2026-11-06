package com.plink.activity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened, built where it happened and handed to {@link ActivityLog}.
 *
 * <p>Only the category and the action are required. Who did it, from where and through
 * which request is filled in from the current request when the caller leaves it out, so
 * a service records what it knows and nothing more:
 * <pre>
 * activity.record(Activity.of(Activity.TICKET, "ticket.issued")
 *     .session(sessionId).ticket(ticketId).summary(email).put("tier", tier));
 * </pre>
 */
public final class Activity {
    public static final String ADMISSION = "ADMISSION";
    public static final String FACE = "FACE";
    public static final String TICKET = "TICKET";
    public static final String DELIVERY = "DELIVERY";
    public static final String VIEW = "VIEW";
    public static final String INVITE = "INVITE";
    public static final String BOOTH = "BOOTH";
    public static final String CHAT = "CHAT";
    public static final String LINK = "LINK";
    public static final String ADMIN = "ADMIN";
    public static final String AUTH = "AUTH";
    public static final String REQUEST = "REQUEST";
    public static final String CLIENT = "CLIENT";
    public static final String ERROR = "ERROR";

    final String category, action;
    boolean ok = true;
    String result, summary, actorType, actorId, actorEmail, gateId;
    Long sessionId, ticketId, boothId;
    final Map<String, Object> detail = new LinkedHashMap<>();
    // Request facts; the filter sets them, a service normally leaves them to the context.
    String httpMethod, path, ip, userAgent, requestId;
    Integer status, durationMs;
    java.sql.Timestamp at;
    /** Written even when the surrounding transaction rolls back - for refusals and failures. */
    boolean always;

    private Activity(String category, String action) {
        this.category = category;
        this.action = action;
    }

    public static Activity of(String category, String action) { return new Activity(category, action); }

    public Activity ok(boolean value) { this.ok = value; return this; }
    public Activity failed(String why) { this.ok = false; this.result = why; return this; }
    public Activity result(String value) { this.result = value; return this; }
    public Activity summary(String value) { this.summary = value; return this; }
    public Activity session(Long id) { this.sessionId = id; return this; }
    public Activity ticket(Long id) { this.ticketId = id; return this; }
    public Activity gate(String id) { this.gateId = id; return this; }
    public Activity booth(Long id) { this.boothId = id; return this; }
    public Activity actor(String type, String id, String email) {
        this.actorType = type; this.actorId = id; this.actorEmail = email; return this;
    }
    public Activity put(String key, Object value) {
        if (value != null) detail.put(key, value);
        return this;
    }
    public Activity putAll(Map<String, ?> values) {
        if (values != null) values.forEach(this::put);
        return this;
    }
    /** Keep the row whatever happens to the transaction it was recorded in. */
    public Activity always() { this.always = true; return this; }
}
