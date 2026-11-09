package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 테스트 프로필은 Flyway를 끄므로 공통 모집수 묶음 표 마이그레이션을 직접 적용해 제약과 지울 때의 동작을 확인한다. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class SupplyTargetCountGroupsMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 묶음은_종류와_음수가_아닌_수를_지키고_묶음을_지우면_대상은_남고_공급행을_지우면_묶음도_지운다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA supply_target_count_groups_migration_test");
            statement.execute("SET search_path TO supply_target_count_groups_migration_test");
            try {
                statement.execute("CREATE TABLE supply_rows (id bigint PRIMARY KEY)");
                statement.execute("CREATE TABLE supply_targets (id bigint PRIMARY KEY, supply_row_id bigint REFERENCES supply_rows (id))");
                statement.execute("INSERT INTO supply_rows VALUES (1), (2)");
                statement.execute("INSERT INTO supply_targets VALUES (10, 1), (11, 1)");

                statement.execute(migration());

                statement.execute("INSERT INTO supply_target_count_groups (id, supply_row_id, kind, household_count) VALUES (100, 1, 'RESERVE', 20)");
                statement.execute("UPDATE supply_targets SET reserve_count_group_id = 100");
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO supply_target_count_groups (supply_row_id, kind, household_count) VALUES (1, 'OTHER', 1)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO supply_target_count_groups (supply_row_id, kind, household_count) VALUES (1, 'SUPPLY', -1)"))
                        .isInstanceOf(SQLException.class);

                statement.execute("DELETE FROM supply_target_count_groups WHERE id = 100");
                assertThat(count(statement, "SELECT count(*) FROM supply_targets WHERE reserve_count_group_id IS NULL")).isEqualTo(2);

                statement.execute("INSERT INTO supply_target_count_groups (supply_row_id, kind, household_count) VALUES (2, 'SUPPLY', 5)");
                statement.execute("DELETE FROM supply_rows WHERE id = 2");
                assertThat(count(statement, "SELECT count(*) FROM supply_target_count_groups")).isZero();
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA supply_target_count_groups_migration_test CASCADE");
            }
        }
    }

    private long count(Statement statement, String sql) throws SQLException {
        try (var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource("db/migration/V20261109_01__supply_target_count_groups.sql").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
