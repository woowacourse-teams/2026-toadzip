package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
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
class SupplyRowDisplayNameMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 공개_화면이_원천_이름을_보여_주던_보정_행만_공고_표시명으로_옮긴다() throws Exception {
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA supply_row_display_name_test");
            try {
                connection.setSchema("supply_row_display_name_test");
                sql.execute("CREATE TABLE housing_types(id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
                sql.execute("""
                        CREATE TABLE supply_rows(id BIGINT PRIMARY KEY, housing_type_id BIGINT,
                          source_housing_type_name VARCHAR(255) NOT NULL, admin_modified BOOLEAN NOT NULL,
                          supply_group_id BIGINT)
                        """);
                sql.execute("INSERT INTO housing_types VALUES (10, '26')");
                sql.execute("""
                        INSERT INTO supply_rows VALUES
                          (1, 10, '26형 고령자', true, NULL),
                          (2, 10, '26B', false, 7),
                          (3, 10, '26', true, NULL),
                          (4, 10, '아파트', false, NULL),
                          (5, NULL, '39.8800', true, NULL)
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261024_02__supply_row_announcement_housing_type_name.sql"));

                Map<Long, String> labels = new LinkedHashMap<>();
                try (var values = sql.executeQuery(
                        "SELECT id, announcement_housing_type_name FROM supply_rows ORDER BY id")) {
                    while (values.next()) { labels.put(values.getLong(1), values.getString(2)); }
                }
                // 보정·공통 모집 행은 원천 이름을 보여 줬고, 나머지는 연결 주택형 이름(없으면 원천)을 보여 줬다.
                assertThat(labels).containsOnlyKeys(1L, 2L, 3L, 4L, 5L);
                assertThat(labels.values()).containsExactly("26형 고령자", "26B", null, null, null);
            } finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA supply_row_display_name_test CASCADE");
            }
        }
    }
}
