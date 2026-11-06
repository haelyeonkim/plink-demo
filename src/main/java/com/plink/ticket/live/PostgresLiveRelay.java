package com.plink.ticket.live;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The relay over the database every copy already shares: Postgres LISTEN/NOTIFY, so a
 * second copy of the app needs no broker of its own.
 *
 * <p>The channel is named after the schema. Stage and production share one database in
 * separate schemas, and a NOTIFY reaches every listener in the database whatever its
 * schema: on a common channel, stage's event 3 would light up production's event 3.
 *
 * <p>Listening holds one connection for as long as the app runs, so it is opened outside
 * the pool rather than taking one of its few slots. A NOTIFY is lost while that
 * connection is down; the screens poll behind the socket, so what is lost is freshness.
 */
@Component
@ConditionalOnExpression("${plink.live.relay:true} and '${spring.datasource.url:}'.startsWith('jdbc:postgresql')")
public class PostgresLiveRelay implements LiveRelay, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(PostgresLiveRelay.class);
    /** NOTIFY refuses payloads from 8000 bytes; this leaves room for the encoding. */
    static final int INLINE_LIMIT = 7000;
    /** A payload that is a pointer into live_relay rather than the message itself. */
    private static final String STORED = "@";
    private static final int IDLE_CHECK_MILLIS = 25_000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final JdbcTemplate jdbc;
    private final String url;
    private final String username;
    private final String password;
    private final String channel;
    private final AtomicLong stored = new AtomicLong();
    private volatile boolean running = true;
    private volatile Connection listening;
    private Thread listener;

    public PostgresLiveRelay(JdbcTemplate jdbc,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.flyway.default-schema:public}") String schema) {
        this.jdbc = jdbc;
        this.url = url;
        this.username = username;
        this.password = password;
        this.channel = channelFor(schema);
    }

    /** A channel per schema, spelled as an unquoted identifier so LISTEN and pg_notify agree. */
    public static String channelFor(String schema) {
        String name = "plink_live_" + schema.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return name.length() > 63 ? name.substring(0, 63) : name;
    }

    @Override
    public synchronized void start(Consumer<String> receiver) {
        if (listener != null) return;
        listener = new Thread(() -> listen(receiver), "live-relay");
        listener.setDaemon(true);
        listener.start();
    }

    @Override
    public void send(String message) {
        String payload = message;
        if (message.getBytes(StandardCharsets.UTF_8).length > INLINE_LIMIT) {
            Long id = jdbc.queryForObject("INSERT INTO live_relay (body) VALUES (?) RETURNING id", Long.class, message);
            payload = STORED + id;
            // Every copy has read a row within moments; the sweep only has to keep the
            // table from growing.
            if (stored.incrementAndGet() % 50 == 1) {
                jdbc.update("DELETE FROM live_relay WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'");
            }
        }
        jdbc.query("SELECT pg_notify(?, ?)", rs -> null, channel, payload);
    }

    private void listen(Consumer<String> receiver) {
        long backoff = 1000;
        while (running) {
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                listening = connection;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + channel);
                }
                PGConnection notices = connection.unwrap(PGConnection.class);
                log.info("Live relay listening on {}", channel);
                backoff = 1000;
                while (running) {
                    PGNotification[] arrived = notices.getNotifications(IDLE_CHECK_MILLIS);
                    if (arrived == null || arrived.length == 0) {
                        // A quiet channel and a dead connection look the same until something
                        // is asked of it.
                        try (Statement statement = connection.createStatement()) { statement.execute("SELECT 1"); }
                        continue;
                    }
                    for (PGNotification notification : arrived) deliver(notification.getParameter(), receiver);
                }
            } catch (SQLException | RuntimeException lost) {
                if (!running) return;
                log.warn("Live relay lost its connection, retrying in {} ms: {}", backoff, lost.getMessage());
                try { Thread.sleep(backoff); } catch (InterruptedException stop) { return; }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            } finally {
                listening = null;
            }
        }
    }

    private void deliver(String payload, Consumer<String> receiver) {
        try {
            if (payload.startsWith(STORED)) {
                long id = Long.parseLong(payload.substring(STORED.length()));
                List<String> body = jdbc.queryForList("SELECT body FROM live_relay WHERE id = ?", String.class, id);
                if (body.isEmpty()) return;
                payload = body.get(0);
            }
            receiver.accept(payload);
        } catch (RuntimeException unreadable) {
            // One message that cannot be delivered must not stop the ones behind it.
            log.warn("Dropping a relayed live message: {}", unreadable.getMessage());
        }
    }

    @Override
    public void destroy() {
        running = false;
        Connection connection = listening;
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) { /* already gone */ }
        }
        if (listener != null) listener.interrupt();
    }
}
