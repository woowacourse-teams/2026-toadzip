package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V20261115_03이 공급행의 원천 시절 열을 지우면서 공개 화면이 보이던 주택형 이름·면적·단지 연결을 행의 정본 열로 그대로 옮기는지 본다.
 * 옮기기 전 공개 규칙: 이름은 공고 표시명 → 연결 주택타입명 → 원천 주택형명, 면적은 공고 면적을 고정했으면 공고 면적 아니면 연결
 * 주택타입 면적, 단지 연결은 공고 단지({@link AnnouncementHousingComplex#LINKS}).
 */
class SupplyRowCanonicalColumnsMigrationTest {

    private static final String PUBLIC_LINKS = "SELECT announcement_id || ' ' || housing_complex_id FROM ("
            + AnnouncementHousingComplex.LINKS + ") link ORDER BY announcement_id, housing_complex_id";

    @Test
    void 공개_화면이_보이던_이름_면적_연결을_정본_열로_옮기고_원천_열을_지운다() throws Exception {
        String database = "supply_row_canonical_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261115.02").load().migrate();
                List<String> linksBefore;
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seedOldShape(seed);
                    seedReviews(seed);
                    linksBefore = rows(seed, PUBLIC_LINKS);
                }

                Flyway flyway = migration(database).target("20261115.03").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                flyway.validate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    assertThat(rows(query, """
                            SELECT r.id || '|' || ac.source_complex_name || '|' || r.housing_type_name || '|'
                                || COALESCE(trim_scale(r.exclusive_area)::text, '-') || '|'
                                || COALESCE(trim_scale(r.supply_area)::text, '-') || '|'
                                || COALESCE(r.housing_type_id::text, '-')
                            FROM supply_rows r JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
                            ORDER BY r.id
                            """)).containsExactly(
                            // 공고 표시명과 고정한 공고 면적은 그대로다.
                            "11|행복단지|26A 청년|26.5|34|21",
                            // 공고 표시명이 없으면 공개 화면처럼 연결 주택타입명, 면적은 연결 주택타입 면적이다.
                            "12|행복단지|26A|26|33|21",
                            // 면적을 고정하면 비어 있어도 공고 면적(없음)이다.
                            "13|행복단지|36A|-|-|22",
                            // 주택타입이 없으면 원천 주택형명이고 면적은 없다.
                            "14|행복단지|46B 원문|-|-|-",
                            // 휴지통 주택타입은 공개 화면에도 보이지 않으므로 원천 주택형명이다.
                            "15|행복단지|59C 원문|-|-|23",
                            // 공고 단지가 없던 행은 (공고, 단지명) 공고 단지에 든다.
                            "21|새단지|26A|26|33|21",
                            // 공개 화면이 보이지 않던 면적: 주택타입이 없는 행은 LH 전용면적으로 채운다.
                            "31|다른단지|84A|84.1|-|-");
                    assertThat(rows(query, """
                            SELECT announcement_id || ' ' || source_complex_name || ' '
                                || COALESCE(housing_complex_id::text, '-') || ' ' || COALESCE(link_method, '-') || ' '
                                || COALESCE(linked_by, '-')
                            FROM announcement_complexes WHERE announcement_id = 2
                            """)).containsExactly("2 새단지 905 MIGRATED V20261115_03");
                    // 공고 2는 확정 전이라 준비 데이터 시절에도 공고 단지가 연결이었다. 새로 생긴 공고 단지가 그 공고의 연결로 보인다.
                    List<String> linksAfter = rows(query, PUBLIC_LINKS);
                    assertThat(linksAfter).containsAll(linksBefore);
                    assertThat(linksAfter).containsExactly("1 905", "2 905", "3 906");
                    // 표현만 바뀐 검토 값은 옮겨져 확인한 그대로이고, 이미 낡았던 검토는 낡은 채다.
                    assertThat(rows(query, "SELECT target || ' ' || status FROM (" + SupplyReviewSql.EVALUATED
                            + ") evaluated WHERE latest ORDER BY target")).containsExactly(
                            "row:11 STALE", "row:12 VERIFIED", "row:21 VERIFIED");
                    assertThat(rows(query, """
                            SELECT column_name FROM information_schema.columns WHERE table_name = 'supply_rows'
                            ORDER BY column_name
                            """)).containsExactly("admin_excluded", "admin_modified", "announcement_complex_id",
                            "announcement_id", "display_order", "exclusive_area", "expected_move_in_month",
                            "housing_type_id", "housing_type_name", "id", "supply_area", "supply_category",
                            "supply_pnu");

                    assertThatThrownBy(() -> query.execute("""
                            INSERT INTO supply_rows (announcement_id, display_order, housing_type_name, supply_category)
                            VALUES (1, 9, '빈 공고 단지', 'NEW_SUPPLY')
                            """)).isInstanceOf(SQLException.class).hasMessageContaining("announcement_complex_id");
                    assertThatThrownBy(() -> query.execute("UPDATE supply_rows SET exclusive_area = -1 WHERE id = 11"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("ck_supply_rows_areas");
                    // 주택형이 든 공고 단지는 지울 수 없다. 주택형을 먼저 지우면 공고 삭제는 공고 단지까지 지운다.
                    assertThatThrownBy(() -> query.execute("DELETE FROM announcement_complexes WHERE id = 101"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("supply_rows");
                    query.execute("DELETE FROM supply_rows WHERE announcement_id = 3");
                    query.execute("DELETE FROM announcements WHERE id = 3");
                    assertThat(rows(query,
                            "SELECT count(*)::text FROM announcement_complexes WHERE announcement_id = 3"))
                            .containsExactly("0");
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
                VALUES (905, '행복단지', 'manual:905', '11140', 37.5, '1114010100', 127, '11', '주소', 'LH',
                        'HAPPY_HOUSING'),
                    (906, '다른단지', 'manual:906', '11140', 37.5, '1114010100', 127, '11', '주소', 'LH',
                        'HAPPY_HOUSING')
                """);
        seed.execute("""
                INSERT INTO housing_types (id, housing_complex_id, name, exclusive_area, supply_area, admin_deleted)
                VALUES (21, 905, '26A', 26.0, 33.0, false), (22, 905, '36A', 36.0, 45.0, false),
                    (23, 905, '59C', 59.0, 70.0, true)
                """);
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider,
                    source_announcement_identifier, status, view_count, release_status, supply_confirmed_at)
                VALUES (1, '확정 공고', 'https://example.com/1', DATE '2026-11-01', 'LH', 'canonical-1', 'ORIGINAL', 0,
                        'UNCONFIRMED', CURRENT_TIMESTAMP),
                    (2, '확정 전 공고', 'https://example.com/2', DATE '2026-11-01', 'LH', 'canonical-2', 'ORIGINAL', 0,
                        'UNCONFIRMED', NULL),
                    (3, '단지 없는 행 공고', 'https://example.com/3', DATE '2026-11-01', 'LH', 'canonical-3', 'ORIGINAL',
                        0, 'UNCONFIRMED', CURRENT_TIMESTAMP)
                """);
        seed.execute("""
                INSERT INTO announcement_complexes (id, announcement_id, source_complex_name, housing_complex_id,
                    link_method, linked_by, linked_at)
                VALUES (101, 1, '행복단지', 905, 'ADMIN', 'admin', CURRENT_TIMESTAMP),
                    (301, 3, '다른단지', 906, 'AUTO', 'LH 공고 정제', CURRENT_TIMESTAMP)
                """);
        seed.execute("""
                INSERT INTO supply_rows (id, announcement_id, announcement_complex_id, housing_complex_id,
                    housing_type_id, source_supply_row_identifier, lh_source_supply_row_identifier, display_order,
                    source_complex_name, source_housing_type_name, announcement_housing_type_name, area_override,
                    announcement_exclusive_area, announcement_supply_area, lh_exclusive_area, lh_match_status,
                    matching_failure_reason, supply_category)
                VALUES (11, 1, 101, 905, 21, 'row-11', 'LH:1', 0, '행복단지', '26A 청년, 신혼부부', '26A 청년', true,
                        26.5, 34.0, 26.4, 'MATCHED', NULL, 'NEW_SUPPLY'),
                    (12, 1, 101, 905, 21, 'row-12', NULL, 1, '행복단지', '26A 신혼부부, 한부모가족', NULL, false,
                        NULL, NULL, 26.4, NULL, NULL, 'NEW_SUPPLY'),
                    (13, 1, 101, 905, 22, 'row-13', NULL, 2, '행복단지', '36A', NULL, true, NULL, NULL, 36.2, NULL,
                        NULL, 'NEW_SUPPLY'),
                    (14, 1, 101, NULL, NULL, 'row-14', NULL, 3, '행복단지', '46B 원문', NULL, false, NULL, NULL, NULL,
                        'COMPLEX_UNLINKED', '단지 없음', 'NEW_SUPPLY'),
                    (15, 1, 101, 905, 23, 'row-15', NULL, 4, '행복단지', '59C 원문', NULL, false, NULL, NULL, NULL,
                        NULL, NULL, 'NEW_SUPPLY'),
                    (21, 2, NULL, 905, 21, 'row-21', NULL, 0, ' 새단지 ', '26A', NULL, false, NULL, NULL, NULL, NULL,
                        NULL, 'NEW_SUPPLY'),
                    (31, 3, 301, 906, NULL, 'row-31', NULL, 0, '다른단지', '84A', NULL, false, NULL, NULL, 84.1, NULL,
                        NULL, 'NEW_SUPPLY')
                """);
    }

    /** 옮기기 전 TARGET_VALUES 표현으로 확인한 검토. 행 11은 이름이 이미 낡은 검토다. */
    private static void seedReviews(Statement seed) throws SQLException {
        seed.execute("""
                INSERT INTO admin_supply_reviews (announcement_id, target, outcome, checked_values, evidence, note,
                    source_url, actor)
                VALUES (1, 'row:11', 'VERIFIED', '{"name": "옛 이름", "exclusiveArea": 26.5}', '[]', '확인',
                        'https://example.com/1', 'admin'),
                    (1, 'row:12', 'VERIFIED', '{"complexName": "행복단지", "name": "26A 신혼부부, 한부모가족",
                        "housingComplexId": 905, "housingTypeId": 21, "exclusiveArea": null, "supplyArea": null,
                        "displayOrder": 1}', '[]', '확인', 'https://example.com/1', 'admin'),
                    (2, 'row:21', 'VERIFIED', '{"complexName": " 새단지 ", "name": "26A", "housingComplexId": 905,
                        "exclusiveArea": null}', '[]', '확인', 'https://example.com/2', 'admin')
                """);
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
