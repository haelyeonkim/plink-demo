package com.plink.activity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.UUID;

/**
 * Every request the backend answers becomes one REQUEST row: who, from where, which
 * handler, with what (the body, secrets removed), the status and how long it took. A
 * request that ends in a server error also leaves an ERROR row with the stack.
 *
 * <p>This is the net under the hand-written rows: whatever a service forgot to record is
 * still here as the request that did it. It runs inside the security chain's output, so
 * the signed-in account is known; the request id it hands out ties a request's own rows
 * to it.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RequestLogFilter extends OncePerRequestFilter {
    static final String HANDLER = RequestLogFilter.class.getName() + ".handler";
    private static final int CACHE_LIMIT = 64 * 1024;

    private final ActivityLog activity;

    public RequestLogFilter(ActivityLog activity) {
        this.activity = activity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("OPTIONS".equals(request.getMethod())) return true;
        // The browser beacon records itself, as a CLIENT row.
        if (uri.startsWith("/api/log/")) return true;
        return !(uri.startsWith("/api/") || uri.startsWith("/login/") || uri.startsWith("/oauth2/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString().substring(0, 12);
        request.setAttribute(ActivityContext.REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        ContentCachingRequestWrapper wrapped = request instanceof ContentCachingRequestWrapper already
            ? already : new ContentCachingRequestWrapper(request, CACHE_LIMIT);
        Throwable thrown = null;
        try {
            chain.doFilter(wrapped, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            thrown = failure;
            throw failure;
        } finally {
            int status = thrown != null ? 500 : response.getStatus();
            int millis = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
            Throwable error = thrown != null ? thrown : (Throwable) request.getAttribute(ActivityContext.EXCEPTION);
            try {
                recordRequest(wrapped, status, millis, error);
            } catch (RuntimeException ignored) {
                // Logging must never turn a served request into a failed one.
            }
        }
    }

    private void recordRequest(ContentCachingRequestWrapper request, int status, int millis, Throwable error) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        String action = handler instanceof HandlerMethod method
            ? method.getBeanType().getSimpleName() + "." + method.getMethod().getName()
            : request.getMethod() + " " + Redact.path(request.getRequestURI());
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        ActivityContext.session(ActivityWebConfig.sessionOf(request));

        Activity row = Activity.of(Activity.REQUEST, action)
            .ok(status < 400)
            .result(String.valueOf(status))
            .put("pattern", pattern)
            .put("query", Redact.query(request.getParameterMap()))
            .put("body", Redact.body(request.getContentAsByteArray(), request.getContentType()))
            .put("referer", request.getHeader("Referer"))
            // As sent: the proxy in front passes a client's own header through, so these are
            // what the request claimed, never trusted as its address.
            .put("forwardedFor", request.getHeader("X-Forwarded-For"))
            .put("forwarded", request.getHeader("Forwarded"))
            .put("realIp", request.getHeader("X-Real-IP"))
            .put("cfIp", request.getHeader("CF-Connecting-IP"))
            .put("language", request.getHeader("Accept-Language"))
            .always();
        if (variables != null && !variables.isEmpty()) {
            Map<String, String> safe = new java.util.LinkedHashMap<>();
            variables.forEach((key, value) -> safe.put(key, Redact.secretKey(key) ? Redact.path(value) : value));
            row.put("vars", safe);
        }
        if (error != null) row.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        row.status = status;
        row.durationMs = millis;
        row.summary = request.getMethod() + " " + Redact.path(request.getRequestURI()) + " → " + status;
        activity.record(row);

        if (status >= 500) {
            Activity failure = Activity.of(Activity.ERROR, "server.error").failed(String.valueOf(status))
                .summary(error == null ? "응답 " + status : error.getClass().getSimpleName() + ": " + error.getMessage())
                .put("handler", action)
                .put("pattern", pattern)
                .put("stack", error == null ? null : stack(error))
                .always();
            failure.status = status;
            failure.durationMs = millis;
            activity.record(failure);
        }
    }

    static String stack(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        String text = out.toString();
        return text.length() > 8000 ? text.substring(0, 8000) + "…" : text;
    }
}
