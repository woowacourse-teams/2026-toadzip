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
class DataPipelineExecutionMigrationTest {

    private static final String SCHEMA = "data_pipeline_execution_migration_test";
    private static final String MIGRATION =
            "db/migration/V20260903_01__create_data_pipeline_executions.sql";

    @Autowired
    private DataSource dataSource;

    @Test
    void 실행과_완료_단계_테이블을_생성한다() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            prepareSchema(connection);
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));

                assertThat(tableExists(connection, "data_pipeline_executions")).isTrue();
                assertThat(tableExists(connection, "data_pipeline_execution_completed_steps"))
                        .isTrue();
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

    private void dropTestSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }
}
