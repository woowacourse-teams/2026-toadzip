package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
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
class AnnouncementChildProtectionMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기존_일정과_첨부를_보존하며_관리자_보호를_기본_해제로_추가한다() throws Exception {
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA announcement_child_protection_test");
            try {
                connection.setSchema("announcement_child_protection_test");
                sql.execute("CREATE TABLE announcement_schedules(id BIGINT PRIMARY KEY)");
                sql.execute("CREATE TABLE announcement_attachments(id BIGINT PRIMARY KEY)");
                sql.execute("INSERT INTO announcement_schedules VALUES(1)");
                sql.execute("INSERT INTO announcement_attachments VALUES(2)");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261019_09__protect_admin_announcement_documents.sql"));
                for (var table : java.util.List.of("announcement_schedules", "announcement_attachments")) {
                    try (var values = sql.executeQuery("SELECT admin_modified FROM " + table)) {
                        assertThat(values.next()).isTrue();
                        assertThat(values.getBoolean(1)).isFalse();
                        assertThat(values.next()).isFalse();
                    }
                    assertThatThrownBy(() -> sql.execute("UPDATE " + table + " SET admin_modified = NULL"))
                            .isInstanceOf(SQLException.class);
                }
            } finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA announcement_child_protection_test CASCADE");
            }
        }
    }
}
