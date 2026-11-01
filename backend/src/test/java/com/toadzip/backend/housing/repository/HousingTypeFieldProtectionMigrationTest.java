package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class HousingTypeFieldProtectionMigrationTest {

    @Test
    void 기존_관리자_수정_주택형은_이력에서_실제로_바꾼_필드만_보호한다() throws Exception {
        String database = "type_protection_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261101.06").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seed.execute("""
                            INSERT INTO housing_complexes (id, name, source_complex_identifier,
                                city_county_district_code, latitude, legal_dong_code, longitude,
                                pnu, province_code, road_address, parking_space_count, provider,
                                supply_type, total_household_count, version, admin_modified)
                            VALUES (1, '기존 단지', 'manual:migration-test', '11140', 37.5,
                                '1114010100', 127, '1114010100100010000', '11', '기존 주소',
                                30, 'LH', 'HAPPY_HOUSING', 100, 7, true),
                                (2, '다른 단지', 'manual:migration-test-2', '11140', 37.5,
                                '1114010100', 127, '1114010100100020000', '11', '다른 주소',
                                30, 'LH', 'HAPPY_HOUSING', 100, 0, false)
                            """);
                    seed.execute("""
                            INSERT INTO housing_types (id, housing_complex_id, source_housing_type_identifier, name,
                                exclusive_area, admin_modified, admin_deleted)
                            VALUES (11, 1, 'ADMIN_ENTRY-HOUSING-TYPE-a', '16A', 16.37, true, false),
                                (12, 1, 'ADMIN_ENTRY-HOUSING-TYPE-b', '26A', 26.01, true, false),
                                (13, 1, 'source-13', '36', 36.91, true, false),
                                (14, 1, 'source-14', '46', 46.0, true, false),
                                (15, 1, 'source-15', '59', 59.9, true, false),
                                (16, 2, 'source-16', '84', 84.9, false, false)
                            """);
                    seed.execute("""
                            INSERT INTO admin_data_changes (resource_type, resource_id, action, actor, occurred_at,
                                before_value, after_value)
                            VALUES ('COMPLEX', 1, 'CREATE_HOUSING_TYPE', 'admin', now(), '{}',
                                    '{"id":11,"name":"16A","exclusiveArea":16.37,"householdCount":null}'),
                                ('COMPLEX', 1, 'CREATE_HOUSING_TYPE', 'admin', now(), '{}',
                                    '{"id":12,"name":"26A","exclusiveArea":26.01,"householdCount":20}'),
                                ('COMPLEX', 1, 'UPDATE_HOUSING_TYPE', 'admin', now(),
                                    '{"id":13,"name":"36","exclusiveArea":36.91,"householdCount":null}',
                                    '{"id":13,"name":"36","exclusiveArea":36.91,"householdCount":30}'),
                                ('COMPLEX', 1, 'UPDATE_HOUSING_TYPE', 'admin', now(),
                                    '{"id":14,"name":"46","exclusiveArea":46.0,"householdCount":5}',
                                    '{"id":14,"name":"46B","exclusiveArea":46.0,"householdCount":5}'),
                                ('COMPLEX', 2, 'CREATE_HOUSING_TYPE', 'admin', now(), '{}',
                                    '{"id":16,"name":"84","exclusiveArea":84.9,"householdCount":3}')
                            """);
                }

                Flyway flyway = migration(database).target("20261102.01").load();
                assertEquals(1, flyway.migrate().migrationsExecuted);
                flyway.validate();
                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    Map<Long, String> flags = new LinkedHashMap<>();
                    try (var result = query.executeQuery("""
                            SELECT id, name_admin_modified, household_count_admin_modified, announcement_observed_at
                            FROM housing_types ORDER BY id
                            """)) {
                        while (result.next()) {
                            assertNull(result.getTimestamp("announcement_observed_at"));
                            flags.put(result.getLong("id"), result.getBoolean("name_admin_modified") + ","
                                    + result.getBoolean("household_count_admin_modified"));
                        }
                    }
                    assertEquals(Map.of(
                            11L, "true,false",
                            12L, "true,true",
                            13L, "false,true",
                            14L, "true,false",
                            15L, "false,false",
                            16L, "false,false"), flags);
                    try (var history = query.executeQuery(
                            "SELECT script FROM flyway_schema_history WHERE success AND version = '20261102.01'")) {
                        assertTrue(history.next());
                    }
                }
            } finally {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
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
        return PostgresTestDatabases.primaryUrl(database);
    }
}
