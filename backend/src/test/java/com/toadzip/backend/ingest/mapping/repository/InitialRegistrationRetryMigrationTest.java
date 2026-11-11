package com.toadzip.backend.ingest.mapping.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
class InitialRegistrationRetryMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기존_실패를_이력으로_보존하고_완료_상태와_연결을_유지한다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA initial_registration_retry_migration_test");
            statement.execute("SET search_path TO initial_registration_retry_migration_test");
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261105_02__initial_registration_targets.sql"));
                statement.execute("""
                        INSERT INTO initial_registration_targets
                            (source_complex_identifier, status, detail, housing_complex_id, checked_at)
                        VALUES ('1:HAPPY_HOUSING', 'COMPLETED', '완료', 10, now()),
                               ('2:HAPPY_HOUSING', 'FAILED', '기존 저장 실패', null, now())
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261111_01__initial_registration_retries.sql"));
                try (var rows = statement.executeQuery("SELECT * FROM initial_registration_failures")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("source_complex_identifier")).isEqualTo("2:HAPPY_HOUSING");
                    assertThat(rows.getString("detail")).isEqualTo("기존 저장 실패");
                    assertThat(rows.getString("actor")).isNull();
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("""
                        SELECT status, housing_complex_id, information_fingerprint
                        FROM initial_registration_targets WHERE source_complex_identifier = '1:HAPPY_HOUSING'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("status")).isEqualTo("COMPLETED");
                    assertThat(rows.getLong("housing_complex_id")).isEqualTo(10);
                    assertThat(rows.getString("information_fingerprint")).isNull();
                }
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA initial_registration_retry_migration_test CASCADE");
            }
        }
    }
}
