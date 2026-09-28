package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DataPipelineStopMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 기존_실행을_보존하고_중지와_진행_컬럼을_기본값으로_추가한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema pipeline_stop_migration_test");
            try {
                statement.execute("""
                        create table pipeline_stop_migration_test.data_pipeline_executions (
                            id bigint primary key,
                            status varchar(20) not null,
                            constraint data_pipeline_executions_status_check check (status in ('RUNNING', 'COMPLETED'))
                        )
                        """);
                statement.execute("""
                        insert into pipeline_stop_migration_test.data_pipeline_executions values (1, 'RUNNING')
                        """);
                var resource = new ClassPathResource("db/migration/V20260926_03__pipeline_stop_and_progress.sql");
                String migration = resource.getContentAsString(StandardCharsets.UTF_8)
                        .replace("public.data_pipeline_executions",
                                "pipeline_stop_migration_test.data_pipeline_executions");
                ScriptUtils.executeSqlScript(
                        connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8))
                );
                var progressResource = new ClassPathResource("db/migration/V20260926_04__pipeline_work_progress.sql");
                String progressMigration = progressResource.getContentAsString(StandardCharsets.UTF_8)
                        .replace("public.data_pipeline_executions",
                                "pipeline_stop_migration_test.data_pipeline_executions");
                ScriptUtils.executeSqlScript(
                        connection, new ByteArrayResource(progressMigration.getBytes(StandardCharsets.UTF_8))
                );
                try (var result = statement.executeQuery(
                        "select * from pipeline_stop_migration_test.data_pipeline_executions"
                )) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("status")).isEqualTo("RUNNING");
                    assertThat(result.getBoolean("stop_requested")).isFalse();
                    assertThat(result.getLong("external_request_count")).isZero();
                    assertThat(result.getString("last_request_description")).isNull();
                    assertThat(result.getString("work_progress")).isNull();
                }
                assertThat(statement.executeUpdate("""
                        update pipeline_stop_migration_test.data_pipeline_executions set status = 'STOPPED' where id = 1
                        """)).isEqualTo(1);
            }
            finally {
                statement.execute("drop schema pipeline_stop_migration_test cascade");
            }
        }
    }
}
