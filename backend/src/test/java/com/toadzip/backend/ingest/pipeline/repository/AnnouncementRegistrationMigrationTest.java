package com.toadzip.backend.ingest.pipeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AnnouncementRegistrationMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 기존_실행을_보존하고_단건_등록은_대상_ID를_필수로_저장한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA registration_migration_test");
            statement.execute("SET search_path TO registration_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE data_pipeline_executions (
                            type VARCHAR(40) NOT NULL,
                            report TEXT NOT NULL DEFAULT '기존 실행',
                            CONSTRAINT data_pipeline_executions_type_check CHECK (
                                type IN ('ANNOUNCEMENT_SYNC', 'COMPLEX_SYNC')
                            )
                        )
                        """);
                statement.execute("INSERT INTO data_pipeline_executions(type) VALUES ('ANNOUNCEMENT_SYNC')");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261007_02__single_announcement_registration.sql"));

                insert(connection, "ANNOUNCEMENT_REGISTRATION", "21026");
                assertThatThrownBy(() -> insert(connection, "ANNOUNCEMENT_REGISTRATION", null))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insert(connection, "ANNOUNCEMENT_REGISTRATION", " "))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insert(connection, "UNKNOWN", null)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insert(connection, "ANNOUNCEMENT_SYNC", "21026"))
                        .isInstanceOf(SQLException.class);
                try (var rows = statement.executeQuery("SELECT report, target_announcement_identifier"
                        + " FROM data_pipeline_executions WHERE type = 'ANNOUNCEMENT_SYNC'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("기존 실행");
                    assertThat(rows.getString(2)).isNull();
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA registration_migration_test CASCADE");
            }
        }
    }

    private void insert(Connection connection, String type, String identifier) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO data_pipeline_executions(type, target_announcement_identifier) VALUES (?, ?)")) {
            statement.setString(1, type);
            statement.setString(2, identifier);
            statement.executeUpdate();
        }
    }
}
