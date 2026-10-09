package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class ComplexVerificationMigrationTest {

    @Test
    void 기존_단지를_보존하며_검토_테이블을_추가하고_유효한_근거만_저장한다() throws Exception {
        String database = "complex_review_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261008.04").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seed.execute("""
                            INSERT INTO housing_complexes (id, name, source_complex_identifier,
                                city_county_district_code, latitude, legal_dong_code, longitude,
                                pnu, province_code, road_address, parking_space_count, provider,
                                supply_type, total_household_count, version, admin_modified)
                            VALUES (1, '기존 단지', 'manual:migration-test', '11140', 37.5,
                                '1114010100', 127, '1114010100100010000', '11', '기존 주소',
                                30, 'LH', 'HAPPY_HOUSING', 100, 7, true)
                            """);
                }

                Flyway flyway = migration(database).target("20261009.01").load();
                assertEquals(1, flyway.migrate().migrationsExecuted);
                flyway.validate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    try (var complex = query.executeQuery(
                            "SELECT name, road_address, version, admin_modified FROM housing_complexes WHERE id = 1")) {
                        assertTrue(complex.next());
                        assertEquals("기존 단지", complex.getString("name"));
                        assertEquals("기존 주소", complex.getString("road_address"));
                        assertEquals(7, complex.getLong("version"));
                        assertTrue(complex.getBoolean("admin_modified"));
                    }
                    try (var history = query.executeQuery("""
                            SELECT script FROM flyway_schema_history
                            WHERE success AND version = '20261009.01'
                            """)) {
                        assertTrue(history.next());
                        assertEquals("V20261009_01__create_housing_complex_reviews.sql", history.getString(1));
                    }
                    insertReview(query, 1, "{\"NAME\":\"기존 단지\"}", "공식 자료 확인");
                    try (var review = query.executeQuery("""
                            SELECT checked_values->>'NAME' AS name, evidence_note
                            FROM housing_complex_reviews WHERE housing_complex_id = 1
                            """)) {
                        assertTrue(review.next());
                        assertEquals("기존 단지", review.getString("name"));
                        assertEquals("공식 자료 확인", review.getString("evidence_note"));
                    }
                    assertThrows(SQLException.class, () -> insertReview(query, 1, "{}", "근거"));
                    assertThrows(SQLException.class, () -> insertReview(query, 1, "[]", "근거"));
                    assertThrows(SQLException.class, () -> insertReview(query, 1, "{\"NAME\":\"기존 단지\"}", " "));
                    assertThrows(SQLException.class, () -> insertReview(query, 999, "{\"NAME\":\"없는 단지\"}", "근거"));
                }
            } finally {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private void insertReview(Statement query, long complexId, String values, String note) throws SQLException {
        try (var insert = query.getConnection().prepareStatement("""
                INSERT INTO housing_complex_reviews
                    (housing_complex_id, outcome, checked_values, evidence_note, actor, reviewed_at)
                VALUES (?, 'VERIFIED', ?::jsonb, ?, 'admin', CURRENT_TIMESTAMP)
                """)) {
            insert.setLong(1, complexId);
            insert.setString(2, values);
            insert.setString(3, note);
            insert.executeUpdate();
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
