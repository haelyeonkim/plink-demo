package com.plink.ticket;

import com.plink.ticket.live.LiveEvents;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A scan says where one named person is. The console asked about the event; a phone asked
 * about its own ticket, so what reaches it has to be about that ticket alone - both to
 * keep the seat number of a stranger off the screen and so the phone can react to
 * anything it receives as its own admission.
 */
class LiveRoutingTest {

    private WebSocketSession socket(Long ticketId) {
        WebSocketSession socket = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (ticketId != null) attributes.put("plink.live.ticketId", ticketId);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }

    @Test
    void aMovementReachesTheConsoleAndOnlyThatTicketsPhone() throws Exception {
        LiveEvents events = new LiveEvents();
        WebSocketSession console = socket(null);
        WebSocketSession mine = socket(7L);
        WebSocketSession somebodyElse = socket(9L);
        events.join(1L, console);
        events.join(1L, mine);
        events.join(1L, somebodyElse);

        events.publishForTicket(1L, 7L, "MOVEMENT", Map.of("ticketRef", "AAAA-BBBB"));

        verify(console).sendMessage(any(TextMessage.class));
        verify(mine).sendMessage(any(TextMessage.class));
        verify(somebodyElse, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void crowdingStillGoesToEverybody() throws Exception {
        LiveEvents events = new LiveEvents();
        WebSocketSession mine = socket(7L);
        WebSocketSession somebodyElse = socket(9L);
        events.join(1L, mine);
        events.join(1L, somebodyElse);

        events.publish(1L, "CROWDING", Map.of("zones", java.util.List.of()));

        verify(mine).sendMessage(any(TextMessage.class));
        verify(somebodyElse).sendMessage(any(TextMessage.class));
    }
}
