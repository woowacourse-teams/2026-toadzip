package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShAnnouncementMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationPreservesExistingValuesAndExtendsEveryPipelineAndFailureConstraint() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA sh_migration_test");
            statement.execute("SET search_path TO sh_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE data_pipeline_executions (
                            type varchar(40), current_step varchar(80), failed_step varchar(80),
                            report text DEFAULT '기존 보고서',
                            CONSTRAINT data_pipeline_executions_type_check CHECK(type = 'ANNOUNCEMENT_COLLECTION'),
                            CONSTRAINT data_pipeline_executions_current_step_check
                                CHECK(current_step = 'COLLECT_MYHOME_ANNOUNCEMENTS'),
                            CONSTRAINT data_pipeline_executions_failed_step_check
                                CHECK(failed_step = 'COLLECT_MYHOME_ANNOUNCEMENTS')
                        )
                        """);
                List<String> tables = List.of("data_pipeline_execution_completed_steps",
                        "data_pipeline_execution_skipped_steps", "data_pipeline_execution_partial_failures");
                List<String> columns = List.of("completed_step", "skipped_step", "partially_failed_step");
                for (int index = 0; index < tables.size(); index++) {
                    statement.execute("CREATE TABLE " + tables.get(index)
                            + " (" + columns.get(index) + " varchar(80))");
                }
                statement.execute("""
                        CREATE TABLE external_data_collection_failures (source varchar(80),
                        CONSTRAINT external_data_collection_failures_source_check CHECK(source = 'MYHOME_ANNOUNCEMENT'))
                        """);
                statement.execute("INSERT INTO data_pipeline_executions (type) VALUES ('ANNOUNCEMENT_COLLECTION')");

                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261010_01__sh_announcement_collection.sql"));

                statement.execute("""
                        INSERT INTO data_pipeline_executions (type,current_step,failed_step)
                        VALUES ('SH_ANNOUNCEMENT_COLLECTION','COLLECT_SH_ANNOUNCEMENTS','COLLECT_SH_ANNOUNCEMENTS')
                        """);
                for (int index = 0; index < tables.size(); index++) {
                    statement.execute("INSERT INTO " + tables.get(index) + " VALUES ('COLLECT_SH_ANNOUNCEMENTS')");
                    final int current = index;
                    assertThatThrownBy(() -> statement.execute("INSERT INTO " + tables.get(current)
                            + " VALUES ('UNKNOWN')")).isInstanceOf(SQLException.class);
                }
                statement.execute("INSERT INTO external_data_collection_failures VALUES ('SH_ANNOUNCEMENT')");
                statement.execute("INSERT INTO external_data_collection_failures VALUES ('LH_ANNOUNCEMENT_SUPPLY')");
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO external_data_collection_failures VALUES ('UNKNOWN')"))
                        .isInstanceOf(SQLException.class);
                try (var rows = statement.executeQuery(
                        "SELECT count(*) FROM data_pipeline_executions WHERE report='기존 보고서'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(2);
                }
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO data_pipeline_executions(type) VALUES ('UNKNOWN')"))
                        .isInstanceOf(SQLException.class);
                statement.execute("""
                        INSERT INTO sh_announcement_source (source_key,seq,title,department,registered_date,body_html,
                        body_text,attachments,original_url,list_url,raw_list_html,raw_detail_html,content_fingerprint,
                        changed_at,collected_at) VALUES ('SH:m_247:100','100','공고','공급부','2026-10-02','<p>본문</p>',
                        '본문','[]','url','list','list html','detail html','hash',now(),now())
                        """);
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA sh_migration_test CASCADE");
            }
        }
    }
}
