package com.plink.ticket.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The token secret keys every ticket URL, gate token and OTP hash, so shipping the
 * development default to a real origin has to be a startup failure rather than a note
 * in the log that nobody reads.
 */
class TicketStartupCheckTest {

    private TicketStartupCheck check(String secret, String baseUrl) {
        TicketProperties properties = new TicketProperties();
        properties.setTokenSecret(secret);
        return new TicketStartupCheck(properties, baseUrl);
    }

    @Test void theDevelopmentDefaultIsRefusedOnAnHttpsOrigin() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> check(TicketStartupCheck.DEV_SECRET, "https://tickets.example.com").verify());
        assertTrue(refused.getMessage().contains("TICKET_TOKEN_SECRET"));
        assertThrows(IllegalStateException.class,
            () -> check("", "https://tickets.example.com").verify());
    }

    @Test void localDevelopmentOnlyWarns() {
        assertDoesNotThrow(() -> check(TicketStartupCheck.DEV_SECRET, "http://localhost:3000").verify());
    }

    @Test void aRealSecretPassesAnywhere() {
        assertDoesNotThrow(() -> check("a-real-secret", "https://tickets.example.com").verify());
    }
}
