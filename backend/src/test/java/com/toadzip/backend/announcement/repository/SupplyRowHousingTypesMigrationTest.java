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
class SupplyRowHousingTypesMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 대표_주택형이_있는_공급행만_연결로_채우고_공급행이나_주택형을_지우면_연결도_지운다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA supply_row_types_migration_test");
            statement.execute("SET search_path TO supply_row_types_migration_test");
            try {
                statement.execute("""
                        CREATE TABLE housing_types (id bigint PRIMARY KEY);
                        CREATE TABLE supply_rows (id bigint PRIMARY KEY, housing_type_id bigint REFERENCES housing_types(id));
                        INSERT INTO housing_types VALUES (100), (200), (300);
                        INSERT INTO supply_rows VALUES (1, 100), (2, NULL), (3, 200), (4, 200);
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261101_04__supply_row_housing_types.sql"));

                assertThat(rows(statement, """
                        SELECT supply_row_id || ':' || housing_type_id FROM supply_row_housing_types
                        ORDER BY supply_row_id, housing_type_id
                        """)).containsExactly("1:100", "3:200", "4:200");
                statement.execute("INSERT INTO supply_row_housing_types VALUES (1, 300)");
                assertThatThrownBy(() -> statement.execute("INSERT INTO supply_row_housing_types VALUES (1, 300)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("INSERT INTO supply_row_housing_types VALUES (2, 999)"))
                        .isInstanceOf(SQLException.class);
                statement.execute("DELETE FROM supply_rows WHERE id = 1");
                assertThat(rows(statement, "SELECT supply_row_id || ':' || housing_type_id FROM supply_row_housing_types"
                        + " ORDER BY 1")).containsExactly("3:200", "4:200");
                // 연결을 모르는 이전 애플리케이션이 지운 주택형의 낡은 연결은 함께 지워진다.
                statement.execute("UPDATE supply_rows SET housing_type_id = 100 WHERE id = 4");
                statement.execute("UPDATE supply_rows SET housing_type_id = NULL WHERE id = 3");
                statement.execute("DELETE FROM housing_types WHERE id = 200");
                assertThat(rows(statement, "SELECT count(*) FROM supply_row_housing_types")).containsExactly("0");
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA supply_row_types_migration_test CASCADE");
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
