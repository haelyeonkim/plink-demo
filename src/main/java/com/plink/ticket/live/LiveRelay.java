package com.plink.ticket.live;

import java.util.function.Consumer;

/**
 * Carries live messages between copies of the app.
 *
 * <p>A phone's socket is held by whichever copy it reached, while the scan or the chat
 * line that concerns it may be handled by another. Each copy hands what it publishes to
 * the relay and delivers what the relay brings in from the others to its own sockets.
 */
public interface LiveRelay {

    /** Starts listening; every message another copy sends arrives at {@code receiver}. */
    void start(Consumer<String> receiver);

    /** Sends one message to every other copy. Called off the request thread. */
    void send(String message);
}
