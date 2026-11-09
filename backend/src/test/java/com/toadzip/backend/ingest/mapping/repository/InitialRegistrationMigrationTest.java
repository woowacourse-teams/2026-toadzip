package com.toadzip.backend.ingest.mapping.repository;

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
class InitialRegistrationMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 진행_상태를_저장하고_서비스_연결_없는_완료와_알_수_없는_상태를_거절한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA initial_registration_migration_test");
            statement.execute("SET search_path TO initial_registration_migration_test");
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_02__initial_registration_targets.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_03__initial_registration_target_text.sql"));
                statement.execute("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, status, detail, housing_complex_id, checked_at)
                        VALUES ('1:HAPPY_HOUSING', 'COMPLETED', '완료', 10, now())
                        """);
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, status, detail, checked_at)
                        VALUES ('2:HAPPY_HOUSING', 'COMPLETED', '연결 없음', now())
                        """)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, status, detail, checked_at)
                        VALUES ('3:HAPPY_HOUSING', 'UNKNOWN', '잘못된 상태', now())
                        """)).isInstanceOf(SQLException.class);
                try (var rows = statement.executeQuery("SELECT count(*) FROM initial_registration_targets")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA initial_registration_migration_test CASCADE");
            }
        }
    }

    @Test
    void 기존_원천과_완료_기록을_보존하며_보완_좌표의_완전성과_범위를_검증한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA initial_registration_location_migration_test");
            statement.execute("SET search_path TO initial_registration_location_migration_test");
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_02__initial_registration_targets.sql"));
                statement.execute("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, road_address, status, detail, housing_complex_id, checked_at)
                        VALUES ('1:HAPPY_HOUSING', '원천 주소', 'COMPLETED', '완료', 10, now())
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261108_01__initial_registration_locations.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261108_02__initial_registration_source_presence.sql"));
                try (var rows = statement.executeQuery("SELECT * FROM initial_registration_targets")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("road_address")).isEqualTo("원천 주소");
                    assertThat(rows.getString("status")).isEqualTo("COMPLETED");
                    assertThat(rows.getBigDecimal("prepared_latitude")).isNull();
                    assertThat(rows.getBoolean("source_present")).isTrue();
                }
                assertThatThrownBy(() -> statement.execute("""
                        UPDATE initial_registration_targets SET prepared_address = '보완 주소',
                            prepared_latitude = 37.5, location_origin = 'ADMIN'
                        """)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("""
                        UPDATE initial_registration_targets SET prepared_address = '보완 주소',
                            prepared_latitude = 91, prepared_longitude = 127, location_origin = 'ADMIN'
                        """)).isInstanceOf(SQLException.class);
                statement.execute("""
                        UPDATE initial_registration_targets SET prepared_address = '보완 주소',
                            prepared_latitude = 37.12345678, prepared_longitude = 127.12345678,
                            location_origin = 'ADMIN', location_checked_at = now(), location_checked_by = 'admin'
                        """);
                try (var rows = statement.executeQuery("SELECT * FROM initial_registration_targets")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("road_address")).isEqualTo("원천 주소");
                    assertThat(rows.getBigDecimal("prepared_latitude")).isEqualByComparingTo("37.12345678");
                    assertThat(rows.getLong("housing_complex_id")).isEqualTo(10);
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA initial_registration_location_migration_test CASCADE");
            }
        }
    }

    @Test
    void 기존_완료_기록을_보존하며_긴_원천_단지명과_주소를_저장하도록_확장한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA initial_registration_text_migration_test");
            statement.execute("SET search_path TO initial_registration_text_migration_test");
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_02__initial_registration_targets.sql"));
                statement.execute("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, name, road_address, status, detail,
                             housing_complex_id, checked_at)
                        VALUES ('1:HAPPY_HOUSING', '기존 단지', '기존 주소', 'COMPLETED', '완료',
                                10, '2026-11-04T00:00:00Z')
                        """);

                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_03__initial_registration_target_text.sql"));

                try (var rows = statement.executeQuery("SELECT * FROM initial_registration_targets")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("source_complex_identifier")).isEqualTo("1:HAPPY_HOUSING");
                    assertThat(rows.getString("name")).isEqualTo("기존 단지");
                    assertThat(rows.getString("road_address")).isEqualTo("기존 주소");
                    assertThat(rows.getString("status")).isEqualTo("COMPLETED");
                    assertThat(rows.getString("detail")).isEqualTo("완료");
                    assertThat(rows.getLong("housing_complex_id")).isEqualTo(10);
                    assertThat(rows.getTimestamp("checked_at").toInstant()).isEqualTo("2026-11-04T00:00:00Z");
                    assertThat(rows.next()).isFalse();
                }
                String name = "가".repeat(1024);
                String address = "나".repeat(2048);
                try (var insert = connection.prepareStatement("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, name, road_address, status, detail, checked_at)
                        VALUES ('2:HAPPY_HOUSING', ?, ?, 'INFORMATION_REQUIRED', '원천 확인 필요', now())
                        RETURNING name, road_address
                        """)) {
                    insert.setString(1, name);
                    insert.setString(2, address);
                    try (var rows = insert.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString("name")).isEqualTo(name);
                        assertThat(rows.getString("road_address")).isEqualTo(address);
                    }
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA initial_registration_text_migration_test CASCADE");
            }
        }
    }
}
