package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.AnnouncementHousingComplex;
import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

/**
 * V20261115_01이 공고 단지 없이 단지만 가리키던 공급행(공고 20·48·54처럼 관리자 등록·JSON 등록으로 만든 행)에 공고 단지를 만들어
 * 잇는지 본다. 공고↔단지 연결은 공고 단지로만 정하므로(AnnouncementHousingComplex.LINKS), 옮긴 뒤에도 행이 가리키던 단지가
 * 그 공고의 연결로 남아야 한다.
 */
class SupplyRowAnnouncementComplexesMigrationTest {

    @Test
    void 공고_단지_없는_공급행에_같은_공고_같은_단지명이면_공고_단지_하나를_만들어_행의_단지로_잇는다() throws Exception {
        String database = "supply_row_complexes_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261114.01").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seedOldShape(seed);
                    // 옮기기 전에는 공고 20·48·54·70·80이 공고 단지가 없어 연결이 없고, 공고 61은 이미 이어진 공고 단지로만 보인다.
                    assertThat(links(seed)).containsExactly("61 905");
                }

                Flyway flyway = migration(database).load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                flyway.validate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    assertThat(rows(query, """
                            SELECT announcement_id || ' ' || source_complex_name || ' '
                                || COALESCE(housing_complex_id::text, '-') || ' ' || COALESCE(link_method, '-') || ' '
                                || COALESCE(linked_by, '-')
                            FROM announcement_complexes ORDER BY announcement_id, source_complex_name
                            """)).containsExactly(
                            "20 김제하동 2961 MIGRATED V20261115_01",
                            "46 대전판암4 - - -",
                            "46 중촌2 - - -",
                            "48 통합공공임대 3161 MIGRATED V20261115_01",
                            // 이미 있던 같은 이름의 공고 단지에 잇고, 이어지지 않았던 그 공고 단지를 행의 단지로 잇는다.
                            "54 금호빛여울채 905 MIGRATED V20261115_01",
                            // 이미 다른 단지로 이어진 공고 단지는 관리자 연결을 그대로 둔다.
                            "61 단지X 905 ADMIN admin",
                            // 앞 행이 단지를 모르면 단지를 아는 뒤 행의 단지로 잇는다.
                            "70 늦게정한단지 2961 MIGRATED V20261115_01",
                            // 제외한 앞 행의 단지가 아니라 제외하지 않은 행의 단지로 잇는다.
                            "80 제외앞행 2961 MIGRATED V20261115_01");
                    // 모든 행이 이름이 같은 공고 단지에 든다. 같은 공고·같은 단지명의 두 행은 한 공고 단지에 든다.
                    assertThat(rows(query, """
                            SELECT r.id || ' ' || COALESCE(ac.announcement_id || ' ' || ac.source_complex_name, '-')
                            FROM supply_rows r LEFT JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
                            ORDER BY r.id
                            """)).containsExactly(
                            "201 20 김제하동", "202 20 김제하동", "461 46 대전판암4", "462 46 중촌2", "463 46 중촌2",
                            "481 48 통합공공임대", "541 54 금호빛여울채", "611 61 단지X", "612 61 단지X",
                            "701 70 늦게정한단지", "702 70 늦게정한단지", "801 80 제외앞행", "802 80 제외앞행");
                    assertThat(links(query)).containsExactly("20 2961", "48 3161", "54 905", "61 905", "70 2961",
                            "80 2961");
                }
            } finally {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private static void seedOldShape(Statement seed) throws SQLException {
        seed.execute("""
                INSERT INTO housing_complexes (id, name, source_complex_identifier, city_county_district_code, latitude,
                    legal_dong_code, longitude, province_code, road_address, provider, supply_type)
                VALUES (905, '금호빛여울채', 'manual:905', '29170', 35.1, '2917010100', 126.8, '29', '주소', 'LH',
                        'HAPPY_HOUSING'),
                    (2961, '김제하동', 'manual:2961', '45210', 35.8, '4521010100', 126.9, '45', '주소', 'LH',
                        'HAPPY_HOUSING'),
                    (3161, '통합공공임대', 'manual:3161', '11140', 37.5, '1114010100', 127, '11', '주소', 'SH',
                        'HAPPY_HOUSING')
                """);
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider,
                    source_announcement_identifier, status, view_count, release_status)
                SELECT id, '공고 ' || id, 'https://example.com/' || id, DATE '2026-11-01', 'LH', 'complexes-' || id,
                       'ORIGINAL', 0, 'UNCONFIRMED'
                FROM (VALUES (20), (46), (48), (54), (61), (70), (80)) announcement(id)
                """);
        seed.execute("""
                INSERT INTO announcement_complexes (id, announcement_id, source_complex_name, housing_complex_id,
                    link_method, linked_by, linked_at)
                VALUES (5401, 54, '금호빛여울채', NULL, NULL, NULL, NULL),
                    (6101, 61, '단지X', 905, 'ADMIN', 'admin', CURRENT_TIMESTAMP)
                """);
        seed.execute("""
                INSERT INTO supply_rows (id, announcement_id, housing_complex_id, announcement_complex_id,
                    source_supply_row_identifier, display_order, source_complex_name, source_housing_type_name,
                    supply_category)
                VALUES (201, 20, 2961, NULL, 'row-201', 0, '김제하동', '26A', 'NEW_SUPPLY'),
                    (202, 20, 2961, NULL, 'row-202', 1, '김제하동', '36A', 'NEW_SUPPLY'),
                    (461, 46, NULL, NULL, 'row-461', 0, '대전판암4', '26A', 'NEW_SUPPLY'),
                    (462, 46, NULL, NULL, 'row-462', 1, '중촌2', '26A', 'NEW_SUPPLY'),
                    (463, 46, NULL, NULL, 'row-463', 2, '중촌2', '36A', 'NEW_SUPPLY'),
                    (481, 48, 3161, NULL, 'row-481', 0, '통합공공임대', '26A', 'NEW_SUPPLY'),
                    (541, 54, 905, NULL, 'row-541', 0, ' 금호빛여울채 ', '26A', 'NEW_SUPPLY'),
                    (611, 61, 905, 6101, 'row-611', 0, '단지X', '26A', 'NEW_SUPPLY'),
                    (612, 61, 2961, NULL, 'row-612', 1, '단지X', '36A', 'NEW_SUPPLY'),
                    (701, 70, NULL, NULL, 'row-701', 0, '늦게정한단지', '26A', 'NEW_SUPPLY'),
                    (702, 70, 2961, NULL, 'row-702', 1, '늦게정한단지', '36A', 'NEW_SUPPLY')
                """);
        seed.execute("""
                INSERT INTO supply_rows (id, announcement_id, housing_complex_id, source_supply_row_identifier,
                    display_order, source_complex_name, source_housing_type_name, supply_category, admin_excluded)
                VALUES (801, 80, 905, 'row-801', 0, '제외앞행', '26A', 'NEW_SUPPLY', true),
                    (802, 80, 2961, 'row-802', 1, '제외앞행', '36A', 'NEW_SUPPLY', false)
                """);
    }

    private static List<String> links(Statement query) throws SQLException {
        return rows(query, "SELECT announcement_id || ' ' || housing_complex_id FROM ("
                + AnnouncementHousingComplex.LINKS + ") link ORDER BY announcement_id, housing_complex_id");
    }

    private static List<String> rows(Statement query, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var result = query.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
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
