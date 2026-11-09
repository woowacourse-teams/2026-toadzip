package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class OptionalComplexHouseholdCountMigrationTest {

    @Test
    void 기존_단지_세대수를_유지하면서_미확인_세대수_등록을_허용한다() throws Exception {
        String database = "optional_households_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                Flyway flyway = Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                        .locations("classpath:db/migration").target("20261103.01").load();
                flyway.migrate();
                flyway.validate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    query.execute(insertComplex(3, "350"));
                }
                Flyway upgraded = Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                        .locations("classpath:db/migration").target("20261107.02").load();
                upgraded.migrate();
                upgraded.validate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    try (var column = query.executeQuery("""
                            SELECT is_nullable FROM information_schema.columns
                            WHERE table_name = 'housing_complexes' AND column_name = 'total_household_count'
                            """)) {
                        assertTrue(column.next());
                        assertEquals("YES", column.getString(1));
                    }
                    query.execute(insertComplex(1, "NULL"));
                    query.execute(insertComplex(2, "0"));
                    try (var counts = query.executeQuery("SELECT total_household_count FROM housing_complexes ORDER BY id")) {
                        assertTrue(counts.next());
                        assertEquals(null, counts.getObject(1));
                        assertTrue(counts.next());
                        assertEquals(0, counts.getInt(1));
                        assertTrue(counts.next());
                        assertEquals(350, counts.getInt(1));
                    }
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private static String insertComplex(long id, String count) {
        return """
                INSERT INTO housing_complexes (id, name, source_complex_identifier, city_county_district_code, latitude,
                    legal_dong_code, longitude, pnu, province_code, road_address, provider, supply_type,
                    total_household_count, version, admin_modified)
                VALUES (%d, '단지', 'manual:pnu-%d', '11140', 37.5, '1114010100', 127, NULL, '11', '주소', 'LH',
                    'HAPPY_HOUSING', %s, 0, false)
                """.formatted(id, id, count);
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        return PostgresTestDatabases.primaryUrl(database);
    }
}
