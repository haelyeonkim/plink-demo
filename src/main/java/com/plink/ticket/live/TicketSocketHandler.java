package com.plink.ticket.live;

import com.plink.ticket.model.Ticket;
import com.plink.ticket.service.TicketService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * The holder's live channel: /ws/tickets/{sessionId}/{token}.
 *
 * <p>The token in the path is the credential, exactly as it is for the REST routes - a
 * socket cannot be opened for a ticket whose link you do not hold. Nothing is read from
 * the socket: the phone has nothing to tell the server that a signed request should not
 * carry instead.
 */
@Component
public class TicketSocketHandler extends TextWebSocketHandler {
    static final String SESSION_KEY = "plink.live.sessionId";
    static final String TICKET_KEY = "plink.live.ticketId";

    private final TicketService tickets;
    private final LiveEvents events;
    private final LiveSnapshots snapshots;

    public TicketSocketHandler(TicketService tickets, LiveEvents events, LiveSnapshots snapshots) {
        this.tickets = tickets;
        this.events = events;
        this.snapshots = snapshots;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String[] parts = socket.getUri() == null ? new String[0] : socket.getUri().getPath().split("/");
        // /ws/tickets/{sessionId}/{token}
        if (parts.length < 5) { socket.close(CloseStatus.BAD_DATA); return; }
        long sessionId;
        Ticket ticket;
        try {
            sessionId = Long.parseLong(parts[3]);
            ticket = tickets.resolve(sessionId, parts[4]).ticket;
        } catch (RuntimeException refused) {
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        socket.getAttributes().put(SESSION_KEY, ticket.sessionId);
        socket.getAttributes().put(TICKET_KEY, ticket.id);
        events.join(ticket.sessionId, socket);
        // The phone may have been asleep: it gets the current picture before any change.
        events.send(socket, "CROWDING", snapshots.crowding(ticket.sessionId));
        events.send(socket, "PRESENCE", snapshots.presence(ticket.id));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Object sessionId = socket.getAttributes().get(SESSION_KEY);
        if (sessionId instanceof Long id) events.leave(id, socket);
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable error) {
        afterConnectionClosed(socket, CloseStatus.SERVER_ERROR);
    }

    /** Ignores anything the client sends; the channel is one-way by design. */
    @Override
    protected void handleTextMessage(WebSocketSession socket, org.springframework.web.socket.TextMessage message) {
        Map<String, Object> ignored = Map.of();
        if (ignored.isEmpty()) return;
    }
}
