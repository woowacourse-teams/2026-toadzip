package com.toadzip.backend.privacy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.privacy.configuration.PrivacyMigrationConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PrivacySchemaMigrationTest {

    private static final List<String> PRIVACY_TABLES = List.of("privacy_analytics_consents",
            "privacy_analytics_consent_events", "privacy_registration_notices", "privacy_notification_states",
            "privacy_notification_notices", "privacy_notification_receipts", "privacy_notification_events",
            "privacy_flyway_schema_history");
    private final PrivacyMigrationConfiguration configuration = new PrivacyMigrationConfiguration();

    @Test
    void 기존_모든_테이블의_컬럼_제약_인덱스_데이터를_그대로_두고_신규_테이블만_추가한다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.applicationMigration().migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                seedLegacyData(statement);
            }
            SchemaSnapshot before = snapshot(database, false);
            verifySchemaGate(database, database.name(), "before");
            assertSchemaGateRejected(database, database.name(), "after");

            configuration.migrate(database.applicationMigration());

            assertEquals(before, snapshot(database, false));
            verifySchemaGate(database, database.name(), "after");
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                assertEquals(PRIVACY_TABLES.stream().sorted().toList(), queryStrings(statement, """
                        SELECT tablename FROM pg_tables WHERE schemaname='public'
                        AND starts_with(tablename, 'privacy_') ORDER BY tablename
                        """));
                for (String table : PRIVACY_TABLES.subList(0, 7)) {
                    assertEquals(List.of("0"), queryStrings(statement, "SELECT count(*) FROM " + table));
                }
                assertEquals(List.of("0:BASELINE", "1:SQL"), queryStrings(statement, """
                        SELECT version || ':' || type FROM privacy_flyway_schema_history ORDER BY installed_rank
                        """));
            }
            SchemaSnapshot firstMigration = snapshot(database, true);
            configuration.migrate(database.applicationMigration());
            assertEquals(firstMigration, snapshot(database, true));
        }
    }

    @Test
    void 빈_DB도_기존_스키마를_먼저_만들고_독립_개인정보_이력을_생성한다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            verifySchemaGate(database, database.name(), "before");
            configuration.migrate(database.applicationMigration());
            verifySchemaGate(database, database.name(), "after");
            database.applicationMigration().validate();
            configuration.privacyFlyway(database.applicationMigration().getConfiguration().getDataSource()).validate();
            assertSchemaGateRejected(database, "different_database", "after");
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("DROP INDEX privacy_analytics_consent_events_purge_idx");
            }
            assertSchemaGateRejected(database, database.name(), "after");
        }
    }

    @Test
    void 이미_폐기된_SQL이_적용된_DB는_어느_마이그레이션도_시작하지_않고_차단한다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            database.applicationMigration().migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                seedLegacyData(statement);
            }
            Flyway.configure().dataSource(database.applicationMigration().getConfiguration().getDataSource())
                    .locations("classpath:db/migration", "classpath:db/archive/privacy-legacy")
                    .load().migrate();
            SchemaSnapshot appliedLegacy = snapshot(database, true);

            assertThrows(IllegalStateException.class, () -> configuration.migrate(database.applicationMigration()));
            assertSchemaGateRejected(database, database.name(), "before");
            assertEquals(appliedLegacy, snapshot(database, true));
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                assertEquals(List.of("0"), queryStrings(statement, """
                        SELECT count(*) FROM pg_tables WHERE schemaname='public'
                        AND tablename='privacy_flyway_schema_history'
                        """));
            }
        }
    }

    @Test
    void 이력없는_DB와_수동변경흔적은_자동_baseline이나_복구로_우회하지_않는다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE legacy_fixture(id BIGINT)");
            }
            assertThrows(IllegalStateException.class, () -> configuration.migrate(database.applicationMigration()));
            assertSchemaGateRejected(database, database.name(), "before");
        }
        try (TestDatabase database = TestDatabase.create()) {
            database.applicationMigration().migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN registration_policy_version VARCHAR(100)");
            }
            assertRejectedWithoutMutation(database);
        }
        try (TestDatabase database = TestDatabase.create()) {
            database.applicationMigration().migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE notification_interest_events ALTER COLUMN session_id DROP NOT NULL");
            }
            assertRejectedWithoutMutation(database);
        }
        try (TestDatabase database = TestDatabase.create()) {
            database.applicationMigration().migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE privacy_analytics_consents(id UUID)");
            }
            assertRejectedWithoutMutation(database);
        }
    }

    @Test
    void 신규_테이블의_참조와_삭제연쇄는_신규_테이블_안에만_존재한다() throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            configuration.migrate(database.applicationMigration());
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                seedLegacyData(statement);
                SchemaSnapshot legacy = snapshot(database, false);
                statement.executeUpdate("""
                        INSERT INTO privacy_analytics_consents(id,user_id,decision,created_at,updated_at)
                        VALUES ('10000000-0000-4000-8000-000000000001',999,'UNSET',now(),now())
                        """);
                reject(statement, "23505", """
                        INSERT INTO privacy_analytics_consents(id,user_id,decision,created_at,updated_at)
                        VALUES ('10000000-0000-4000-8000-000000000002',999,'UNSET',now(),now())
                        """);
                reject(statement, "23514", "UPDATE privacy_analytics_consents SET user_id=NULL");
                reject(statement, "23514", "UPDATE privacy_analytics_consents SET decision='GRANTED'");
                reject(statement, "23514", "UPDATE privacy_analytics_consents SET revision=-1");
                statement.executeUpdate("""
                        INSERT INTO privacy_analytics_consent_events(id,consent_id,command_id,request_fingerprint,
                            previous_decision,decision,previous_revision,revision,notice_version,scope_version,
                            source,recorded_at)
                        VALUES ('30000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001',
                            '20000000-0000-4000-8000-000000000001','fixture','UNSET','DENIED',0,1,
                            'notice-v1','scope-v1','FIRST_VISIT',now())
                        """);
                reject(statement, "23503", """
                        UPDATE privacy_analytics_consent_events SET consent_id='10000000-0000-4000-8000-000000000099'
                        """);
                statement.executeUpdate("DELETE FROM privacy_analytics_consents");
                assertEquals(List.of("0"), queryStrings(statement, "SELECT count(*) FROM privacy_analytics_consent_events"));
                assertEquals(legacy, snapshot(database, false));
                verifySchemaGate(database, database.name(), "after");
                statement.execute("ALTER TABLE privacy_registration_notices ADD FOREIGN KEY(user_id) REFERENCES users(id)");
                assertSchemaGateRejected(database, database.name(), "after");
            }
        }
    }

    @Test
    void 적용된_원본_SQL은_해시를_유지하며_정상_마이그레이션_검색경로에서_제외한다() throws Exception {
        Path archive = Path.of("src/main/resources/db/archive/privacy-legacy");
        for (String line : Files.readAllLines(archive.resolve("SHA256SUMS"))) {
            String[] fields = line.split("  ", 2);
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive.resolve(fields[1])));
            assertEquals(fields[0], HexFormat.of().formatHex(actual));
            assertFalse(Files.exists(Path.of("src/main/resources/db/migration").resolve(fields[1])));
        }
        assertEquals(4, Files.readAllLines(archive.resolve("SHA256SUMS")).size());
    }

    private void assertRejectedWithoutMutation(TestDatabase database) throws Exception {
        SchemaSnapshot before = snapshot(database, true);
        assertThrows(IllegalStateException.class, () -> configuration.migrate(database.applicationMigration()));
        assertSchemaGateRejected(database, database.name(), "before");
        assertEquals(before, snapshot(database, true));
    }

    private SchemaSnapshot snapshot(TestDatabase database, boolean includePrivacy) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            List<String> tables = queryStrings(statement, """
                    SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename
                    """).stream().filter(name -> includePrivacy || !name.startsWith("privacy_")).toList();
            Map<String, List<String>> schema = new LinkedHashMap<>();
            Map<String, List<String>> rows = new LinkedHashMap<>();
            for (String table : tables) {
                schema.put(table + ":columns", queryStrings(statement, """
                        SELECT concat_ws('|', attnum, attname, format_type(atttypid, atttypmod), attnotnull,
                            attidentity, attgenerated, coalesce(pg_get_expr(d.adbin,d.adrelid),''))
                        FROM pg_attribute a LEFT JOIN pg_attrdef d ON a.attrelid=d.adrelid AND a.attnum=d.adnum
                        WHERE a.attrelid='public.%s'::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum
                        """.formatted(table)));
                schema.put(table + ":constraints", queryStrings(statement, """
                        SELECT conname || '|' || pg_get_constraintdef(oid) || '|' || convalidated
                        FROM pg_constraint WHERE conrelid='public.%s'::regclass ORDER BY conname
                        """.formatted(table)));
                schema.put(table + ":indexes", queryStrings(statement, """
                        SELECT indexname || '|' || indexdef FROM pg_indexes
                        WHERE schemaname='public' AND tablename='%s' ORDER BY indexname
                        """.formatted(table)));
                schema.put(table + ":triggers", queryStrings(statement, """
                        SELECT tgname || '|' || pg_get_triggerdef(oid) FROM pg_trigger
                        WHERE tgrelid='public.%s'::regclass ORDER BY tgname
                        """.formatted(table)));
                rows.put(table, queryStrings(statement,
                        "SELECT to_jsonb(row)::text FROM public.\"" + table + "\" row ORDER BY to_jsonb(row)::text"));
            }
            return new SchemaSnapshot(schema, rows);
        }
    }

    private List<String> queryStrings(Statement statement, String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private void verifySchemaGate(TestDatabase database, String expectedDatabase, String phase) throws Exception {
        String preflight = Files.readString(Path.of("src/main/resources/privacy/schema-preflight.sql"));
        String query = Files.readString(Path.of("..", "scripts", "check-privacy-schema.sql"))
                .replace("\\ir ../backend/src/main/resources/privacy/schema-preflight.sql", preflight)
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

    private void reject(Statement statement, String state, String query) {
        SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(query));
        assertEquals(state, failure.getSQLState());
    }

    private record SchemaSnapshot(Map<String, List<String>> schema, Map<String, List<String>> rows) {
    }

    private record TestDatabase(String name) implements AutoCloseable {

        private static TestDatabase create() throws SQLException {
            TestDatabase database = new TestDatabase("privacy_schema_" + UUID.randomUUID().toString().replace("-", ""));
            try (Connection connection = connectTo("postgres"); Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database.name());
            }
            return database;
        }

        private Flyway applicationMigration() {
            return Flyway.configure().dataSource(url(name), "toadzip_test", "toadzip_test")
                    .locations("classpath:db/migration").load();
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
