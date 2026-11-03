package com.toadzip.backend.ingest.sh.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
class ShSourceConstructionRentalResetMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void SH_원천을_모두_지우고_열린_SH_수집_실패만_건너뜀으로_닫는다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA sh_source_reset_migration_test");
            statement.execute("SET search_path TO sh_source_reset_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE sh_announcement_source (id bigint PRIMARY KEY, source_key varchar(200));
                        INSERT INTO sh_announcement_source VALUES (1, 'SH:100'), (2, 'SH:200');
                        CREATE TABLE external_data_collection_failures (id bigint PRIMARY KEY, source varchar(40),
                            status varchar(20), error_type varchar(120), reason varchar(1000),
                            resolved_at timestamptz);
                        INSERT INTO external_data_collection_failures VALUES
                            (1, 'SH_ANNOUNCEMENT', 'PENDING', 'PARSE', '상세 일부를 읽지 못함', NULL),
                            (2, 'SH_ANNOUNCEMENT', 'RESOLVED', 'PARSE', '해결됨', '2026-10-01T00:00:00Z'),
                            (3, 'GH_ANNOUNCEMENT', 'PENDING', 'PARSE', 'GH 실패', NULL);
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261104_04__sh_source_construction_rental_reset.sql"));

                assertThat(rows(statement, "SELECT source_key FROM sh_announcement_source")).isEmpty();
                assertThat(rows(statement, """
                        SELECT id || ':' || status || ':' || error_type || ':' || (resolved_at IS NOT NULL)
                        FROM external_data_collection_failures ORDER BY id
                        """)).containsExactly("1:SKIPPED:NOT_APPLICABLE:true", "2:RESOLVED:PARSE:true",
                        "3:PENDING:PARSE:false");
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA sh_source_reset_migration_test CASCADE");
            }
        }
    }

    private static List<String> rows(Statement statement, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }
}
