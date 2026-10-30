package com.plink.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failed attempts per key inside a fixed window, in memory.
 *
 * <p>Each deployment runs as one process, so a map is enough; a restart forgets the
 * counts, which only ever errs towards letting somebody try again. Keys carry their own
 * prefix ("link-code:", "admin:" ...) so unrelated limits never share a counter.
 */
@Component
public class AttemptLimiter {
    private static final int PRUNE_AT = 10_000;

    private static final class Window {
        final long resetsAt;
        int failures;

        Window(long resetsAt) { this.resetsAt = resetsAt; }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Whether this key has already used up its failures for the current window. */
    public boolean blocked(String key, int maxFailures) {
        Window window = windows.get(key);
        if (window == null) return false;
        if (window.resetsAt <= System.currentTimeMillis()) {
            windows.remove(key, window);
            return false;
        }
        synchronized (window) { return window.failures >= maxFailures; }
    }

    /** Counts one failure; the window starts at the first failure, not at a fixed clock. */
    public void fail(String key, Duration window) {
        long now = System.currentTimeMillis();
        if (windows.size() > PRUNE_AT) prune(now);
        Window current = windows.compute(key, (ignored, existing) ->
            existing == null || existing.resetsAt <= now ? new Window(now + window.toMillis()) : existing);
        synchronized (current) { current.failures++; }
    }

    /** Forgets a key, after a success that proves the earlier failures were mistakes. */
    public void clear(String key) {
        windows.remove(key);
    }

    private void prune(long now) {
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (it.next().resetsAt <= now) it.remove();
        }
    }
}
