package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
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
class VerifiedAnnouncementMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 기존_공고를_유지하며_소유권과_확인_일정의_DB_제약을_추가한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA verified_announcement_migration_test");
            try {
                connection.setSchema("verified_announcement_migration_test");
                sql.execute("CREATE TABLE announcements (id BIGINT PRIMARY KEY)");
                sql.execute("CREATE TABLE housing_complexes (id BIGINT PRIMARY KEY)");
                sql.execute("INSERT INTO announcements VALUES (42)");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260926_01__ingest_execution_ownership.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260926_02__verified_announcement_schedules_and_revisions.sql"));
                try (var result = sql.executeQuery("SELECT application_schedule_reviewed FROM announcements")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isFalse();
                }
                try (var result = sql.executeQuery("SELECT generation, owner_id FROM ingest_execution_ownership")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                    assertThat(result.getObject(2)).isNull();
                    assertThat(result.next()).isFalse();
                }
                assertThatThrownBy(() -> sql.execute(
                        "INSERT INTO ingest_execution_ownership (id, generation) VALUES (2, 0)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute("""
                        INSERT INTO announcement_application_schedules
                          (announcement_id, state, start_date, end_date, source_url, source_page)
                        VALUES (42, 'CONDITIONAL', '2026-09-15', '2026-09-15', 'https://apply.lh.or.kr', 6)
                        """))
                        .isInstanceOf(SQLException.class);
                sql.execute("""
                        INSERT INTO announcement_application_schedules
                          (announcement_id, state, start_date, end_date, source_url, source_page)
                        VALUES (42, 'CONFIRMED', '2026-09-15', '2026-09-15', 'https://apply.lh.or.kr', 6)
                        """);
                assertThatThrownBy(() -> sql.execute(
                        "UPDATE announcement_application_schedules SET end_date = '2026-09-14'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql.execute(
                        "UPDATE announcement_application_schedules SET start_time = '10:00'"))
                        .isInstanceOf(SQLException.class);
            }
            finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA verified_announcement_migration_test CASCADE");
            }
        }
    }
}
