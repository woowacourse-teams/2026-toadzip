package com.toadzip.backend.ingest.failure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.MigrationSqlSection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IngestFailureLifecycleMigrationTest {

    private static final String SCHEMA = "ingest_failure_lifecycle_migration_test";
    private static final String FAILURE_LIFECYCLE_MIGRATION =
            "V20260919_03__add_ingest_failure_lifecycle.sql";
    private static final String EXTERNAL_FAILURE_LIFECYCLE_MIGRATION =
            "V20260919_04__add_external_failure_lifecycle.sql";
    private static final String HOUSEHOLD_FAILURE_MIGRATION =
            "V20260919_05__create_lh_household_enrichment_failures.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_실패를_현재_이력으로_이관하고_세대수_실패_테이블을_생성한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            prepareSchema(connection);
            try {
                createExistingFailureTables(connection);
                insertExistingFailures(connection);

                execute(connection, FAILURE_LIFECYCLE_MIGRATION);
                execute(connection, EXTERNAL_FAILURE_LIFECYCLE_MIGRATION);
                execute(connection, HOUSEHOLD_FAILURE_MIGRATION);
                insertLegacyFailuresAfterMigration(connection);
                execute(connection, FAILURE_LIFECYCLE_MIGRATION);
                execute(connection, EXTERNAL_FAILURE_LIFECYCLE_MIGRATION);
                execute(connection, HOUSEHOLD_FAILURE_MIGRATION);

                assertThat(lifecycleBackfilled(
                        connection,
                        "myhome_complex_mapping_failures"
                )).isTrue();
                assertThat(lifecycleBackfilled(
                        connection,
                        "myhome_announcement_mapping_failures"
                )).isTrue();
                assertThat(lifecycleBackfilled(
                        connection,
                        "lh_announcement_enrichment_failures"
                )).isTrue();
                assertThat(externalLifecycleBackfilled(connection)).isTrue();
                assertThat(legacyInsertsHaveLifecycleDefaults(connection)).isTrue();
                assertThat(tableExists(
                        connection,
                        "lh_household_enrichment_failures"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_myhome_complex_mapping_failures_status_source"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_myhome_complex_mapping_failures_source_reason"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_myhome_announcement_mapping_failures_status_source"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_myhome_announcement_mapping_failures_source_reason"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_lh_announcement_enrichment_failures_status_source"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_lh_announcement_enrichment_failures_source_reason"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_lh_household_enrichment_failures_status_source"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "idx_lh_household_enrichment_failures_source_reason"
                )).isTrue();
            }
            finally {
                dropTestSchema(connection);
            }
        }
    }

    private void prepareSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
        }
    }

    private void createExistingFailureTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE myhome_complex_mapping_failures (
                        id BIGSERIAL PRIMARY KEY,
                        source_key VARCHAR(500) NOT NULL DEFAULT 'source',
                        reason VARCHAR(40) NOT NULL DEFAULT 'INVALID_VALUE',
                        occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE myhome_announcement_mapping_failures (
                        id BIGSERIAL PRIMARY KEY,
                        source_key VARCHAR(500) NOT NULL DEFAULT 'source',
                        reason VARCHAR(50) NOT NULL DEFAULT 'MISSING_REQUIRED_VALUE',
                        occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE lh_announcement_enrichment_failures (
                        id BIGSERIAL PRIMARY KEY,
                        source_key VARCHAR(500) NOT NULL DEFAULT 'source',
                        reason VARCHAR(50) NOT NULL DEFAULT 'MISSING_REQUIRED_VALUE',
                        occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE external_data_collection_failures (
                        id BIGSERIAL PRIMARY KEY,
                        occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
        }
    }

    private void insertExistingFailures(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "myhome_complex_mapping_failures",
                    "myhome_announcement_mapping_failures",
                    "lh_announcement_enrichment_failures",
                    "external_data_collection_failures"
            }) {
                statement.execute("INSERT INTO " + table
                        + " (occurred_at) VALUES ('2026-09-19T00:00:00Z')");
            }
        }
    }

    private void insertLegacyFailuresAfterMigration(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "myhome_complex_mapping_failures",
                    "myhome_announcement_mapping_failures",
                    "lh_announcement_enrichment_failures",
                    "external_data_collection_failures"
            }) {
                statement.execute("INSERT INTO " + table
                        + " (occurred_at) VALUES ('2026-09-19T01:00:00Z')");
            }
        }
    }

    private void execute(Connection connection, String migration) {
        ScriptUtils.executeSqlScript(connection, MigrationSqlSection.resource(migration));
    }

    private boolean lifecycleBackfilled(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT last_occurred_at = occurred_at"
                             + " AND occurrence_count = 1"
                             + " AND recurrence_count = 0"
                             + " AND status = 'PENDING' FROM " + table
                             + " WHERE occurred_at = '2026-09-19T00:00:00Z'"
             )) {
            return result.next() && result.getBoolean(1);
        }
    }

    private boolean externalLifecycleBackfilled(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT last_occurred_at = occurred_at
                        AND occurrence_count = 1
                        AND recurrence_count = 0
                     FROM external_data_collection_failures
                     WHERE occurred_at = '2026-09-19T00:00:00Z'
                     """)) {
            return result.next() && result.getBoolean(1);
        }
    }

    private boolean legacyInsertsHaveLifecycleDefaults(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) = 4
                     FROM (
                         SELECT last_occurred_at FROM myhome_complex_mapping_failures
                         WHERE occurred_at = '2026-09-19T01:00:00Z'
                         UNION ALL
                         SELECT last_occurred_at FROM myhome_announcement_mapping_failures
                         WHERE occurred_at = '2026-09-19T01:00:00Z'
                         UNION ALL
                         SELECT last_occurred_at FROM lh_announcement_enrichment_failures
                         WHERE occurred_at = '2026-09-19T01:00:00Z'
                         UNION ALL
                         SELECT last_occurred_at FROM external_data_collection_failures
                         WHERE occurred_at = '2026-09-19T01:00:00Z'
                     ) legacy_rows
                     WHERE last_occurred_at IS NOT NULL
                     """)) {
            return result.next() && result.getBoolean(1);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = current_schema() AND table_name = ?
                )
                """)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_indexes
                    WHERE schemaname = current_schema() AND indexname = ?
                )
                """)) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void dropTestSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
