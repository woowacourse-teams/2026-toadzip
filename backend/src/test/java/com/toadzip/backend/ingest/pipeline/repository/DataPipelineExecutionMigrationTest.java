package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DataPipelineExecutionMigrationTest {

    private static final String SCHEMA = "data_pipeline_execution_migration_test";
    private static final UUID LEGACY_EXECUTION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID POST_MIGRATION_LEGACY_EXECUTION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002"
    );
    private static final String MIGRATION =
            "db/migration/V20260903_01__create_data_pipeline_executions.sql";
    private static final String SKIPPED_STEPS_MIGRATION =
            "db/migration/V20260903_02__add_data_pipeline_skipped_steps.sql";
    private static final String COMPLETED_STEP_REPORTS_MIGRATION =
            "db/migration/V20260919_01__add_data_pipeline_completed_step_reports.sql";
    private static final String PARTIAL_FAILURE_REPORTS_MIGRATION =
            "db/migration/V20260919_02__add_data_pipeline_partial_failure_reports.sql";
    private static final String SCHEDULE_METADATA_MIGRATION =
            "db/migration/V20260921_01__add_data_pipeline_schedule_metadata.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 실행과_완료_단계_테이블을_생성한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            prepareSchema(connection);
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(SKIPPED_STEPS_MIGRATION)
                );
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(COMPLETED_STEP_REPORTS_MIGRATION)
                );
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(PARTIAL_FAILURE_REPORTS_MIGRATION)
                );
                insertLegacyExecution(connection);
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(SCHEDULE_METADATA_MIGRATION)
                );
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(COMPLETED_STEP_REPORTS_MIGRATION)
                );
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(PARTIAL_FAILURE_REPORTS_MIGRATION)
                );
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(SCHEDULE_METADATA_MIGRATION)
                );
                insertLegacyExecution(connection, POST_MIGRATION_LEGACY_EXECUTION_ID);

                assertThat(tableExists(connection, "data_pipeline_executions")).isTrue();
                assertThat(tableExists(connection, "data_pipeline_execution_completed_steps"))
                        .isTrue();
                assertThat(tableExists(connection, "data_pipeline_execution_skipped_steps"))
                        .isTrue();
                assertThat(tableExists(
                        connection,
                        "data_pipeline_execution_partial_failures"
                )).isTrue();
                assertThat(columnLength(connection, "data_pipeline_executions", "type"))
                        .isEqualTo(40);
                assertThat(columnExists(
                        connection,
                        "data_pipeline_execution_completed_steps",
                        "completed_report"
                )).isTrue();
                assertThat(columnExists(
                        connection,
                        "data_pipeline_executions",
                        "execution_trigger"
                )).isTrue();
                assertThat(columnExists(
                        connection,
                        "data_pipeline_executions",
                        "scheduled_at"
                )).isTrue();
                assertThat(columnExists(
                        connection,
                        "data_pipeline_executions",
                        "upstream_execution_id"
                )).isTrue();
                assertThat(executionTrigger(connection, LEGACY_EXECUTION_ID)).isEqualTo("MANUAL");
                assertThat(executionTrigger(connection, POST_MIGRATION_LEGACY_EXECUTION_ID))
                        .isEqualTo("MANUAL");
                assertThat(indexExists(
                        connection,
                        "idx_data_pipeline_execution_upstream"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "ux_data_pipeline_execution_type_scheduled_at"
                )).isTrue();
                assertThat(indexExists(
                        connection,
                        "ux_data_pipeline_execution_type_upstream"
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

    private int columnLength(
            Connection connection,
            String tableName,
            String columnName
    ) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("컬럼을 찾지 못했습니다.");
                }
                return result.getInt(1);
            }
        }
    }

    private boolean columnExists(
            Connection connection,
            String tableName,
            String columnName
    ) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = ?
                      AND column_name = ?
                )
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void insertLegacyExecution(Connection connection) throws Exception {
        insertLegacyExecution(connection, LEGACY_EXECUTION_ID);
    }

    private void insertLegacyExecution(Connection connection, UUID executionId) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO data_pipeline_executions (
                    execution_id, type, status, started_at, heartbeat_at
                ) VALUES (?, 'ANNOUNCEMENT_COLLECTION', 'RUNNING', ?, ?)
                """)) {
            statement.setObject(1, executionId);
            Timestamp startedAt = Timestamp.from(Instant.parse(
                    "2026-09-21T00:00:00Z"
            ));
            statement.setTimestamp(2, startedAt);
            statement.setTimestamp(3, startedAt);
            statement.executeUpdate();
        }
    }

    private String executionTrigger(Connection connection, UUID executionId) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT execution_trigger
                FROM data_pipeline_executions
                WHERE execution_id = ?
                """)) {
            statement.setObject(1, executionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("기존 실행을 찾지 못했습니다.");
                }
                return result.getString(1);
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
