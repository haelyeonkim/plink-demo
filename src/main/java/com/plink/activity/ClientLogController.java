package com.plink.activity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What only the browser knows: which screen was opened, and the script errors nobody
 * would otherwise hear about. Anyone can post here, so a row is short, and one address
 * gets a limited number of rows a minute.
 */
@RestController
public class ClientLogController {
    private static final int PER_MINUTE = 120;

    private final ActivityLog activity;
    private final Map<String, int[]> recent = new ConcurrentHashMap<>();
    private volatile long minute;

    public ClientLogController(ActivityLog activity) {
        this.activity = activity;
    }

    @PostMapping("/api/log/client")
    public ResponseEntity<Void> client(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        if (body == null || !allowed(request.getRemoteAddr())) return ResponseEntity.noContent().build();
        // The apps send {kind: VIEW|ERROR}; {type: view|error} is read too.
        String type = text(body.get("kind") != null ? body.get("kind") : body.get("type"), 16);
        type = type == null ? null : type.toLowerCase(java.util.Locale.ROOT);
        String app = text(body.get("app"), 16);
        String path = Redact.path(text(body.get("path"), 300));
        boolean error = "error".equals(type);
        Activity row = Activity.of(error ? Activity.ERROR : Activity.CLIENT, error ? "client.error" : "client.view")
            .ok(!error)
            .summary(error ? text(body.get("message"), 400) : path)
            .put("app", app)
            .put("screen", path)
            .put("stack", error ? text(body.get("stack"), 4000) : null)
            .put("width", body.get("width") instanceof Number width ? width : null)
            .put("referrer", text(body.get("referrer"), 300))
            .always();
        row.path = path;
        activity.record(row);
        return ResponseEntity.noContent().build();
    }

    private boolean allowed(String address) {
        long now = System.currentTimeMillis() / 60_000;
        if (now != minute) {
            recent.clear();
            minute = now;
        }
        int[] count = recent.computeIfAbsent(String.valueOf(address), key -> new int[1]);
        synchronized (count) {
            return ++count[0] <= PER_MINUTE;
        }
    }

    private static String text(Object value, int max) {
        return value == null ? null : ActivityLog.cut(String.valueOf(value), max);
    }
}
