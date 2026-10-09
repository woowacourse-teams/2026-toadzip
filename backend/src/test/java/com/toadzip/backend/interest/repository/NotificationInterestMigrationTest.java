package com.toadzip.backend.interest.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class NotificationInterestMigrationTest {

    @Test
    void 기존_관리자_마이그레이션_다음에_결과를_추가하고_과거_완료를_추정하지_않는다() throws Exception {
        String database = "notification_outcome_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261008.03").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seed.execute("""
                            INSERT INTO notification_interest_events
                                (event_id, session_id, event_type, source, target_type, target_id, created_at)
                            VALUES ('00000000-0000-4000-8000-000000000003',
                                '00000000-0000-4000-8000-000000000002', 'CONFIRMED', 'COMPLEX_DETAIL',
                                'COMPLEX', '1', '2026-10-01T00:00:00Z')
                            """);
                }

                Flyway flyway = migration(database).target("20261008.04").load();
                assertEquals(1, flyway.migrate().migrationsExecuted);
                flyway.validate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    try (var history = query.executeQuery("""
                            SELECT script FROM flyway_schema_history
                            WHERE success AND version IN ('20261008.03', '20261008.04') ORDER BY version
                            """)) {
                        for (String script : List.of("V20261008_03__protect_admin_housing_type_updates.sql",
                                "V20261008_04__notification_interest_outcomes.sql")) {
                            assertTrue(history.next());
                            assertEquals(script, history.getString(1));
                        }
                    }
                    try (var event = query.executeQuery(
                            "SELECT outcome, request_fingerprint FROM notification_interest_events")) {
                        assertTrue(event.next());
                        assertEquals("UNKNOWN", event.getString("outcome"));
                        assertNull(event.getString("request_fingerprint"));
                    }
                    assertThrows(SQLException.class, () -> query.executeUpdate(
                            "UPDATE notification_interest_events SET outcome = 'UNSUPPORTED'"));
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    @Test
    void 회원_업무이력은_분석식별자없이_저장되고_탈퇴하면_함께_삭제된다() throws Exception {
        String database = "notification_privacy_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                Flyway flyway = migration(database).target("20261009.02").load();
                flyway.migrate();
                flyway.validate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    query.executeUpdate("""
                            INSERT INTO users(id,login_identifier,created_at) VALUES (1,'notification-test',now())
                            """);
                    query.executeUpdate("""
                            INSERT INTO notification_interest_events(event_id,event_type,source,target_type,target_id,
                                created_at,outcome,user_id,notice_version,settings_revision)
                            VALUES ('10000000-0000-4000-8000-000000000001','CONFIRMED','SETTING','REGION','11',now(),
                                'ACTIVATED',1,'notification-2026-10-09-v1',1)
                            """);
                    try (var result = query.executeQuery("SELECT session_id FROM notification_interest_events")) {
                        assertTrue(result.next());
                        assertNull(result.getObject(1));
                    }
                    assertThrows(SQLException.class, () -> query.executeUpdate(
                            "UPDATE notification_interest_events SET event_type = 'CLICKED'"));
                    query.executeUpdate("DELETE FROM users WHERE id = 1");
                    try (var result = query.executeQuery("SELECT count(*) FROM notification_interest_events")) {
                        assertTrue(result.next());
                        assertEquals(0, result.getInt(1));
                    }
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private FluentConfiguration migration(String database) {
        return Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                .locations("classpath:db/migration");
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        String port = System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432");
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }
}
