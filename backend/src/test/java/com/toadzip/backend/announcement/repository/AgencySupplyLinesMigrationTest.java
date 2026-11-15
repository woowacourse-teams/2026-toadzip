package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
 * V20261115_02가 관리자 흔적 없는 LH 원천 소유 정본과 손대지 않은 GH 정제 틀을 기관 준비 줄로 옮기고 정본에서 지우는지, 정본이 남는
 * 공고(공고문 소유, 관리자 흔적 있는 LH 원천 소유, LH 공고 ID 없는 등록)는 확정하는지 본다. 준비 줄은 옮기기 전 칸의 형명·대상·
 * 세대수·금액을 그대로 가져야 공개 화면 값이 같다.
 */
class AgencySupplyLinesMigrationTest {

    @Test
    void 원천_소유_정본은_준비_줄로_옮기고_정본이_남는_공고는_확정한다() throws Exception {
        String database = "agency_supply_lines_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261115.01").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seedOldShape(seed);
                }

                Flyway flyway = migration(database).target("20261115.02").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                flyway.validate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    assertThat(rows(query, """
                            SELECT announcement_id || '|' || agency || '|' || source_line_key || '|' || display_order
                                || '|' || COALESCE(announcement_complex_id::text, '-') || '|' || complex_name || '|'
                                || housing_type_name || '|' || COALESCE(target_label, '-') || '|'
                                || COALESCE(household_count::text, '-') || '|'
                                || COALESCE(exclusive_area::text, '-') || '|'
                                || COALESCE(rental_deposit::text, '-') || '/' || COALESCE(monthly_rent::text, '-')
                                || '|' || supply_category
                            FROM agency_supply_lines ORDER BY announcement_id, display_order
                            """)).containsExactly(
                            // 칸마다 한 줄: 형명은 이은 형명 중 그 표기의 형명, 세대수는 그 칸만 멤버인 모집수, 금액은 칸의
                            // 임대조건이다. 두 칸이 함께 쓰는 모집수(합 9)는 칸 하나의 수가 아니라 옮기지 않는다.
                            "1|LH|MIGRATED:111|0|101|행복단지|26A(청년)|청년|5|26.0000|1000.00/10.00|NEW_SUPPLY",
                            "1|LH|MIGRATED:112|1|101|행복단지|26A(고령자)|고령자|3|26.0000|-/-|NEW_SUPPLY",
                            // '전체'만 있는 행은 대상 표기 없이 형명 원문 그대로다.
                            "1|LH|MIGRATED:121|2|101|행복단지|36A|-|7|36.0000|-/-|NEW_SUPPLY",
                            // GH는 손대지 않은 틀이든 아니든 지구 원천으로 만든다. 같은 키는 #2를 붙이고 숫자만 읽는다.
                            "4|GH|GH:9:다산:A1|0|401|다산|36A 청년|-|60|36.8000|-/-|RESUPPLY",
                            "4|GH|GH:9:다산:A1#2|1|401|다산|36A 신혼|-|-|-|-/-|RESUPPLY",
                            "11|GH|GH:11:다산:B1|0|1101|다산|46A|-|3|-|-/-|NEW_SUPPLY",
                            "12|GH|GH:12:다산:B1|0|1201|다산|46A|-|3|-|-/-|NEW_SUPPLY",
                            "14|GH|GH:14:다산:B1|0|1401|다산|46A|-|3|-|-/-|NEW_SUPPLY");
                    // 원천 공급면적 없이 주택타입만 이은 옛 행은 그 주택타입의 공급면적을 준비 줄로 옮긴다.
                    assertThat(rows(query, "SELECT COALESCE(supply_area::text, '-') FROM agency_supply_lines"
                            + " WHERE source_line_key = 'MIGRATED:121'")).containsExactly("60.5000");

                    assertThat(rows(query, """
                            SELECT id || ' ' || CASE WHEN supply_confirmed_at IS NULL THEN '준비'
                                WHEN supply_confirmed_at = TIMESTAMPTZ '2026-11-10 00:00:00+00' THEN '확정(관리자 시각)'
                                ELSE '확정' END
                            FROM announcements ORDER BY id
                            """)).containsExactly("1 준비", "2 확정", "3 확정(관리자 시각)", "4 준비", "5 확정",
                            "6 준비", "7 확정", "8 확정", "9 확정", "10 확정", "11 확정", "12 확정", "13 확정(관리자 시각)",
                            "14 확정(관리자 시각)");
                    // 옮긴 공고(1·4)의 정본 행·대상·묶음·임대조건이 지워지고 나머지는 그대로다.
                    assertThat(rows(query, "SELECT announcement_id || ' ' || id FROM supply_rows ORDER BY id"))
                            .containsExactly("2 21", "3 31", "5 51", "7 71", "8 81", "9 91", "10 95", "11 1110",
                                    "12 1210");
                    assertThat(rows(query, """
                            SELECT DISTINCT announcement_id::text FROM (
                                SELECT announcement_id FROM announcement_supply_targets
                                UNION ALL SELECT announcement_id FROM supply_quotas
                                UNION ALL SELECT announcement_id FROM lease_terms) owned ORDER BY 1
                            """)).containsExactly("10", "2", "9");
                    assertThat(rows(query, """
                            SELECT column_name FROM information_schema.columns
                            WHERE table_name = 'announcements' AND column_name IN ('supply_owner', 'supply_confirmed_at')
                            """)).containsExactly("supply_confirmed_at");
                    // 관리자가 공고 단지의 단지 연결을 풀 수 있다(단지 없음 + ADMIN).
                    query.execute("UPDATE announcement_complexes SET housing_complex_id = NULL, link_method = 'ADMIN'"
                            + " WHERE id = 101");
                }
            } finally {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /**
     * 공고 1: 손대지 않은 LH 원천 소유(옮김). 2: 관리자가 고친 대상이 있는 LH 원천 소유(남김). 3: 공고문 소유(남김, 관리자 시각).
     * 4: 손대지 않은 GH 정제 틀(옮김). 5: LH 공고 ID 없는 원천 소유 등록 공고(남김). 6: 공급행 없는 LH 원천 소유(준비).
     * 7~10: 관리자가 고친 행·제외한 행·고친 모집수·고친 임대조건이 있는 LH 원천 소유(남김). 11: 공개한 GH, 12: 관리자가 단지를 이은
     * GH(옛 GH 정제가 관리자가 맡은 공고로 봄, 남김, 준비 줄은 만든다).
     */
    private static void seedOldShape(Statement seed) throws SQLException {
        seed.execute("""
                INSERT INTO housing_complexes (id, name, source_complex_identifier, city_county_district_code, latitude,
                    legal_dong_code, longitude, province_code, road_address, provider, supply_type)
                VALUES (1, '행복단지', 'manual:1', '11140', 37.5, '1114010100', 127, '11', '주소', 'LH', 'HAPPY_HOUSING')
                """);
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider, source_announcement_identifier,
                    status, view_count, release_status, supply_owner, lh_pan_id, admin_updated_at)
                VALUES (1, '공고 1', 'https://example.com/1', DATE '2026-11-01', 'LH', 'lh-1', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P1', NULL),
                    (2, '공고 2', 'https://example.com/2', DATE '2026-11-01', 'LH', 'lh-2', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P2', NULL),
                    (3, '공고 3', 'https://example.com/3', DATE '2026-11-01', 'LH', 'lh-3', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'ANNOUNCEMENT_DOCUMENT', 'P3', TIMESTAMPTZ '2026-11-10 00:00:00+00'),
                    (4, '예비입주자 모집', 'https://example.com/4', DATE '2026-11-01', 'GH', 'GH:9', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'ANNOUNCEMENT_DOCUMENT', NULL, NULL),
                    (5, '공고 5', 'https://example.com/5', DATE '2026-11-01', 'ETC', 'ADMIN_ENTRY-ANNOUNCEMENT-5',
                        'ORIGINAL', 0, 'UNCONFIRMED', 'LH_SOURCE', NULL, NULL),
                    (6, '공고 6', 'https://example.com/6', DATE '2026-11-01', 'LH', 'lh-6', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P6', NULL),
                    (7, '공고 7', 'https://example.com/7', DATE '2026-11-01', 'LH', 'lh-7', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P7', NULL),
                    (8, '공고 8', 'https://example.com/8', DATE '2026-11-01', 'LH', 'lh-8', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P8', NULL),
                    (9, '공고 9', 'https://example.com/9', DATE '2026-11-01', 'LH', 'lh-9', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P9', NULL),
                    (10, '공고 10', 'https://example.com/10', DATE '2026-11-01', 'LH', 'lh-10', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE', 'P10', NULL),
                    (11, '공고 11', 'https://example.com/11', DATE '2026-11-01', 'GH', 'GH:11', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'ANNOUNCEMENT_DOCUMENT', NULL, NULL),
                    (12, '공고 12', 'https://example.com/12', DATE '2026-11-01', 'GH', 'GH:12', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'ANNOUNCEMENT_DOCUMENT', NULL, NULL)
                """);
        seed.execute("""
                INSERT INTO announcement_complexes (id, announcement_id, source_complex_name, housing_complex_id,
                    link_method, linked_by, linked_at)
                VALUES (101, 1, '행복단지', 1, 'AUTO', 'refiner', CURRENT_TIMESTAMP),
                    (201, 2, '행복단지', NULL, NULL, NULL, NULL), (301, 3, '행복단지', NULL, NULL, NULL, NULL),
                    (401, 4, '다산', NULL, NULL, NULL, NULL), (501, 5, '등록단지', NULL, NULL, NULL, NULL),
                    (701, 7, '행복단지', NULL, NULL, NULL, NULL), (801, 8, '행복단지', NULL, NULL, NULL, NULL),
                    (901, 9, '행복단지', NULL, NULL, NULL, NULL), (1001, 10, '행복단지', NULL, NULL, NULL, NULL),
                    (1101, 11, '다산', NULL, NULL, NULL, NULL),
                    (1201, 12, '다산', 1, 'ADMIN', 'operator', CURRENT_TIMESTAMP)
                """);
        seed.execute("""
                INSERT INTO supply_rows (id, announcement_id, announcement_complex_id, source_supply_row_identifier,
                    display_order, source_complex_name, source_housing_type_name, supply_category, lh_exclusive_area)
                VALUES (11, 1, 101, 'LH:P1:1', 0, '행복단지', '26A(청년) · 26A(고령자)', 'NEW_SUPPLY', 26.0),
                    (12, 1, 101, 'LH:P1:2', 1, '행복단지', '36A', 'NEW_SUPPLY', NULL),
                    (21, 2, 201, 'LH:P2:1', 0, '행복단지', '26A', 'NEW_SUPPLY', NULL),
                    (31, 3, 301, 'LH:P3:1', 0, '행복단지', '26A', 'NEW_SUPPLY', NULL),
                    (41, 4, 401, 'GH:9:다산:A1', 0, '다산', '36A 청년', 'RESUPPLY', NULL),
                    (51, 5, 501, 'row-51', 0, '등록단지', '26A', 'NEW_SUPPLY', NULL),
                    (71, 7, 701, 'LH:P7:1', 0, '행복단지', '26A', 'NEW_SUPPLY', NULL),
                    (81, 8, 801, 'LH:P8:1', 0, '행복단지', '26A', 'NEW_SUPPLY', NULL),
                    (91, 9, 901, 'LH:P9:1', 0, '행복단지', '26A', 'NEW_SUPPLY', NULL),
                    (95, 10, 1001, 'LH:P10:1', 0, '행복단지', '26A', 'NEW_SUPPLY', NULL),
                    (1110, 11, 1101, 'GH:11:다산:B1', 0, '다산', '46A', 'NEW_SUPPLY', NULL),
                    (1210, 12, 1201, 'GH:12:다산:B1', 0, '다산', '46A', 'NEW_SUPPLY', NULL)
                """);
        seed.execute("UPDATE announcements SET release_status = 'PUBLISHED', application_start_date = DATE '2026-11-02',"
                + " application_end_date = DATE '2026-11-03', winner_announcement_date = DATE '2026-12-01',"
                + " supply_type = 'HAPPY_HOUSING', recruitment_type = 'NEW' WHERE id = 11");
        // 원천 면적이 없고 옛 연결이 이어 둔 주택타입만 있는 행: 준비 줄은 그 주택타입 면적을 갖는다.
        seed.execute("INSERT INTO housing_types (id, housing_complex_id, name, exclusive_area, supply_area)"
                + " VALUES (31, 1, '36', 36.0, 60.5)");
        seed.execute("UPDATE supply_rows SET housing_type_id = 31 WHERE id = 12");
        seed.execute("UPDATE supply_rows SET admin_modified = true WHERE id = 71");
        seed.execute("UPDATE supply_rows SET admin_excluded = true WHERE id = 81");
        seed.execute("""
                INSERT INTO announcement_supply_targets (id, announcement_id, label, display_order, admin_modified)
                VALUES (1001, 1, '청년', 1, false), (1002, 1, '고령자', 2, false), (1003, 1, '전체', 0, false),
                    (2001, 2, '청년', 1, true), (4001, 4, '전체', 0, false), (9001, 9, '전체', 0, false),
                    (10001, 10, '전체', 0, false)
                """);
        seed.execute("""
                INSERT INTO supply_row_targets (id, supply_row_id, supply_target_id, display_order)
                VALUES (111, 11, 1001, 1), (112, 11, 1002, 2), (121, 12, 1003, 0), (211, 21, 2001, 1),
                    (411, 41, 4001, 0), (911, 91, 9001, 0), (951, 95, 10001, 0)
                """);
        seed.execute("""
                INSERT INTO supply_quotas (id, announcement_id, kind, household_count)
                VALUES (1, 1, 'WINNER', 5), (2, 1, 'WINNER', 3), (3, 1, 'WINNER', 9), (4, 1, 'WINNER', 7),
                    (5, 2, 'WINNER', 4), (6, 4, 'WINNER', 60), (7, 9, 'WINNER', 2)
                """);
        seed.execute("UPDATE supply_quotas SET admin_modified = true WHERE id = 7");
        seed.execute("""
                INSERT INTO supply_quota_members (supply_quota_id, supply_row_target_id)
                VALUES (1, 111), (2, 112), (3, 111), (3, 112), (4, 121), (5, 211), (6, 411), (7, 911)
                """);
        seed.execute("""
                INSERT INTO lease_terms (id, announcement_id, rental_deposit, monthly_rent)
                VALUES (1, 1, 1000, 10), (2, 2, 500, 5), (3, 10, 700, 7)
                """);
        seed.execute("UPDATE lease_terms SET admin_modified = true WHERE id = 3");
        seed.execute("INSERT INTO lease_term_members (lease_terms_id, supply_row_target_id) VALUES (1, 111), (2, 211),"
                + " (3, 951)");
        seed.execute("""
                INSERT INTO gh_announcement_source (source_key, pbanc_no, board, kind_code, title, supply_type, region,
                    posted_date, original_url, list_url, districts, collected_at)
                VALUES ('GH:9', '9', 'RENTAL_HOUSE', '01', '다산 예비입주자 모집', '행복주택', '남양주', DATE '2026-11-01',
                    'https://example.com/gh', 'https://example.com/gh-list',
                    '[{"name":" 다산 ","housingTypes":[
                        {"name":"36A 청년","houseTypeCode":"A1","supplyCount":"60","exclusiveArea":"36.8"},
                        {"name":"36A 신혼","houseTypeCode":"A1","supplyCount":"미정","exclusiveArea":""}]},
                      {"name":"공고 단지 없는 지구","housingTypes":[{"name":"46A","supplyCount":"3"}]}]',
                    CURRENT_TIMESTAMP),
                  ('GH:11', '11', 'RENTAL_HOUSE', '01', '다산 입주자 모집', '행복주택', '남양주', DATE '2026-11-01',
                    'https://example.com/gh11', 'https://example.com/gh-list',
                    '[{"name":"다산","housingTypes":[{"name":"46A","houseTypeCode":"B1","supplyCount":"3"}]}]',
                    CURRENT_TIMESTAMP),
                  ('GH:12', '12', 'RENTAL_HOUSE', '01', '다산 입주자 모집', '행복주택', '남양주', DATE '2026-11-01',
                    'https://example.com/gh12', 'https://example.com/gh-list',
                    '[{"name":"다산","housingTypes":[{"name":"46A","houseTypeCode":"B1","supplyCount":"3"}]}]',
                    CURRENT_TIMESTAMP)
                """);
        // 관리자가 넘겨받은 뒤 공급행을 모두 지운 LH 공고. 행이 없어도 확정해 다음 정제의 준비 데이터로 돌아가지 않는다.
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider, source_announcement_identifier,
                    status, view_count, release_status, supply_owner, lh_pan_id, admin_updated_at)
                VALUES (13, '공고 13', 'https://example.com/13', DATE '2026-11-01', 'LH', 'lh-13', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'ANNOUNCEMENT_DOCUMENT', 'P13', TIMESTAMPTZ '2026-11-10 00:00:00+00')
                """);
        // 관리자가 맡은 뒤 공급행을 모두 지운 GH 공고. 준비 줄은 만들지만 확정해 그 줄이 화면에 되살아나지 않는다.
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider, source_announcement_identifier,
                    status, view_count, release_status, supply_owner, lh_pan_id, admin_updated_at)
                VALUES (14, '공고 14', 'https://example.com/14', DATE '2026-11-01', 'GH', 'GH:14', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'ANNOUNCEMENT_DOCUMENT', NULL, TIMESTAMPTZ '2026-11-10 00:00:00+00')
                """);
        seed.execute("INSERT INTO announcement_complexes (id, announcement_id, source_complex_name) VALUES (1401, 14, '다산')");
        seed.execute("""
                INSERT INTO gh_announcement_source (source_key, pbanc_no, board, kind_code, title, supply_type, region,
                    posted_date, original_url, list_url, districts, collected_at)
                VALUES ('GH:14', '14', 'RENTAL_HOUSE', '01', '다산 입주자 모집', '행복주택', '남양주', DATE '2026-11-01',
                    'https://example.com/gh14', 'https://example.com/gh-list',
                    '[{"name":"다산","housingTypes":[{"name":"46A","houseTypeCode":"B1","supplyCount":"3"}]}]',
                    CURRENT_TIMESTAMP)
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
