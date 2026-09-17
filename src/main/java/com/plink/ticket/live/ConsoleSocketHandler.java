package com.plink.ticket.live;

import com.plink.account.AdminPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The console's live channel: /ws/admin/sessions/{id}.
 *
 * <p>Authorised from the signed-in HTTP session copied onto the handshake, so the same
 * account that may open the ticket console may watch it. A socket is not a way around
 * the permission the filter chain enforces on every other ticket route.
 */
@Component
public class ConsoleSocketHandler extends TextWebSocketHandler {
    private final LiveEvents events;
    private final LiveSnapshots snapshots;

    public ConsoleSocketHandler(LiveEvents events, LiveSnapshots snapshots) {
        this.events = events;
        this.snapshots = snapshots;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if (!allowed(socket)) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
        String[] parts = socket.getUri() == null ? new String[0] : socket.getUri().getPath().split("/");
        // /ws/admin/sessions/{id}
        if (parts.length < 5) { socket.close(CloseStatus.BAD_DATA); return; }
        long sessionId;
        try { sessionId = Long.parseLong(parts[4]); }
        catch (NumberFormatException malformed) { socket.close(CloseStatus.BAD_DATA); return; }

        socket.getAttributes().put(TicketSocketHandler.SESSION_KEY, sessionId);
        events.join(sessionId, socket);
        events.send(socket, "OCCUPANCY", snapshots.occupancy(sessionId));
    }

    private boolean allowed(WebSocketSession socket) {
        Object context = socket.getAttributes()
            .get(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(context instanceof SecurityContext security)) return false;
        Authentication authentication = security.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return false;
        return authentication.getAuthorities().stream()
            .anyMatch(granted -> AdminPrincipal.TICKETS.equals(granted.getAuthority()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Object sessionId = socket.getAttributes().get(TicketSocketHandler.SESSION_KEY);
        if (sessionId instanceof Long id) events.leave(id, socket);
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable error) {
        afterConnectionClosed(socket, CloseStatus.SERVER_ERROR);
    }
}
