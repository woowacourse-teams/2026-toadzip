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
                        "db/migration/V20261104_06__initial_registration_targets.sql"));
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
}
