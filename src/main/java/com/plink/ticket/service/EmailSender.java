package com.plink.ticket.service;

/** Outbound mail seam: ticket links, verification codes and re-issue notices. */
public interface EmailSender {
    /**
     * Hands one message to the relay.
     *
     * @return true when it was accepted for delivery. A false here is not an error the
     *     caller has to abort on - an issued link still exists and can be handed over by
     *     hand - but it is the difference between "sent" and "not sent" that the console
     *     has to be honest about.
     */
    boolean send(String to, String subject, String body);

    /** Whether a relay is configured at all. False means every send only logs. */
    default boolean configured() { return true; }

    /** The address messages are sent from, for the settings screen. */
    default String from() { return ""; }
}
