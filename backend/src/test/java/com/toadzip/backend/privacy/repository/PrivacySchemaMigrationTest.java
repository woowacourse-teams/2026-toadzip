package com.toadzip.backend.privacy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PrivacySchemaMigrationTest {

    private static final String PRIVACY_VERSION = "20261009.04";
    private static final String CONSENT_ID = "10000000-0000-4000-8000-000000000001";
    private static final String COMMAND_ID = "20000000-0000-4000-8000-000000000001";
    private static final String EVENT_ID = "30000000-0000-4000-8000-000000000001";
    private static final Instant UPDATED_AT = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2027-03-01T12:00:00Z");
    private static final Instant LATE_CANCELLED_EXPIRES_AT = Instant.parse("2026-02-01T12:00:00Z");

    @Test
    void 기존_데이터와_미확정_고지를_보존하며_파기기한만_채운다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.migration("20261008.04").migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                seedLegacyData(statement);
            }
            verifySchemaGate(database, database.name(), "before");
            assertSchemaGateRejected(database, database.name(), "after");

            Flyway flyway = database.migration(PRIVACY_VERSION);
            assertEquals(4, flyway.migrate().migrationsExecuted);
            flyway.validate();

            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                assertLegacyDataPreserved(statement);
                assertRetentionDeadlines(statement);
                assertEquals(0, count(statement, "analytics_consents"));
                assertEquals(0, count(statement, "analytics_consent_events"));
                assertEquals(0, count(statement, "user_deletion_markers"));
            }
            verifySchemaGate(database, database.name(), "after");

            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();
        }
    }

    @Test
    void 중간_버전의_파기기한을_보존하며_누락분만_채운다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.migration("20261009.03").migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                seedLegacyData(statement);
                statement.executeUpdate("""
                        INSERT INTO notification_subscriptions
                            (user_id,target_type,target_id,active,updated_at,expires_at,notice_version,
                             requested_at,purge_after)
                        VALUES (1,'REGION','28',true,'2026-03-01T12:00:00Z','2027-03-01T12:00:00Z',
                            'notification-2026-10-09-v1','2026-03-01T12:00:00Z','2027-04-01T12:00:00Z')
                        """);
                try (var legacy = statement.executeQuery("""
                        SELECT count(*) FROM notification_subscriptions
                        WHERE target_id IN ('11','26','27') AND purge_after IS NULL
                        """)) {
                    assertTrue(legacy.next());
                    assertEquals(3, legacy.getInt(1));
                }
            }
            verifySchemaGate(database, database.name(), "before");

            // Calendar-day arithmetic would differ from Duration.ofDays across a DST transition.
            Flyway flyway = database.migration(PRIVACY_VERSION, "America/New_York");
            assertEquals(1, flyway.migrate().migrationsExecuted);
            flyway.validate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                assertRetentionDeadlines(statement);
                try (var row = statement.executeQuery("""
                        SELECT notice_version,requested_at,purge_after FROM notification_subscriptions
                        WHERE target_id='28'
                        """)) {
                    assertTrue(row.next());
                    assertEquals("notification-2026-10-09-v1", row.getString("notice_version"));
                    assertEquals(UPDATED_AT, row.getTimestamp("requested_at").toInstant());
                    assertEquals(Instant.parse("2027-04-01T12:00:00Z"), row.getTimestamp("purge_after").toInstant());
                }
            }
            verifySchemaGate(database, database.name(), "after");
            assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }

    @Test
    void 빈_DB에_개인정보_스키마와_참조_무결성_및_파기_인덱스를_생성한다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            verifySchemaGate(database, database.name(), "before");
            assertSchemaGateRejected(database, database.name(), "after");
            Flyway flyway = database.migration(PRIVACY_VERSION);
            flyway.migrate();
            flyway.validate();
            verifySchemaGate(database, database.name(), "after");
            assertSchemaGateRejected(database, "different_database", "after");
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                assertPrivacyIndexes(statement);
                seedLegacyData(statement);
                assertConsentConstraints(statement);
                assertConsentEventConstraints(statement);
                assertDeletionMarkerConstraints(statement);
                assertMemberReceiptConstraints(statement);
                statement.executeUpdate("DELETE FROM users WHERE id=1");
                assertEquals(0, count(statement, "analytics_consents"));
                assertEquals(0, count(statement, "analytics_consent_events"));
                assertEquals(0, count(statement, "notification_subscriptions"));
                assertEquals(1, count(statement, "notification_interest_events"));
                statement.execute("DROP INDEX analytics_consent_events_purge_idx");
            }
            assertSchemaGateRejected(database, database.name(), "after");
            assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }

    @Test
    void 배포_사전검사는_이력없는_기존_DB와_이력에_없는_개인정보_테이블을_거절한다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE migration_guard_fixture(id BIGINT)");
            }
            assertSchemaGateRejected(database, database.name(), "before");
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE migration_guard_fixture");
            }
            database.migration("20261008.04").migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE analytics_consents(id UUID)");
            }
            assertSchemaGateRejected(database, database.name(), "before");
        }
    }

    private void verifySchemaGate(TestDatabase database, String expectedDatabase, String phase) throws Exception {
        String query = Files.readString(Path.of("..", "scripts", "check-privacy-schema.sql"))
                .replace(":'expected_db'", "'" + expectedDatabase.replace("'", "''") + "'")
                .replace(":'phase'", "'" + phase.replace("'", "''") + "'");
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private void assertSchemaGateRejected(TestDatabase database, String expectedDatabase, String phase) {
        SQLException failure = assertThrows(SQLException.class,
                () -> verifySchemaGate(database, expectedDatabase, phase));
        assertEquals("P0001", failure.getSQLState());
    }

    private void seedLegacyData(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO users(id,login_identifier,created_at,email)
                VALUES (1,'privacy-migration-fixture','2026-03-01T12:00:00','fixture@example.invalid')
                """);
        statement.executeUpdate("""
                INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (1,'REGION','11',true,'2026-03-01T12:00:00Z','2027-03-01T12:00:00Z'),
                    (1,'REGION','26',false,'2026-03-01T12:00:00Z','2027-03-01T12:00:00Z'),
                    (1,'REGION','27',false,'2026-03-01T12:00:00Z','2026-02-01T12:00:00Z')
                """);
        statement.executeUpdate("""
                INSERT INTO notification_interest_events
                    (event_id,session_id,event_type,source,target_type,target_id,created_at)
                VALUES ('40000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000001',
                    'CONFIRMED','SETTING','REGION','11','2026-03-01T12:00:00Z')
                """);
    }

    private void assertLegacyDataPreserved(Statement statement) throws SQLException {
        assertEquals(1, count(statement, "users"));
        assertEquals(3, count(statement, "notification_subscriptions"));
        try (var user = statement.executeQuery("""
                SELECT login_identifier,email,created_at,registration_policy_version,notification_settings_revision
                FROM users WHERE id=1
                """)) {
            assertTrue(user.next());
            assertEquals("privacy-migration-fixture", user.getString("login_identifier"));
            assertEquals("fixture@example.invalid", user.getString("email"));
            assertEquals("2026-03-01T12:00", user.getTimestamp("created_at").toLocalDateTime().toString());
            assertNull(user.getString("registration_policy_version"));
            assertEquals(0, user.getLong("notification_settings_revision"));
        }
        try (var event = statement.executeQuery("SELECT * FROM notification_interest_events")) {
            assertTrue(event.next());
            assertEquals(UUID.fromString("50000000-0000-4000-8000-000000000001"), event.getObject("session_id"));
            assertEquals("UNKNOWN", event.getString("outcome"));
            assertEquals("CONFIRMED", event.getString("event_type"));
            assertEquals(UPDATED_AT, event.getTimestamp("created_at").toInstant());
            assertNull(event.getObject("user_id"));
            assertNull(event.getString("notice_version"));
            assertNull(event.getObject("settings_revision"));
            assertFalse(event.next());
        }
    }

    private void assertRetentionDeadlines(Statement statement) throws SQLException {
        try (var row = statement.executeQuery("""
                SELECT target_id,active,updated_at,expires_at,notice_version,requested_at,purge_after
                FROM notification_subscriptions WHERE target_id IN ('11','26','27') ORDER BY target_id
                """)) {
            assertTrue(row.next());
            assertEquals("11", row.getString("target_id"));
            assertTrue(row.getBoolean("active"));
            assertEquals(EXPIRES_AT.plus(Duration.ofDays(90)), row.getTimestamp("purge_after").toInstant());
            assertNull(row.getString("notice_version"));
            assertNull(row.getTimestamp("requested_at"));
            assertEquals(UPDATED_AT, row.getTimestamp("updated_at").toInstant());
            assertEquals(EXPIRES_AT, row.getTimestamp("expires_at").toInstant());
            assertTrue(row.next());
            assertEquals("26", row.getString("target_id"));
            assertFalse(row.getBoolean("active"));
            assertEquals(UPDATED_AT.plus(Duration.ofDays(90)), row.getTimestamp("purge_after").toInstant());
            assertNull(row.getString("notice_version"));
            assertNull(row.getTimestamp("requested_at"));
            assertEquals(UPDATED_AT, row.getTimestamp("updated_at").toInstant());
            assertEquals(EXPIRES_AT, row.getTimestamp("expires_at").toInstant());
            assertTrue(row.next());
            assertEquals("27", row.getString("target_id"));
            assertFalse(row.getBoolean("active"));
            assertEquals(LATE_CANCELLED_EXPIRES_AT.plus(Duration.ofDays(90)),
                    row.getTimestamp("purge_after").toInstant());
            assertNull(row.getString("notice_version"));
            assertNull(row.getTimestamp("requested_at"));
            assertEquals(UPDATED_AT, row.getTimestamp("updated_at").toInstant());
            assertEquals(LATE_CANCELLED_EXPIRES_AT, row.getTimestamp("expires_at").toInstant());
            assertFalse(row.next());
        }
    }

    private void assertConsentConstraints(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO analytics_consents(id,user_id,decision,created_at,updated_at)
                VALUES ('%s',1,'UNSET',now(),now())
                """.formatted(CONSENT_ID));
        reject(statement, "23505", """
                INSERT INTO analytics_consents(id,user_id,decision,created_at,updated_at)
                VALUES ('10000000-0000-4000-8000-000000000002',1,'UNSET',now(),now())
                """);
        reject(statement, "23503", "UPDATE analytics_consents SET user_id=999 WHERE user_id=1");
        reject(statement, "23514", "UPDATE analytics_consents SET user_id=NULL");
        reject(statement, "23514", "UPDATE analytics_consents SET guest_token_hash='both-owners'");
        reject(statement, "23514", "UPDATE analytics_consents SET decision='INVALID'");
        reject(statement, "23514", "UPDATE analytics_consents SET decision='GRANTED'");
        reject(statement, "23514", "UPDATE analytics_consents SET revision=-1");
        reject(statement, "23514", """
                UPDATE analytics_consents SET decision='GRANTED',notice_version='notice-v1',
                    scope_version='scope-v1',decided_at=now()
                """);
        reject(statement, "23514", "UPDATE analytics_consents SET user_id=NULL,guest_token_hash='guest'");
    }

    private void assertConsentEventConstraints(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO analytics_consent_events(id,consent_id,command_id,request_fingerprint,
                    previous_decision,decision,previous_revision,revision,notice_version,scope_version,
                    source,recorded_at)
                VALUES ('%s','%s','%s','fixture','UNSET','DENIED',0,1,'notice-v1','scope-v1','FIRST_VISIT',now())
                """.formatted(EVENT_ID, CONSENT_ID, COMMAND_ID));
        reject(statement, "23503", """
                UPDATE analytics_consent_events SET consent_id='10000000-0000-4000-8000-000000000099'
                """);
        reject(statement, "23514", "UPDATE analytics_consent_events SET source='INVALID'");
        reject(statement, "23505", """
                INSERT INTO analytics_consent_events
                SELECT '30000000-0000-4000-8000-000000000002',consent_id,command_id,request_fingerprint,
                    previous_decision,decision,previous_revision,2,previous_notice_version,previous_scope_version,
                    notice_version,scope_version,source,recorded_at,expires_at,superseded_at,purge_after
                FROM analytics_consent_events
                """);
        reject(statement, "23505", """
                INSERT INTO analytics_consent_events
                SELECT '30000000-0000-4000-8000-000000000002',consent_id,
                    '20000000-0000-4000-8000-000000000002',request_fingerprint,
                    previous_decision,decision,previous_revision,revision,
                    previous_notice_version,previous_scope_version,
                    notice_version,scope_version,source,recorded_at,expires_at,superseded_at,purge_after
                FROM analytics_consent_events
                """);
    }

    private void assertDeletionMarkerConstraints(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO user_deletion_markers(login_identifier_hash,deleted_at,expires_at)
                VALUES ('fixture-hash','2026-10-09T00:00:00Z','2026-10-09T00:10:00Z')
                """);
        reject(statement, "23514", "UPDATE user_deletion_markers SET expires_at=deleted_at");
        reject(statement, "23505", "INSERT INTO user_deletion_markers SELECT * FROM user_deletion_markers");
    }

    private void assertMemberReceiptConstraints(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO notification_interest_events(event_id,event_type,source,target_type,target_id,created_at,
                    outcome,user_id,notice_version,settings_revision)
                VALUES ('40000000-0000-4000-8000-000000000002','CONFIRMED','SETTING','REGION','11',now(),
                    'ACTIVATED',1,'notification-2026-10-09-v1',1)
                """);
        reject(statement, "23503", "UPDATE notification_interest_events SET user_id=999 WHERE user_id=1");
        reject(statement, "23514", "UPDATE notification_interest_events SET notice_version=NULL WHERE user_id=1");
        reject(statement, "23514", "UPDATE notification_interest_events SET settings_revision=NULL WHERE user_id=1");
        reject(statement, "23514", "UPDATE notification_interest_events SET event_type='CLICKED' WHERE user_id=1");
        reject(statement, "23514", """
                UPDATE notification_interest_events SET session_id='50000000-0000-4000-8000-000000000001'
                WHERE user_id=1
                """);
    }

    private void assertPrivacyIndexes(Statement statement) throws SQLException {
        for (String index : List.of("analytics_consents_guest_expiry_idx", "analytics_consent_events_purge_idx",
                "idx_notification_subscriptions_purge_after", "idx_notification_interest_events_retention",
                "idx_notification_interest_events_user", "idx_user_deletion_markers_expiry")) {
            try (var row = statement.executeQuery("SELECT to_regclass('public." + index + "') IS NOT NULL")) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1), index);
            }
        }
    }

    private long count(Statement statement, String table) throws SQLException {
        try (var row = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private void reject(Statement statement, String state, String query) {
        SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(query));
        assertEquals(state, failure.getSQLState());
    }

    private record TestDatabase(String name) implements AutoCloseable {

        private static TestDatabase create() throws SQLException {
            TestDatabase database = new TestDatabase("privacy_schema_" + UUID.randomUUID().toString().replace("-", ""));
            try (Connection connection = connectTo("postgres"); Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database.name());
            }
            return database;
        }

        private Flyway migration(String version) {
            return migration(version, "UTC");
        }

        private Flyway migration(String version, String timezone) {
            return Flyway.configure().dataSource(url(name), "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").target(version)
                    .initSql("SET TIME ZONE '" + timezone + "'").load();
        }

        private Connection connect() throws SQLException {
            return connectTo(name);
        }

        private static Connection connectTo(String database) throws SQLException {
            return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
        }

        private static String url(String database) {
            String port = System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432");
            return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = connectTo("postgres"); Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + name + " WITH (FORCE)");
            }
        }
    }
}
