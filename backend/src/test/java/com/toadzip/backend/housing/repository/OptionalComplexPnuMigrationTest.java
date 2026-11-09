package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class OptionalComplexPnuMigrationTest {

    @Test
    void 단지_PNU는_비워_둘_수_있지만_빈_문자열은_받지_않는다() throws Exception {
        String database = "optional_pnu_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                Flyway flyway = Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                        .locations("classpath:db/migration").target("20261102.02").load();
                flyway.migrate();
                flyway.validate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    try (var column = query.executeQuery("""
                            SELECT is_nullable FROM information_schema.columns
                            WHERE table_name = 'housing_complexes' AND column_name = 'pnu'
                            """)) {
                        assertTrue(column.next());
                        assertEquals("YES", column.getString(1));
                    }
                    try (var check = query.executeQuery("""
                            SELECT pg_get_constraintdef(oid) FROM pg_constraint
                            WHERE conname = 'ck_housing_complexes_pnu_not_blank'
                            """)) {
                        assertTrue(check.next());
                    }
                    query.execute(insertComplex(1, "NULL"));
                    var blank = assertThrows(SQLException.class, () -> query.execute(insertComplex(2, "'  '")));
                    assertEquals("23514", blank.getSQLState());
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private static String insertComplex(long id, String pnu) {
        return """
                INSERT INTO housing_complexes (id, name, source_complex_identifier, city_county_district_code, latitude,
                    legal_dong_code, longitude, pnu, province_code, road_address, provider, supply_type,
                    total_household_count, version, admin_modified)
                VALUES (%d, '단지', 'manual:pnu-%d', '11140', 37.5, '1114010100', 127, %s, '11', '주소', 'LH',
                    'HAPPY_HOUSING', 100, 0, false)
                """.formatted(id, id, pnu);
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        return PostgresTestDatabases.primaryUrl(database);
    }
}
