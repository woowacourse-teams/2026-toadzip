package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
class AnnouncementSupplyOwnerMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 관리자가_공급정보를_고친_흔적이_없는_공고만_원천_소유로_채우고_나머지는_공고문_소유다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA supply_owner_migration_test");
            statement.execute("SET search_path TO supply_owner_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE announcements (id bigint PRIMARY KEY, source_announcement_identifier varchar(255) NOT NULL);
                        CREATE TABLE supply_rows (id bigint PRIMARY KEY, announcement_id bigint NOT NULL,
                            admin_modified boolean NOT NULL DEFAULT false, admin_excluded boolean NOT NULL DEFAULT false,
                            supply_group_id bigint);
                        CREATE TABLE supply_targets (id bigint PRIMARY KEY, supply_row_id bigint NOT NULL,
                            admin_modified boolean NOT NULL DEFAULT false, admin_excluded boolean NOT NULL DEFAULT false);
                        CREATE TABLE admin_data_changes (id bigint PRIMARY KEY, resource_type varchar(40) NOT NULL,
                            resource_id bigint NOT NULL, action varchar(40) NOT NULL);
                        INSERT INTO announcements
                        SELECT id, CASE id WHEN 3 THEN 'ADMIN_ENTRY-ANNOUNCEMENT-a' ELSE '2100' || id END
                        FROM generate_series(1, 20) id;
                        -- 1: 고친 흔적 없음, 2: 다른 공고·자원의 흔적만 있음, 3: 직접 등록
                        INSERT INTO supply_rows (id, announcement_id, admin_modified, admin_excluded, supply_group_id) VALUES
                            (10, 1, false, false, NULL), (20, 2, false, false, NULL),
                            (40, 4, true, false, NULL), (50, 5, false, true, NULL), (60, 6, false, false, 900),
                            (70, 7, false, false, NULL), (80, 8, false, false, NULL);
                        INSERT INTO supply_targets (id, supply_row_id, admin_modified, admin_excluded) VALUES
                            (100, 10, false, false), (200, 20, false, false), (700, 70, true, false), (800, 80, false, true);
                        INSERT INTO admin_data_changes VALUES
                            (1, 'COMPLEX', 2, 'UPDATE_SUPPLY'), (2, 'ANNOUNCEMENT', 2, 'REVIEW_SUPPLY'),
                            (3, 'ANNOUNCEMENT', 2, 'UPDATE'), (4, 'ANNOUNCEMENT', 2, 'UPDATE_SCHEDULE'),
                            (9, 'ANNOUNCEMENT', 9, 'CORRECT_FROM_PDF'), (10, 'ANNOUNCEMENT', 10, 'CORRECT_SUPPLY'),
                            (11, 'ANNOUNCEMENT', 11, 'UPDATE_SUPPLY'), (12, 'ANNOUNCEMENT', 12, 'UPDATE_SUPPLY_TARGET'),
                            (13, 'ANNOUNCEMENT', 13, 'SAVE_SUPPLY_MATCH'), (14, 'ANNOUNCEMENT', 14, 'SPLIT_SUPPLY'),
                            (15, 'ANNOUNCEMENT', 15, 'EXCLUDE_SUPPLY_ROW'), (16, 'ANNOUNCEMENT', 16, 'INCLUDE_SUPPLY_ROW');
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261101_02__announcement_supply_owner.sql"));

                assertThat(rows(statement, "SELECT id || ':' || supply_owner FROM announcements WHERE id <= 16 ORDER BY id"))
                        .containsExactly("1:LH_SOURCE", "2:LH_SOURCE", "3:ANNOUNCEMENT_DOCUMENT",
                                "4:ANNOUNCEMENT_DOCUMENT", "5:ANNOUNCEMENT_DOCUMENT", "6:ANNOUNCEMENT_DOCUMENT",
                                "7:ANNOUNCEMENT_DOCUMENT", "8:ANNOUNCEMENT_DOCUMENT", "9:ANNOUNCEMENT_DOCUMENT",
                                "10:ANNOUNCEMENT_DOCUMENT", "11:ANNOUNCEMENT_DOCUMENT", "12:ANNOUNCEMENT_DOCUMENT",
                                "13:ANNOUNCEMENT_DOCUMENT", "14:ANNOUNCEMENT_DOCUMENT", "15:ANNOUNCEMENT_DOCUMENT",
                                "16:ANNOUNCEMENT_DOCUMENT");
                // 이전 아티팩트가 소유 없이 저장한 공고는 정제가 덮지 않도록 공고문 소유가 된다.
                statement.execute("INSERT INTO announcements (id, source_announcement_identifier) VALUES (30, '2130')");
                assertThat(rows(statement, "SELECT supply_owner FROM announcements WHERE id = 30"))
                        .containsExactly("ANNOUNCEMENT_DOCUMENT");
                assertThatThrownBy(() -> statement.execute(
                        "UPDATE announcements SET supply_owner = 'UNKNOWN' WHERE id = 1")).isInstanceOf(SQLException.class);
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA supply_owner_migration_test CASCADE");
            }
        }
    }

    private static List<String> rows(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
