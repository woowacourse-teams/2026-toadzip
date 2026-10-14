package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class HousingTypeComplexIndexMigrationTest {

    @Test
    void 기존_주택형을_보존하며_단지별_조회_인덱스를_추가한다() throws Exception {
        String database = "housing_type_index_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261014.02").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seed.execute("""
                            INSERT INTO housing_complexes (id, name, source_complex_identifier,
                                city_county_district_code, latitude, legal_dong_code, longitude,
                                pnu, province_code, road_address, parking_space_count, provider,
                                supply_type, total_household_count)
                            VALUES (1, '기존 단지', 'manual:index-test', '11140', 37.5,
                                '1114010100', 126.9, '1114010100100010000', '11', '기존 주소',
                                30, 'LH', 'HAPPY_HOUSING', 100)
                            """);
                    seed.execute("""
                            INSERT INTO housing_types
                                (id, housing_complex_id, name, exclusive_area, basic_deposit,
                                 basic_monthly_rent, rental_condition_collected_at)
                            VALUES (1, 1, '20A', 20, 0, 0, '2026-10-14T00:00:00Z'),
                                   (2, 1, '60A', 60, NULL, NULL, NULL)
                            """);
                }

                Flyway flyway = migration(database).target("20261014.03").load();
                assertEquals(1, flyway.migrate().migrationsExecuted);
                flyway.validate();
                assertEquals(0, flyway.migrate().migrationsExecuted);
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    try (var index = query.executeQuery("""
                            SELECT indisvalid, indisunique, indnkeyatts,
                                   pg_get_indexdef(indexrelid, 1, true) AS indexed_column
                            FROM pg_index
                            WHERE indexrelid = 'public.idx_housing_types_housing_complex_id'::regclass
                              AND indrelid = 'public.housing_types'::regclass
                            """)) {
                        assertTrue(index.next());
                        assertTrue(index.getBoolean("indisvalid"));
                        assertFalse(index.getBoolean("indisunique"));
                        assertEquals(1, index.getInt("indnkeyatts"));
                        assertEquals("housing_complex_id", index.getString("indexed_column"));
                    }
                    try (var rows = query.executeQuery("""
                            SELECT id, name, exclusive_area, basic_deposit, basic_monthly_rent
                            FROM housing_types WHERE housing_complex_id = 1 ORDER BY id
                            """)) {
                        assertTrue(rows.next());
                        assertEquals(1L, rows.getLong("id"));
                        assertEquals("20A", rows.getString("name"));
                        assertEquals(20, rows.getBigDecimal("exclusive_area").intValueExact());
                        assertEquals(0L, rows.getObject("basic_deposit"));
                        assertEquals(0L, rows.getObject("basic_monthly_rent"));
                        assertTrue(rows.next());
                        assertEquals(2L, rows.getLong("id"));
                        assertEquals("60A", rows.getString("name"));
                        assertEquals(60, rows.getBigDecimal("exclusive_area").intValueExact());
                        assertNull(rows.getObject("basic_deposit"));
                        assertNull(rows.getObject("basic_monthly_rent"));
                        assertFalse(rows.next());
                    }
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private FluentConfiguration migration(String database) {
        return Flyway.configure().dataSource(url(database), "toadzip_test", "toadzip_test")
                .locations("classpath:db/migration");
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "toadzip_test", "toadzip_test");
    }

    private String url(String database) {
        String port = System.getenv().getOrDefault("TEST_POSTGRES_PORT", "55432");
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database;
    }
}
