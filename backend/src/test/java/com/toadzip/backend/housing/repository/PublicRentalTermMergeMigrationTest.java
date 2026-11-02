package com.toadzip.backend.housing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
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
class PublicRentalTermMergeMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void 공공임대_5년_10년을_공공임대로_합치고_원래_값을_되돌릴_수_있게_남긴다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA public_rental_merge_test");
            statement.execute("SET search_path TO public_rental_merge_test");
            try {
                statement.execute("""
                        CREATE TABLE housing_complexes (id bigint PRIMARY KEY, supply_type varchar(30) NOT NULL);
                        CREATE TABLE announcements (id bigint PRIMARY KEY, supply_type varchar(30) NOT NULL,
                            CONSTRAINT announcements_supply_type_check CHECK (supply_type IN (
                                'PUBLIC_RENTAL_5Y', '5년임대', 'PUBLIC_RENTAL_10Y', '10년임대', 'PUBLIC_RENTAL_50Y')));
                        INSERT INTO housing_complexes VALUES (1, 'PUBLIC_RENTAL_5Y'), (2, '10년임대'), (3, 'PUBLIC_RENTAL_50Y'),
                            (4, '5년임대'), (5, 'PUBLIC_RENTAL_10Y');
                        INSERT INTO announcements VALUES (10, 'PUBLIC_RENTAL_10Y'), (11, 'PUBLIC_RENTAL_50Y'), (12, 'PUBLIC_RENTAL_5Y'),
                            (13, '5년임대'), (14, '10년임대');
                        """);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261101_01__merge_public_rental_terms.sql"));

                assertThat(rows(statement, "SELECT id || ':' || supply_type FROM housing_complexes ORDER BY id"))
                        .containsExactly("1:PUBLIC_RENTAL", "2:PUBLIC_RENTAL", "3:PUBLIC_RENTAL_50Y", "4:PUBLIC_RENTAL", "5:PUBLIC_RENTAL");
                assertThat(rows(statement, "SELECT id || ':' || supply_type FROM announcements ORDER BY id"))
                        .containsExactly("10:PUBLIC_RENTAL", "11:PUBLIC_RENTAL_50Y", "12:PUBLIC_RENTAL", "13:PUBLIC_RENTAL", "14:PUBLIC_RENTAL");
                assertThat(rows(statement, """
                        SELECT table_name || ':' || row_id || ':' || supply_type FROM public_rental_term_backup
                        ORDER BY table_name, row_id
                        """)).containsExactly("announcements:10:PUBLIC_RENTAL_10Y", "announcements:12:PUBLIC_RENTAL_5Y",
                        "announcements:13:5년임대", "announcements:14:10년임대", "housing_complexes:1:PUBLIC_RENTAL_5Y",
                        "housing_complexes:2:10년임대", "housing_complexes:4:5년임대", "housing_complexes:5:PUBLIC_RENTAL_10Y");
                assertThatThrownBy(() -> statement.execute("UPDATE announcements SET supply_type = 'PUBLIC_RENTAL_5Y'"))
                        .isInstanceOf(SQLException.class);
            }
            finally {
                statement.execute("RESET search_path");
                statement.execute("DROP SCHEMA public_rental_merge_test CASCADE");
            }
        }
    }

    private List<String> rows(java.sql.Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
