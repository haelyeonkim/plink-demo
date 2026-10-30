package com.plink;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ArtworkPriceMigrationTest {
    private HikariDataSource source;
    @AfterEach void close() { if (source != null) source.close(); }
    @ParameterizedTest
    @ValueSource(ints = {34, 35})
    void upgradesExistingDollarPricesAndPreservesInquiryTextAndSnapshots(int previousVersion) {
        source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        source.setUsername("sa"); source.setPassword(""); source.setMaximumPoolSize(2);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target(Integer.toString(previousVersion)).load().migrate();
        var jdbc = new JdbcTemplate(source);
        String body = "{\"artworks\":[{\"title\":\"A\",\"price\":\"$5,000.25\"},{\"price\":\"가격 문의\"},{\"price\":\"\"}]}";
        jdbc.update("INSERT INTO link_content(id,owner_sub,title,kind,body) VALUES (1,'user','Original','EXHIBITION',?)", body);
        jdbc.update("INSERT INTO link_content(id,owner_sub,title,kind,body) VALUES (2,'user','Snapshot','SELECTION',?)", body);
        String[] prices = {"$5,000.25", "가격 문의", "", "1,234", "€3,000.50", "$1,23", "9999999999999999.99"};
        for (int i = 0; i < prices.length; i++) jdbc.update(
            "INSERT INTO artwork(owner_sub,source_content_id,position,title,price_text,sale_status) VALUES ('user',1,?,'Work',?,'sold')", i, prices[i]);
        var current = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(44 - previousVersion);
        current.validate();
        assertThat(jdbc.queryForObject("SELECT price_amount FROM artwork WHERE position=0", java.math.BigDecimal.class)).isEqualByComparingTo("5000.25");
        assertThat(jdbc.queryForObject("SELECT price_currency FROM artwork WHERE position=0", String.class)).isEqualTo("USD");
        assertThat(jdbc.queryForObject("SELECT price_note FROM artwork WHERE position=1", String.class)).isEqualTo("가격 문의");
        assertThat(jdbc.queryForObject("SELECT price_mode FROM artwork WHERE position=2", String.class)).isEqualTo("hidden");
        assertThat(jdbc.queryForObject("SELECT price_amount FROM artwork WHERE position=3", java.math.BigDecimal.class)).isEqualByComparingTo("1234");
        assertThat(jdbc.queryForObject("SELECT price_currency FROM artwork WHERE position=4", String.class)).isEqualTo("EUR");
        assertThat(jdbc.queryForObject("SELECT price_note FROM artwork WHERE position=5", String.class)).isEqualTo("$1,23");
        assertThat(jdbc.queryForObject("SELECT price_amount FROM artwork WHERE position=6", java.math.BigDecimal.class)).isEqualByComparingTo("9999999999999999.99");
        assertThat(jdbc.queryForObject("SELECT price_text FROM artwork WHERE position=0", String.class)).isEqualTo("$5,000.25");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artwork WHERE sale_status='sold'", Integer.class)).isEqualTo(prices.length);
        assertThat(jdbc.queryForObject("SELECT body FROM link_content WHERE id=2", String.class)).isEqualTo(body);
        var migrated = new ObjectMapper().readTree(jdbc.queryForObject("SELECT body FROM link_content WHERE id=1", String.class));
        assertThat(migrated.path("artworks").get(0).path("priceAmount").asText()).isEqualTo("5000.25");
        assertThat(migrated.path("artworks").get(0).path("price").asText()).isEqualTo("$5,000.25");
        assertThat(current.migrate().migrationsExecuted).isZero();
    }
}
