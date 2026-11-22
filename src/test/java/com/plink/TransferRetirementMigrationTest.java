package com.plink;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** A ticket caught mid-handover when holder transfers went away is returned to where it was. */
class TransferRetirementMigrationTest {
    private HikariDataSource source;
    @AfterEach void close() { if (source != null) source.close(); }

    @Test
    void ticketsLeftMidHandoverAreGivenBackAndTheHandoversClosed() {
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUsername("sa"); source.setPassword(""); source.setMaximumPoolSize(2);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("59").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO event_session(id, name, starts_at) VALUES (1, 'Fair', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO holder(id, email, user_handle) VALUES (1, 'a@example.com', 'handle-a')");
        jdbc.update("INSERT INTO ticket(id, session_id, ticket_ref, token_hmac, issued_to_email, status, holder_id) VALUES (1, 1, 'REF1', 'h1', 'a@example.com', 'TRANSFER_PENDING', 1)");
        jdbc.update("INSERT INTO ticket(id, session_id, ticket_ref, token_hmac, issued_to_email, status) VALUES (2, 1, 'REF2', 'h2', 'd@example.com', 'TRANSFER_PENDING')");
        jdbc.update("INSERT INTO ticket(id, session_id, ticket_ref, token_hmac, issued_to_email, status) VALUES (3, 1, 'REF3', 'h3', 'e@example.com', 'REVOKED')");
        jdbc.update("INSERT INTO ticket_transfer(ticket_id, from_email, to_email, to_token_hmac, status, expires_at) "
            + "VALUES (1, 'a@example.com', 'b@example.com', 't1', 'PENDING', CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO ticket_transfer(ticket_id, from_email, to_email, to_token_hmac, status, expires_at, closed_at) "
            + "VALUES (2, 'a@example.com', 'c@example.com', 't2', 'ACCEPTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForList("SELECT status FROM ticket ORDER BY id", String.class))
            .containsExactly("BOUND", "ISSUED", "REVOKED");
        assertThat(jdbc.queryForList("SELECT status FROM ticket_transfer ORDER BY id", String.class))
            .containsExactly("CANCELLED", "ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_transfer WHERE closed_at IS NULL", Integer.class)).isZero();
    }
}
