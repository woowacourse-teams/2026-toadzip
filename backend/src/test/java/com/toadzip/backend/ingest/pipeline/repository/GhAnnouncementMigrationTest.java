package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
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
class GhAnnouncementMigrationTest {

    private static final List<String> STEP_TABLES = List.of("data_pipeline_execution_completed_steps",
            "data_pipeline_execution_skipped_steps", "data_pipeline_execution_partial_failures");
    private static final List<String> STEP_COLUMNS = List.of("completed_step", "skipped_step", "partially_failed_step");

    @Autowired
    private DataSource dataSource;

    @Test
    void 마이그레이션은_기존_값을_보존하고_GH_유형_단계_실패_원천을_허용한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA gh_migration_test");
            statement.execute("SET search_path TO gh_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE data_pipeline_executions (
                            type varchar(40), current_step varchar(80), failed_step varchar(80),
                            CONSTRAINT data_pipeline_executions_type_check CHECK(type = 'SH_ANNOUNCEMENT_COLLECTION'),
                            CONSTRAINT data_pipeline_executions_current_step_check
                                CHECK(current_step = 'MAP_LH_ANNOUNCEMENTS'),
                            CONSTRAINT data_pipeline_executions_failed_step_check
                                CHECK(failed_step = 'MAP_LH_ANNOUNCEMENTS')
                        )
                        """);
                for (int index = 0; index < STEP_TABLES.size(); index++) {
                    statement.execute("CREATE TABLE " + STEP_TABLES.get(index)
                            + " (" + STEP_COLUMNS.get(index) + " varchar(80))");
                }
                statement.execute("""
                        CREATE TABLE external_data_collection_failures (source varchar(80),
                        CONSTRAINT external_data_collection_failures_source_check CHECK(source = 'SH_ANNOUNCEMENT'))
                        """);
                statement.execute("""
                        INSERT INTO data_pipeline_executions VALUES
                        ('SH_ANNOUNCEMENT_COLLECTION', 'MAP_LH_ANNOUNCEMENTS', 'MAP_LH_ANNOUNCEMENTS')
                        """);
                statement.execute("INSERT INTO external_data_collection_failures VALUES ('SH_ANNOUNCEMENT')");

                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261104_01__gh_announcement_collection.sql"));

                statement.execute("""
                        INSERT INTO data_pipeline_executions VALUES
                        ('GH_ANNOUNCEMENT_COLLECTION', 'COLLECT_GH_ANNOUNCEMENTS', 'COLLECT_GH_ANNOUNCEMENTS')
                        """);
                for (String table : STEP_TABLES) {
                    statement.execute("INSERT INTO " + table + " VALUES ('COLLECT_GH_ANNOUNCEMENTS')");
                    statement.execute("INSERT INTO " + table + " VALUES ('COLLECT_SH_ANNOUNCEMENTS')");
                    assertThatThrownBy(() -> statement.execute("INSERT INTO " + table + " VALUES ('UNKNOWN')"))
                            .isInstanceOf(SQLException.class);
                }
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261104_06__board_announcement_refinement_steps.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261107_01__board_announcement_collect_refine_steps.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261109_01__async_initial_complex_registration.sql"));
                // 후속 정제 단계까지 적용한 CHECK가 현재 코드와 지난 이력을 모두 허용해야 한다.
                for (DataPipelineType type : DataPipelineType.values()) {
                    statement.execute("INSERT INTO data_pipeline_executions(type) VALUES ('" + type.name() + "')");
                }
                for (DataPipelineStep step : DataPipelineStep.values()) {
                    statement.execute("INSERT INTO data_pipeline_executions(current_step, failed_step) VALUES ('"
                            + step.name() + "', '" + step.name() + "')");
                    for (String table : STEP_TABLES) {
                        statement.execute("INSERT INTO " + table + " VALUES ('" + step.name() + "')");
                    }
                }
                for (ExternalDataSource source : ExternalDataSource.values()) {
                    statement.execute("INSERT INTO external_data_collection_failures VALUES ('" + source.name() + "')");
                }
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO external_data_collection_failures VALUES ('UNKNOWN')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO data_pipeline_executions(type) VALUES ('UNKNOWN')"))
                        .isInstanceOf(SQLException.class);
                try (var rows = statement.executeQuery("SELECT count(*) FROM data_pipeline_executions "
                        + "WHERE type = 'SH_ANNOUNCEMENT_COLLECTION' AND current_step = 'MAP_LH_ANNOUNCEMENTS'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isOne();
                }

                statement.execute(insertSource("GH:801", "RENTAL_HOUSE", "[]"));
                assertThatThrownBy(() -> statement.execute(insertSource("GH:801", "RENTAL_HOUSE", "[]")))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute(insertSource("GH:802", "SHOP", "[]")))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute(insertSource("GH:803", "RENTAL_HOUSE", "{}")))
                        .isInstanceOf(SQLException.class);
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA gh_migration_test CASCADE");
            }
        }
    }

    private String insertSource(String sourceKey, String board, String attachments) {
        return """
                INSERT INTO gh_announcement_source (source_key, pbanc_no, board, kind_code, title, supply_type, region,
                posted_date, original_url, list_url, districts, reception, notice, attachments, collected_at)
                VALUES ('%s', '801', '%s', '01', '공고', '국민임대', '남양주시', '2026-07-10', 'url', 'list', '[]', '{}',
                '안내', '%s', now())
                """.formatted(sourceKey, board, attachments);
    }
}
