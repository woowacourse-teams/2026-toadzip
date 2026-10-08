package com.toadzip.backend.streetview.repository;

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
class StreetViewPolicyMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 비활성_싱글턴을_생성하고_정책_제약을_보장한다() throws Exception {
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA street_view_policy_migration_test");
            try {
                connection.setSchema("street_view_policy_migration_test");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261008_01__street_view_policy.sql"));
                try (var result = sql.executeQuery("SELECT * FROM street_view_policies")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong("id")).isEqualTo(1);
                    assertThat(result.getBoolean("enabled")).isFalse();
                    assertThat(result.getLong("version")).isZero();
                    assertThat(result.getString("updated_by")).isEqualTo("SYSTEM_MIGRATION");
                    assertThat(result.getString("change_reason")).isEqualTo("FE 연동 검증 전 기본 비활성화");
                    assertThat(result.getTimestamp("updated_at")).isNotNull();
                    assertThat(result.next()).isFalse();
                }
                for (String assignment : List.of("id = 2", "version = -1", "enabled = NULL", "change_reason = ' '",
                        "change_reason = NULL", "updated_by = ' '", "updated_at = NULL")) {
                    assertThatThrownBy(() -> sql.execute("UPDATE street_view_policies SET " + assignment))
                            .isInstanceOf(SQLException.class);
                }
                assertThatThrownBy(() -> sql.execute(
                        "INSERT INTO street_view_policies SELECT * FROM street_view_policies"))
                        .isInstanceOf(SQLException.class);
            } finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA street_view_policy_migration_test CASCADE");
            }
        }
    }
}
