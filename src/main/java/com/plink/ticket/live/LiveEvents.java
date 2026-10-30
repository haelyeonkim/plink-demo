package com.plink.ticket.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

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
 *
 * <p>Nothing is published from the caller's thread. Inside a transaction a message waits
 * for the commit - a phone told before it reloads into the old row, and one told about
 * something that then rolls back reloads into a lie - and is dropped on rollback. The
 * sends themselves run on a few writer threads, so a request holding one of the pool's
 * few database connections never also waits on a phone in a tunnel.
 */
@Component
public class LiveEvents implements org.springframework.beans.factory.DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);
    private static final int LANES = 4;

    private final Map<Long, Set<WebSocketSession>> listeners = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * One thread per lane, and each socket always in the same lane: messages to one phone
     * keep their order (MOVEMENT before PRESENCE), and a slow phone holds up only its lane.
     */
    private final Executor[] lanes;

    @Autowired
    public LiveEvents() {
        lanes = new Executor[LANES];
        for (int i = 0; i < LANES; i++) {
            String name = "live-send-" + i;
            lanes[i] = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    /** Sends on the given executor instead; a test passes {@code Runnable::run} to see each send at once. */
    public LiveEvents(Executor sender) {
        lanes = new Executor[] { sender };
    }

    @Override
    public void destroy() {
        for (Executor lane : lanes) if (lane instanceof ExecutorService service) service.shutdownNow();
    }

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
            closeQuietly(socket);
        }
    }

    /**
     * Tells everyone watching this event. Failures are per socket: one phone that went
     * into a tunnel must not stop the console from hearing the same scan.
     */
    public void publish(long sessionId, String type, Map<String, Object> payload) {
        afterCommit(() -> fanOut(sessionId, type, payload, socket -> true));
    }

    /**
     * Tells the operators, and the one holder the row is about.
     *
     * <p>A scan says where a named person is. The console is watching the whole event and
     * needs it; the phone in the next queue is watching its own ticket and does not, so
     * it never learns that seat B-12 just walked in. It also means a holder's screen can
     * treat anything arriving on its socket as its own movement.
     */
    public void publishForTicket(long sessionId, long ticketId, String type, Map<String, Object> payload) {
        afterCommit(() -> fanOut(sessionId, type, payload, socket -> {
            Object watching = socket.getAttributes().get(TicketSocketHandler.TICKET_KEY);
            // No ticket on the socket means the console, which watches all of them.
            return watching == null || watching.equals(ticketId);
        }));
    }

    /** Now, outside a transaction; inside one, once it has committed, and never if it rolls back. */
    private static void afterCommit(Runnable dispatch) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }

    private void fanOut(long sessionId, String type, Map<String, Object> payload, Predicate<WebSocketSession> wanted) {
        Set<WebSocketSession> sockets = listeners.get(sessionId);
        if (sockets == null || sockets.isEmpty()) return;
        for (WebSocketSession socket : sockets) {
            if (!wanted.test(socket)) continue;
            try {
                laneOf(socket).execute(() -> {
                    // A phone whose passkey proof has run out hears nothing more until it
                    // proves it again, which opens a new channel.
                    if (socket.getAttributes().get(TicketSocketHandler.VIEW_UNTIL_KEY) instanceof Long until
                            && until < System.currentTimeMillis()) {
                        closeQuietly(socket);
                        return;
                    }
                    send(socket, type, payload);
                });
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                return;
            }
        }
    }

    private Executor laneOf(WebSocketSession socket) {
        return lanes[Math.floorMod(System.identityHashCode(socket), lanes.length)];
    }

    /**
     * Closes every holder channel on this ticket. Called when the ticket changes hands -
     * recovered onto a new phone, or transferred - so the old phone stops hearing about
     * the new holder.
     */
    public void dropTicket(long ticketId) {
        for (Set<WebSocketSession> sockets : listeners.values()) {
            for (WebSocketSession socket : sockets) {
                if (Long.valueOf(ticketId).equals(socket.getAttributes().get(TicketSocketHandler.TICKET_KEY))) {
                    closeQuietly(socket);
                }
            }
        }
    }

    private void closeQuietly(WebSocketSession socket) {
        try { socket.close(); } catch (IOException ignored) { /* already gone */ }
    }
}
