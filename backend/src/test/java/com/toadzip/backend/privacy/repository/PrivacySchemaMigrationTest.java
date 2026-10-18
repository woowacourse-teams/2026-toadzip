package com.toadzip.backend.privacy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                seedReviewedComplex(statement);
                assertEquals(List.of("V20261009_01__create_housing_complex_reviews.sql"), queryStrings(statement,
                        "SELECT script FROM flyway_schema_history WHERE version='20261009.01' AND success"));
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
    void 이미_폐기된_SQL이_적용된_DB는_어느_마이그레이션도_시작하지_않고_차단한다(@TempDir Path migrations)
            throws Exception {
        copyLegacyMigrations(migrations);
        try (TestDatabase database = TestDatabase.create()) {
            Flyway.configure().dataSource(database.applicationMigration().getConfiguration().getDataSource())
                    .locations("filesystem:" + migrations).target("20261008.04").load().migrate();
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                seedLegacyData(statement);
            }
            Flyway.configure().dataSource(database.applicationMigration().getConfiguration().getDataSource())
                    .locations("filesystem:" + migrations).load().migrate();
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

    @Test
    void 구형_개인정보_이력만_남아도_기존_DB를_변경하지_않고_차단한다() throws Exception {
        Path archive = Path.of("src/main/resources/db/archive/privacy-legacy");
        for (String line : Files.readAllLines(archive.resolve("SHA256SUMS"))) {
            String script = line.split("  ", 2)[1];
            try (TestDatabase database = TestDatabase.create()) {
                database.applicationMigration().migrate();
                try (Connection connection = database.connect();
                        var insert = connection.prepareStatement("""
                                INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,
                                    installed_by,execution_time,success)
                                SELECT max(installed_rank)+1,'20261009.02','legacy privacy fixture','SQL',?,
                                    current_user,0,true FROM flyway_schema_history
                                """)) {
                    insert.setString(1, script);
                    insert.executeUpdate();
                }
                SchemaSnapshot before = snapshot(database, true);
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> configuration.migrate(database.applicationMigration()));
                assertTrue(failure.getCause().getMessage().contains("Legacy privacy migrations were applied"));
                assertSchemaGateRejected(database, database.name(), "before");
                assertEquals(before, snapshot(database, true));
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "columns|ALTER TABLE snapshot_fixture ALTER COLUMN note SET DEFAULT 'changed'",
            "constraints|ALTER TABLE snapshot_fixture ADD CONSTRAINT positive_id CHECK (id > 0)",
            "indexes|CREATE INDEX note_idx ON snapshot_fixture (note)",
            "triggers|CREATE TRIGGER write_guard BEFORE INSERT ON snapshot_fixture "
                    + "FOR EACH ROW EXECUTE FUNCTION keep_row()",
            "rows|UPDATE snapshot_fixture SET note = 'changed' WHERE id = 1"
    }, delimiter = '|')
    void 스냅샷은_검사대상_테이블의_메타데이터와_행_변경을_감지한다(String category, String change) throws Exception {
        try (TestDatabase database = TestDatabase.create()) {
            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE snapshot_fixture(id BIGINT PRIMARY KEY, note TEXT DEFAULT 'original')");
                statement.execute("INSERT INTO snapshot_fixture(id) VALUES (1)");
                statement.execute("CREATE TABLE empty_fixture(id BIGINT)");
                statement.execute("CREATE TABLE privacy_fixture(id BIGINT)");
                statement.execute("CREATE SCHEMA unrelated");
                statement.execute("CREATE TABLE unrelated.snapshot_fixture(other_column TEXT)");
                statement.execute("CREATE FUNCTION keep_row() RETURNS trigger LANGUAGE plpgsql "
                        + "AS $$ BEGIN RETURN NEW; END $$");
            }
            SchemaSnapshot before = snapshot(database, false);
            assertEquals(Set.of("snapshot_fixture", "empty_fixture"), before.rows().keySet());
            assertEquals(8, before.schema().size());
            assertEquals(List.of(), before.rows().get("empty_fixture"));
            assertEquals(List.of(), before.schema().get("empty_fixture:constraints"));
            assertEquals(List.of(), before.schema().get("empty_fixture:indexes"));
            assertEquals(List.of(), before.schema().get("empty_fixture:triggers"));
            assertEquals(2, before.schema().get("snapshot_fixture:columns").size());
            SchemaSnapshot includingPrivacy = snapshot(database, true);
            assertEquals(Set.of("snapshot_fixture", "empty_fixture", "privacy_fixture"),
                    includingPrivacy.rows().keySet());
            assertEquals(1, includingPrivacy.schema().get("privacy_fixture:columns").size());

            try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
                statement.execute(change);
            }
            SchemaSnapshot after = snapshot(database, false);

            if (category.equals("rows")) {
                assertNotEquals(before.rows().get("snapshot_fixture"), after.rows().get("snapshot_fixture"));
                return;
            }
            assertNotEquals(before.schema().get("snapshot_fixture:" + category),
                    after.schema().get("snapshot_fixture:" + category));
        }
    }

    private void copyLegacyMigrations(Path directory) throws Exception {
        try (var paths = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().compareTo("V20261009") < 0) {
                    Files.copy(path, directory.resolve(path.getFileName()));
                }
            }
        }
        try (var paths = Files.list(Path.of("src/main/resources/db/archive/privacy-legacy"))) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".sql")).toList()) {
                Files.copy(path, directory.resolve(path.getFileName()));
            }
        }
    }

    private void seedReviewedComplex(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO housing_complexes(id,name,source_complex_identifier,city_county_district_code,
                    latitude,legal_dong_code,longitude,pnu,province_code,road_address,parking_space_count,
                    provider,supply_type,total_household_count,version,admin_modified)
                VALUES (1,'검토된 단지','manual:privacy-migration','11140',37.5,'1114010100',127,
                    '1114010100100010000','11','기존 주소',30,'LH','HAPPY_HOUSING',100,7,true)
                """);
        statement.executeUpdate("""
                INSERT INTO housing_complex_reviews(housing_complex_id,outcome,checked_values,evidence_note,
                    actor,reviewed_at)
                VALUES (1,'VERIFIED','{"NAME":"검토된 단지"}','기존 검토 근거','fixture',CURRENT_TIMESTAMP)
                """);
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
                for (String category : List.of("columns", "constraints", "indexes", "triggers")) {
                    schema.put(table + ":" + category, new ArrayList<>());
                }
                rows.put(table, queryStrings(statement,
                        "SELECT to_jsonb(row)::text FROM public.\"" + table + "\" row ORDER BY to_jsonb(row)::text"));
            }
            collectMetadata(statement, schema, "columns", """
                    SELECT r.relname,
                        concat_ws('|', a.attnum, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                            a.attidentity, a.attgenerated, coalesce(pg_get_expr(d.adbin,d.adrelid),''))
                    FROM pg_attribute a
                    JOIN pg_class r ON r.oid=a.attrelid
                    JOIN pg_namespace n ON n.oid=r.relnamespace
                    LEFT JOIN pg_attrdef d ON a.attrelid=d.adrelid AND a.attnum=d.adnum
                    WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped
                    ORDER BY r.relname, a.attnum
                    """);
            collectMetadata(statement, schema, "constraints", """
                    SELECT r.relname, c.conname || '|' || pg_get_constraintdef(c.oid) || '|' || c.convalidated
                    FROM pg_constraint c
                    JOIN pg_class r ON r.oid=c.conrelid
                    JOIN pg_namespace n ON n.oid=r.relnamespace
                    WHERE n.nspname='public'
                    ORDER BY r.relname, c.conname
                    """);
            collectMetadata(statement, schema, "indexes", """
                    SELECT tablename, indexname || '|' || indexdef FROM pg_indexes
                    WHERE schemaname='public' ORDER BY tablename, indexname
                    """);
            collectMetadata(statement, schema, "triggers", """
                    SELECT r.relname, t.tgname || '|' || pg_get_triggerdef(t.oid)
                    FROM pg_trigger t
                    JOIN pg_class r ON r.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=r.relnamespace
                    WHERE n.nspname='public'
                    ORDER BY r.relname, t.tgname
                    """);
            return new SchemaSnapshot(schema, rows);
        }
    }

    private void collectMetadata(
            Statement statement, Map<String, List<String>> schema, String category, String query
    ) throws SQLException {
        try (var result = statement.executeQuery(query)) {
            while (result.next()) {
                List<String> values = schema.get(result.getString(1) + ":" + category);
                if (values != null) {
                    values.add(result.getString(2));
                }
            }
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
