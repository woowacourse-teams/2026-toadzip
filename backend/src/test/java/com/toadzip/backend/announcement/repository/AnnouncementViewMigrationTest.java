package com.toadzip.backend.announcement.repository;

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
class AnnouncementViewMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_조회수를_보존하고_브라우저별_유일성과_공고_삭제_정리를_보장한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA announcement_view_migration_test");
            try {
                connection.setSchema("announcement_view_migration_test");
                sql.execute("CREATE TABLE announcements (id BIGINT PRIMARY KEY, view_count BIGINT NOT NULL)");
                sql.execute("INSERT INTO announcements VALUES (42, 7)");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260928_03__announcement_daily_views.sql"));
                String insert = """
                        INSERT INTO announcement_views (announcement_id, viewer_id, viewed_on)
                        VALUES (42, 'abcdefab-cdef-4abc-8def-abcdefabcdef', '2026-09-28')
                        """;
                sql.execute(insert);
                assertThatThrownBy(() -> sql.execute(insert)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute(insert.replace("42,", "43,")))
                        .isInstanceOf(SQLException.class);
                try (var result = sql.executeQuery("SELECT view_count FROM announcements WHERE id = 42")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(7);
                }
                sql.execute("DELETE FROM announcements WHERE id = 42");
                try (var result = sql.executeQuery("SELECT count(*) FROM announcement_views")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
            } finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA announcement_view_migration_test CASCADE");
            }
        }
    }
}
