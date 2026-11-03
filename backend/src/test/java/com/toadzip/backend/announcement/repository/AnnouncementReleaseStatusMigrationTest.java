package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Statement;
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
class AnnouncementReleaseStatusMigrationTest {

    private static final String[] REQUIRED = {"application_start_date", "application_end_date",
            "winner_announcement_date", "supply_type", "recruitment_type"};

    @Autowired private DataSource dataSource;

    @Test
    void 기존_공고는_공개이고_미확정_공고만_필수값을_비울_수_있다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA release_status_migration_test");
            statement.execute("SET search_path TO release_status_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE announcements (id bigint PRIMARY KEY,
                            application_start_date date NOT NULL, application_end_date date NOT NULL,
                            winner_announcement_date date NOT NULL, supply_type varchar(255) NOT NULL,
                            recruitment_type varchar(255) NOT NULL);
                        INSERT INTO announcements VALUES (1, '2026-08-10', '2026-08-14', '2026-09-01', 'HAPPY_HOUSING', 'NEW');
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261104_02__announcement_release_status.sql"));

                assertThat(value(statement, "SELECT release_status FROM announcements WHERE id = 1")).isEqualTo("PUBLISHED");
                statement.execute("INSERT INTO announcements (id, release_status) VALUES (2, 'UNCONFIRMED')");
                for (String column : REQUIRED) {
                    assertThatThrownBy(() -> statement.execute(
                            "UPDATE announcements SET " + column + " = NULL WHERE id = 1"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("ck_announcements_published_required");
                }
                assertThatThrownBy(() -> statement.execute(
                        "UPDATE announcements SET release_status = 'PUBLISHED' WHERE id = 2"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_announcements_published_required");
                assertThatThrownBy(() -> statement.execute(
                        "UPDATE announcements SET release_status = 'HIDDEN' WHERE id = 1"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_announcements_release_status");
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA release_status_migration_test CASCADE");
            }
        }
    }

    private static String value(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
