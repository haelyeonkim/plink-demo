package com.plink.ticket;

import com.plink.ticket.live.LiveEvents;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
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
        LiveEvents events = new LiveEvents(Runnable::run);
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
        LiveEvents events = new LiveEvents(Runnable::run);
        WebSocketSession mine = socket(7L);
        WebSocketSession somebodyElse = socket(9L);
        events.join(1L, mine);
        events.join(1L, somebodyElse);

        events.publish(1L, "CROWDING", Map.of("zones", java.util.List.of()));

        verify(mine).sendMessage(any(TextMessage.class));
        verify(somebodyElse).sendMessage(any(TextMessage.class));
    }

    /** Opens a transaction's synchronization the way the transaction manager does. */
    private static void begin() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void end() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void insideATransactionNothingGoesOutUntilItCommits() throws Exception {
        LiveEvents events = new LiveEvents(Runnable::run);
        WebSocketSession mine = socket(7L);
        events.join(1L, mine);
        begin();
        try {
            events.publish(1L, "CHAT", Map.of("lastId", 3));
            events.publishForTicket(1L, 7L, "COUPON", Map.of("outcome", "REDEEMED"));
            verify(mine, never()).sendMessage(any(TextMessage.class));
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            end();
        }
        verify(mine, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test
    void aRolledBackTransactionSaysNothing() throws Exception {
        LiveEvents events = new LiveEvents(Runnable::run);
        WebSocketSession mine = socket(7L);
        events.join(1L, mine);
        begin();
        try {
            events.publish(1L, "CHAT", Map.of("lastId", 3));
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            end();
        }
        verify(mine, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void aPhoneWhoseProofRanOutHearsNothingEvenFromABroadcast() throws Exception {
        LiveEvents events = new LiveEvents(Runnable::run);
        WebSocketSession stale = socket(7L);
        stale.getAttributes().put("plink.live.viewUntil", System.currentTimeMillis() - 1000);
        WebSocketSession console = socket(null);
        events.join(1L, stale);
        events.join(1L, console);

        events.publish(1L, "CHAT", Map.of("lastId", 3));

        verify(stale, never()).sendMessage(any(TextMessage.class));
        verify(stale).close();
        verify(console).sendMessage(any(TextMessage.class));
    }

    @Test
    void theSpringBuiltOneSendsOffTheCallersThread() throws Exception {
        LiveEvents events = new LiveEvents();
        try {
            WebSocketSession mine = socket(7L);
            events.join(1L, mine);
            events.publish(1L, "CHAT", Map.of("lastId", 3));
            verify(mine, timeout(2000)).sendMessage(any(TextMessage.class));
        } finally {
            events.destroy();
        }
    }

    /** Stands in for the database: whatever one copy sends, every started copy hears, itself included. */
    private static final class SharedRelay {
        final java.util.List<java.util.function.Consumer<String>> receivers = new java.util.ArrayList<>();

        com.plink.ticket.live.LiveRelay copy() {
            return new com.plink.ticket.live.LiveRelay() {
                @Override public void start(java.util.function.Consumer<String> receiver) { receivers.add(receiver); }
                @Override public void send(String message) { receivers.forEach(receiver -> receiver.accept(message)); }
            };
        }
    }

    @Test
    void aScanOnOneCopyReachesThePhoneConnectedToAnother() throws Exception {
        SharedRelay relay = new SharedRelay();
        LiveEvents gateSide = new LiveEvents(Runnable::run);
        LiveEvents phoneSide = new LiveEvents(Runnable::run);
        gateSide.setRelay(relay.copy());
        phoneSide.setRelay(relay.copy());
        WebSocketSession consoleHere = socket(null);
        WebSocketSession mine = socket(7L);
        WebSocketSession somebodyElse = socket(9L);
        gateSide.join(1L, consoleHere);
        phoneSide.join(1L, mine);
        phoneSide.join(1L, somebodyElse);

        gateSide.publishForTicket(1L, 7L, "MOVEMENT", Map.of("ticketRef", "AAAA-BBBB"));

        verify(mine).sendMessage(argThat((TextMessage m) -> m.getPayload().contains("\"type\":\"MOVEMENT\"")
            && m.getPayload().contains("AAAA-BBBB")));
        verify(somebodyElse, never()).sendMessage(any(TextMessage.class));
        // Its own message comes back through the relay and is not delivered twice.
        verify(consoleHere, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void aBroadcastReachesEveryCopyButOnlyItsOwnEvent() throws Exception {
        SharedRelay relay = new SharedRelay();
        LiveEvents one = new LiveEvents(Runnable::run);
        LiveEvents two = new LiveEvents(Runnable::run);
        one.setRelay(relay.copy());
        two.setRelay(relay.copy());
        WebSocketSession there = socket(7L);
        WebSocketSession otherEvent = socket(8L);
        two.join(1L, there);
        two.join(2L, otherEvent);

        one.publish(1L, "CHAT", Map.of("lastId", 3));

        verify(there).sendMessage(any(TextMessage.class));
        verify(otherEvent, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void aTicketThatChangedHandsIsCutOffOnEveryCopy() throws Exception {
        SharedRelay relay = new SharedRelay();
        LiveEvents one = new LiveEvents(Runnable::run);
        LiveEvents two = new LiveEvents(Runnable::run);
        one.setRelay(relay.copy());
        two.setRelay(relay.copy());
        WebSocketSession oldPhone = socket(7L);
        WebSocketSession keep = socket(9L);
        two.join(1L, oldPhone);
        two.join(1L, keep);

        one.dropTicket(7L);

        verify(oldPhone).close();
        verify(keep, never()).close();
    }

    @Test
    void nothingIsDeliveredTwiceWhenARelayedMessageWaitsForACommit() throws Exception {
        SharedRelay relay = new SharedRelay();
        LiveEvents one = new LiveEvents(Runnable::run);
        LiveEvents two = new LiveEvents(Runnable::run);
        one.setRelay(relay.copy());
        two.setRelay(relay.copy());
        WebSocketSession there = socket(7L);
        two.join(1L, there);
        begin();
        try {
            one.publish(1L, "CHAT", Map.of("lastId", 3));
            verify(there, never()).sendMessage(any(TextMessage.class));
            TransactionSynchronizationUtils.triggerAfterCommit();
        } finally {
            end();
        }
        verify(there, times(1)).sendMessage(any(TextMessage.class));
    }

    /** Stage and production share a database; NOTIFY does not know about schemas. */
    @Test
    void eachSchemaListensOnItsOwnChannel() {
        org.junit.jupiter.api.Assertions.assertEquals("plink_live_public",
            com.plink.ticket.live.PostgresLiveRelay.channelFor("public"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
            com.plink.ticket.live.PostgresLiveRelay.channelFor("public"),
            com.plink.ticket.live.PostgresLiveRelay.channelFor("stage"));
    }
}
