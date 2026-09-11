package com.plink.ticket.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The token secret keys every personal ticket URL, gate token and OTP hash. Shipping the
 * development default to a real origin would make all of them forgeable, so that
 * combination stops the application instead of warning about it.
 */
@Component
public class TicketStartupCheck {
    static final String DEV_SECRET = "dev-only-insecure-secret";
    private static final Logger log = LoggerFactory.getLogger(TicketStartupCheck.class);

    private final TicketProperties properties;
    private final String baseUrl;

    public TicketStartupCheck(TicketProperties properties, @Value("${plink.auth.base-url}") String baseUrl) {
        this.properties = properties;
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    void verify() {
        boolean insecureSecret = properties.getTokenSecret() == null
            || properties.getTokenSecret().isEmpty()
            || DEV_SECRET.equals(properties.getTokenSecret());
        if (!insecureSecret) return;
        if (baseUrl.startsWith("https://")) {
            throw new IllegalStateException(
                "TICKET_TOKEN_SECRET must be set to a private random value before serving " + baseUrl);
        }
        log.warn("plink.ticket.token-secret is the development default; set TICKET_TOKEN_SECRET before deploying.");
    }
}
