package com.plink;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationUpgradeTest {
    @TempDir Path legacy;
    private HikariDataSource pool;

    private HikariDataSource dataSource(String url) {
        if (pool == null) {
            pool = new HikariDataSource();
            pool.setJdbcUrl(url);
            pool.setUsername("sa");
            pool.setPassword("");
            pool.setMaximumPoolSize(2);
        }
        return pool;
    }

    @AfterEach void closePool() {
        if (pool != null) pool.close();
    }

    private Flyway flyway(String url, String location) {
        return Flyway.configure().dataSource(dataSource(url)).locations(location).load();
    }

    @Test void upgradesProductionWithoutLosingArtworkOrTestEvents() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(dataSource(url)).locations("classpath:db/migration")
                .target("32").load().migrate();
        try (var c = dataSource(url).getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO link_content(id,owner_sub,title,body) VALUES (1,'owner','Catalog','{}')");
            s.execute("INSERT INTO artwork(id,owner_sub,source_content_id,position,title) VALUES (1,'owner',1,0,'Work')");
            s.execute("INSERT INTO event_session(id,name,starts_at,test_event) VALUES (1,'Rehearsal',CURRENT_TIMESTAMP,TRUE)");
        }
        var current = flyway(url, "classpath:db/migration");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(4);
        current.validate();
        try (var c = dataSource(url).getConnection(); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT a.title,a.sale_status,a.archived,ca.artwork_id FROM artwork a JOIN content_artwork ca ON ca.artwork_id=a.id")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("Work");
                assertThat(r.getString(2)).isNull();
                assertThat(r.getBoolean(3)).isFalse();
                assertThat(r.getLong(4)).isEqualTo(1);
            }
            try (var r = s.executeQuery("SELECT test_event FROM event_session WHERE id=1")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBoolean(1)).isTrue();
            }
        }
    }

    @Test void reconcilesLegacyStageAndPreservesSoldStatus() throws Exception {
        Path source = Path.of("src/main/resources/db/migration");
        try (var paths = Files.list(source)) {
            for (Path p : paths.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("V32__") || name.startsWith("V35__")) continue;
                if (name.startsWith("V33__")) name = name.replace("V33__", "V32__");
                else if (name.startsWith("V34__")) name = name.replace("V34__", "V33__");
                Files.copy(p, legacy.resolve(name));
            }
        }
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        flyway(url, "filesystem:" + legacy).migrate();
        try (var c = dataSource(url).getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO link_content(id,owner_sub,title,body) VALUES (1,'owner','Catalog','{}')");
            s.execute("INSERT INTO artwork(id,owner_sub,source_content_id,position,title,sale_status) VALUES (1,'owner',1,0,'Work','sold')");
            s.execute("INSERT INTO content_artwork(content_id,position,artwork_id) VALUES (1,0,1)");
            s.execute("UPDATE \"flyway_schema_history\" SET \"version\"='34',\"script\"='V34__shared_artwork_status.sql' WHERE \"version\"='33'");
            s.execute("UPDATE \"flyway_schema_history\" SET \"version\"='33',\"script\"='V33__artwork_sale_status.sql' WHERE \"version\"='32'");
        }
        var reconcile = Flyway.configure().dataSource(dataSource(url))
                .locations("classpath:db/migration").outOfOrder(true).load();
        assertThat(reconcile.migrate().migrationsExecuted).isEqualTo(3);
        flyway(url, "classpath:db/migration").validate();
        try (var c = dataSource(url).getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT a.sale_status FROM artwork a JOIN content_artwork ca ON ca.artwork_id=a.id")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("sold");
        }
    }
}
