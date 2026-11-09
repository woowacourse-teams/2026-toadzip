package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class BoardRefinementStepMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 기존_이력을_보존하면서_모든_단계_저장소에_두_기관_수집_정제를_허용한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA board_refinement_migration_test");
            statement.execute("SET search_path TO board_refinement_migration_test");
            try {
                statement.execute("CREATE TABLE data_pipeline_executions (type text, current_step text, failed_step text)");
                statement.execute("CREATE TABLE data_pipeline_execution_completed_steps (completed_step text)");
                statement.execute("CREATE TABLE data_pipeline_execution_skipped_steps (skipped_step text)");
                statement.execute("CREATE TABLE data_pipeline_execution_partial_failures (partially_failed_step text)");
                statement.execute("CREATE TABLE external_data_collection_failures (source text)");
                migrate(connection, "V20261104_01__gh_announcement_collection.sql");
                insert(connection, "data_pipeline_executions", "current_step", "COLLECT_GH_ANNOUNCEMENTS");
                assertThatThrownBy(() -> insert(connection, "data_pipeline_executions", "current_step",
                        "MAP_GH_ANNOUNCEMENTS")).isInstanceOf(SQLException.class);

                migrate(connection, "V20261104_06__board_announcement_refinement_steps.sql");
                migrate(connection, "V20261107_01__board_announcement_collect_refine_steps.sql");

                for (String column : List.of("current_step", "failed_step")) {
                    verifySteps(connection, "data_pipeline_executions", column);
                }
                verifySteps(connection, "data_pipeline_execution_completed_steps", "completed_step");
                verifySteps(connection, "data_pipeline_execution_skipped_steps", "skipped_step");
                verifySteps(connection, "data_pipeline_execution_partial_failures", "partially_failed_step");
                try (var rows = statement.executeQuery("SELECT count(*) FROM data_pipeline_executions "
                        + "WHERE current_step = 'COLLECT_GH_ANNOUNCEMENTS'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA board_refinement_migration_test CASCADE");
            }
        }
    }

    private void verifySteps(Connection connection, String table, String column) throws SQLException {
        for (DataPipelineStep step : DataPipelineStep.values()) {
            insert(connection, table, column, step.name());
        }
        assertThatThrownBy(() -> insert(connection, table, column, "UNKNOWN"))
                .isInstanceOf(SQLException.class);
    }

    private void insert(Connection connection, String table, String column, String step) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO " + table + " (" + column + ") VALUES (?)")) {
            statement.setString(1, step);
            statement.executeUpdate();
        }
    }

    private void migrate(Connection connection, String file) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + file));
    }
}
