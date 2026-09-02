package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MyHomeAnnouncementLifecycleMigrationTest {

    private static final String SCHEMA = "myhome_announcement_lifecycle_migration_test";

    private static final String MIGRATION =
            "db/migration/V20260902_01__add_myhome_announcement_source_lifecycle.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_원천_테이블에_수명주기_컬럼을_추가하고_기본값을_backfill한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            prepareLegacySchema(connection);
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));

                assertLifecycleValues(connection);
                assertLifecycleConstraints(connection);
            }
            finally {
                dropTestSchema(connection);
            }
        }
    }

    private void prepareLegacySchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
            statement.execute("""
                    CREATE TABLE myhome_announcement_source (
                        id BIGSERIAL PRIMARY KEY,
                        source_key VARCHAR(500) NOT NULL,
                        collected_at TIMESTAMPTZ
                    )
                    """);
            statement.execute("""
                    INSERT INTO myhome_announcement_source (source_key, collected_at)
                    VALUES ('legacy-source', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                    """);
        }
    }

    private void assertLifecycleValues(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT last_seen_run_id, consecutive_miss_count, active
                        FROM myhome_announcement_source
                        WHERE source_key = 'legacy-source'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("last_seen_run_id")).isNull();
            assertThat(result.getInt("consecutive_miss_count")).isZero();
            assertThat(result.getBoolean("active")).isTrue();
        }
    }

    private void assertLifecycleConstraints(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT column_name, is_nullable, column_default
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'myhome_announcement_source'
                          AND column_name IN ('consecutive_miss_count', 'active')
                        ORDER BY column_name
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("column_name")).isEqualTo("active");
            assertThat(result.getString("is_nullable")).isEqualTo("NO");
            assertThat(result.getString("column_default")).isEqualTo("true");
            assertThat(result.next()).isTrue();
            assertThat(result.getString("column_name")).isEqualTo("consecutive_miss_count");
            assertThat(result.getString("is_nullable")).isEqualTo("NO");
            assertThat(result.getString("column_default")).isEqualTo("0");
        }
    }

    private void dropTestSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
