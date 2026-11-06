package com.plink.activity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who the current request turned out to be acting as, once the code that knows has
 * found out: a holder after their token resolved, a gate after its token checked out, a
 * booth manager after their passkey. Admin accounts need no help - the security
 * context already says.
 *
 * <p>Kept on the request, so every row recorded later in the same request (and the
 * request's own row) carries it. Outside a request these calls do nothing.
 */
public final class ActivityContext {
    static final String ACTOR = ActivityContext.class.getName() + ".actor";
    static final String REQUEST_ID = ActivityContext.class.getName() + ".requestId";
    static final String EXCEPTION = ActivityContext.class.getName() + ".exception";

    record Actor(String type, String id, String email, Long sessionId, Long ticketId, String gateId, Long boothId) { }

    private ActivityContext() { }

    public static void holder(long ticketId, Long sessionId, String email) {
        set(new Actor("HOLDER", String.valueOf(ticketId), email, sessionId, ticketId, null, null));
    }

    public static void gate(String gateId, Long sessionId) {
        set(new Actor("GATE", gateId, null, sessionId, null, gateId, null));
    }

    public static void booth(long managerId, String email, Long sessionId, Long boothId) {
        set(new Actor("BOOTH", String.valueOf(managerId), email, sessionId, null, null, boothId));
    }

    public static void recipient(long exportId, String email) {
        set(new Actor("RECIPIENT", String.valueOf(exportId), email, null, null, null, null));
    }

    /** The event this request is about, when nothing else has said so yet. */
    public static void session(Long sessionId) {
        HttpServletRequest request = current();
        if (request == null || sessionId == null) return;
        Actor actor = (Actor) request.getAttribute(ACTOR);
        if (actor == null) {
            request.setAttribute(ACTOR, new Actor(null, null, null, sessionId, null, null, null));
        } else if (actor.sessionId() == null) {
            request.setAttribute(ACTOR, new Actor(actor.type(), actor.id(), actor.email(), sessionId,
                actor.ticketId(), actor.gateId(), actor.boothId()));
        }
    }

    static void set(Actor actor) {
        HttpServletRequest request = current();
        if (request == null) return;
        Actor before = (Actor) request.getAttribute(ACTOR);
        if (before != null && actor.sessionId() == null && before.sessionId() != null) {
            actor = new Actor(actor.type(), actor.id(), actor.email(), before.sessionId(),
                actor.ticketId(), actor.gateId(), actor.boothId());
        }
        request.setAttribute(ACTOR, actor);
    }

    static Actor actor(HttpServletRequest request) {
        return request == null ? null : (Actor) request.getAttribute(ACTOR);
    }

    static HttpServletRequest current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
