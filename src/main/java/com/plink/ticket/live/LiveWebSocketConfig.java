package com.plink.ticket.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.net.URI;

/**
 * Two channels, both read-only to the client. The handshake carries the HTTP session so
 * the console channel can be authorised as the account that is already signed in, and
 * the origin is pinned to this deployment: a socket is exempt from CSRF, so the check
 * that a page from somewhere else cannot open one has to live here.
 */
@Configuration
@EnableWebSocket
public class LiveWebSocketConfig implements WebSocketConfigurer {
    private final TicketSocketHandler tickets;
    private final ConsoleSocketHandler console;
    private final String origin;

    public LiveWebSocketConfig(TicketSocketHandler tickets, ConsoleSocketHandler console,
            @Value("${plink.auth.base-url}") String baseUrl) {
        this.tickets = tickets;
        this.console = console;
        URI parsed = URI.create(baseUrl);
        this.origin = parsed.getScheme() + "://" + parsed.getAuthority();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        HttpSessionHandshakeInterceptor handshake = new HttpSessionHandshakeInterceptor();
        // Never create a session for a visitor who has none: a holder opening their
        // ticket is not signing in to anything.
        handshake.setCreateSession(false);
        registry.addHandler(tickets, "/ws/tickets/**")
            .addInterceptors(handshake).setAllowedOrigins(origin);
        registry.addHandler(console, "/ws/admin/sessions/**")
            .addInterceptors(handshake).setAllowedOrigins(origin);
    }
}
