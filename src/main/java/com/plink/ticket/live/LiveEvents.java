package com.plink.ticket.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is listening to which event, and what gets sent to them.
 *
 * <p>Subscribers are held per event session because that is the unit everything here is
 * scoped to: a holder watching their own ticket and an operator watching the console are
 * both asking about the same event, and the gate scan that matters to both is one row in
 * the ledger.
 *
 * <p>The registry is in memory, which is the honest match for one application instance.
 * A second instance would see only its own subscribers; the fan-out would then belong in
 * Postgres LISTEN/NOTIFY rather than here.
 */
@Component
public class LiveEvents {
    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);

    private final Map<Long, Set<WebSocketSession>> listeners = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public void join(long sessionId, WebSocketSession socket) {
        listeners.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(socket);
    }

    public void leave(long sessionId, WebSocketSession socket) {
        Set<WebSocketSession> sockets = listeners.get(sessionId);
        if (sockets == null) return;
        sockets.remove(socket);
        if (sockets.isEmpty()) listeners.remove(sessionId);
    }

    public int listenerCount(long sessionId) {
        Set<WebSocketSession> sockets = listeners.get(sessionId);
        return sockets == null ? 0 : sockets.size();
    }

    /** Sends one message to one socket, dropping it if the peer has gone. */
    public void send(WebSocketSession socket, String type, Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.putAll(payload);
        String text = mapper.writeValueAsString(message);
        try {
            synchronized (socket) {
                if (socket.isOpen()) socket.sendMessage(new TextMessage(text));
            }
        } catch (IOException | IllegalStateException gone) {
            log.debug("Dropping a live listener: {}", gone.getMessage());
            try { socket.close(); } catch (IOException ignored) { /* already gone */ }
        }
    }

    /**
     * Tells everyone watching this event. Failures are per socket: one phone that went
     * into a tunnel must not stop the console from hearing the same scan.
     */
    public void publish(long sessionId, String type, Map<String, Object> payload) {
        Set<WebSocketSession> sockets = listeners.get(sessionId);
        if (sockets == null || sockets.isEmpty()) return;
        for (WebSocketSession socket : sockets) send(socket, type, payload);
    }
}
