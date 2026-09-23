package com.toadzip.backend;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class LocalProfileSchemaPersistenceTest {

    @Test
    void local_프로필은_Flyway로_스키마를_생성하고_종료_후에도_유지한다() throws Exception {
        String databaseName = "toadzip_local_profile_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:55432/" + databaseName;
        createDatabase(databaseName);

        try {
            try (ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run()) {
                String ddlAuto = applicationContext.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto");
                assertEquals("validate", ddlAuto);
            }

            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    ResultSet tables = connection.getMetaData()
                            .getTables(null, null, "announcements", new String[] {"TABLE"});
                    Statement statement = connection.createStatement();
                    ResultSet history = statement.executeQuery(
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE success"
                    )) {
                assertAll(
                        () -> assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName()),
                        () -> assertTrue(tables.next()),
                        () -> assertTrue(history.next()),
                        () -> assertEquals(3, history.getInt(1)),
                        () -> assertEquals(1, countColumn(connection,
                                "lh_announcement_detail_source", "request_hash")),
                        () -> assertEquals(1, countColumn(connection,
                                "lh_announcement_supply_source", "request_hash"))
                );
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    @Test
    void 기존_스키마를_baseline_후_통합_마이그레이션으로_보정한다() throws Exception {
        String databaseName = "toadzip_reconciliation_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:55432/" + databaseName;
        createDatabase(databaseName);

        try {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                ScriptUtils.executeSqlScript(connection, MigrationSqlSection.productionSchemaSnapshot());
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_detail_source (pan_id, source_order, dataset_type)
                        VALUES ('legacy-pan', 0, 'ETC_INFO')
                        """);
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_supply_source (pan_id, source_order)
                        VALUES ('legacy-pan', 0)
                        """);
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(jdbcUrl, "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration")
                    .baselineVersion("20260922.00")
                    .load();
            flyway.baseline();
            flyway.migrate();

            try (Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_detail_source
                            (pan_id, request_hash, source_order, dataset_type)
                        VALUES ('legacy-pan', repeat('a', 64), 0, 'ETC_INFO'),
                               ('legacy-pan', repeat('b', 64), 0, 'ETC_INFO')
                        """);
                statement.executeUpdate("""
                        INSERT INTO lh_announcement_supply_source (pan_id, request_hash, source_order)
                        VALUES ('legacy-pan', repeat('a', 64), 0),
                               ('legacy-pan', repeat('b', 64), 0)
                        """);
            }

            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BackendApplication.class)
                    .environment(createIsolatedEnvironment(jdbcUrl))
                    .run();
                    Connection connection = DriverManager.getConnection(jdbcUrl, "toadzip_test", "toadzip_test");
                    Statement statement = connection.createStatement();
                    ResultSet history = statement.executeQuery("""
                            SELECT string_agg(type || ':' || version, ',' ORDER BY installed_rank)
                            FROM flyway_schema_history
                            """)) {
                assertTrue(history.next());
                assertEquals("BASELINE:20260922.00,SQL:20260922.01,SQL:20260922.02,SQL:20260923.01",
                        history.getString(1));
                assertEquals(1, countColumn(connection, "announcements", "lh_reception_place_owned"));
                assertEquals(1, countColumn(connection, "supply_rows", "lh_total_supply_household_count_enriched"));
                assertEquals(1, countColumn(connection, "lh_announcement_detail_source", "request_hash"));
                assertEquals(1, countColumn(connection, "lh_announcement_supply_source", "request_hash"));
                assertEquals(1, countLegacyRow(connection, "lh_announcement_detail_source"));
                assertEquals(1, countLegacyRow(connection, "lh_announcement_supply_source"));
                assertEquals(3, countRows(connection, "lh_announcement_detail_source"));
                assertEquals(3, countRows(connection, "lh_announcement_supply_source"));
                assertEquals("UNIQUE (pan_id, request_hash, source_order, dataset_type)",
                        constraintDefinition(connection, "uk_lh_detail_source_request_row"));
                assertEquals("UNIQUE (pan_id, request_hash, source_order)",
                        constraintDefinition(connection, "uk_lh_supply_source_request_row"));
            }
        }
        finally {
            dropDatabase(databaseName);
        }
    }

    private int countColumn(Connection connection, String tableName, String columnName) throws Exception {
        try (var columns = connection.getMetaData().getColumns(null, "public", tableName, columnName)) {
            return columns.next() ? 1 : 0;
        }
    }

    private int countLegacyRow(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName
                        + " WHERE pan_id = 'legacy-pan' AND request_hash IS NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countRows(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName
                        + " WHERE pan_id = 'legacy-pan'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String constraintDefinition(Connection connection, String constraintName) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?
                """)) {
            statement.setString(1, constraintName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private void createDatabase(String databaseName) throws Exception {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
    }

    private void dropDatabase(String databaseName) throws Exception {
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/postgres", "toadzip_test", "toadzip_test");
    }

    private ConfigurableEnvironment createIsolatedEnvironment(String jdbcUrl) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "isolatedTestProperties",
                Map.of(
                        "spring.datasource.url", jdbcUrl,
                        "spring.datasource.username", "toadzip_test",
                        "spring.datasource.password", "toadzip_test",
                        "spring.datasource.driver-class-name", "org.postgresql.Driver",
                        "app.datasource.shared.url", sharedTestDatabaseUrl(),
                        "app.datasource.shared.username", "toadzip_shared_test",
                        "app.datasource.shared.password", "toadzip_shared_test",
                        "app.datasource.shared.driver-class-name", "org.postgresql.Driver",
                        "spring.main.web-application-type", "none"
                )
        ));
        environment.setActiveProfiles("local");
        return environment;
    }

    private String sharedTestDatabaseUrl() {
        return "jdbc:postgresql://127.0.0.1:55433/toadzip_shared_test";
    }
}
