package com.toadzip.backend.ingest.pipeline.repository;

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
class IngestExecutionOwnershipMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 소유권_행을_초기화하고_단일_행과_음수_세대값_금지_제약을_적용한다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA ingest_execution_ownership_migration_test");
            try {
                connection.setSchema("ingest_execution_ownership_migration_test");
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260926_01__ingest_execution_ownership.sql"));
                try (var result = sql.executeQuery("SELECT id, generation, owner_id FROM ingest_execution_ownership")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt("id")).isEqualTo(1);
                    assertThat(result.getLong("generation")).isZero();
                    assertThat(result.getObject("owner_id")).isNull();
                    assertThat(result.next()).isFalse();
                }
                assertThatThrownBy(() -> sql.execute(
                        "INSERT INTO ingest_execution_ownership (id, generation) VALUES (2, 0)"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
                assertThatThrownBy(() -> sql.execute(
                        "UPDATE ingest_execution_ownership SET generation = -1 WHERE id = 1"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
            }
            finally {
                connection.setSchema("public");
                sql.execute("DROP SCHEMA ingest_execution_ownership_migration_test CASCADE");
            }
        }
    }
}
