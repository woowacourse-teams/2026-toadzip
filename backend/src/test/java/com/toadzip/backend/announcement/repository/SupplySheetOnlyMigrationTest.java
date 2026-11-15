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
 * V20261114_01이 옛 테이블을 지우기 전에 여러 주택형을 함께 가리키던 공급행(supply_row_housing_types)을 주택형마다 한 행으로
 * 나누는지 본다. 옛 테이블로 공급표 트리거(V20261113_02)가 만든 공급표를 나눈 뒤에도 공고 모집수 합계가 같고, 나눈 행이 원래 행의
 * 칸과 수량 묶음·임대조건 멤버를 그대로 갖는지 본다.
 */
class SupplySheetOnlyMigrationTest {

    @Test
    void 접힌_공급행을_주택형마다_나누고_공고_모집수_합계는_그대로_둔_채_옛_구조를_지운다() throws Exception {
        String database = "supply_sheet_only_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261113.02").load().migrate();
                Snapshot before;
                Folded folded;
                long oldTargetSequence;
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seedOldShape(seed);
                    before = snapshot(seed);
                    folded = folded(seed);
                    oldTargetSequence = single(seed, "SELECT last_value FROM supply_targets_id_seq");
                }
                // 나눈 결과를 보려면 옛 행에 칸과 묶음·임대조건 멤버가 있어야 한다.
                assertThat(folded.cells()).isEqualTo(3);
                assertThat(folded.quotaMembers()).isPositive();
                assertThat(folded.leaseMembers()).isPositive();

                Flyway flyway = migration(database).target("20261114.01").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                flyway.validate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    Snapshot after = snapshot(query);
                    // 행 101은 주택형 12·13 몫으로 두 행이 더 생긴다. 다른 행과 다른 공고는 그대로다.
                    assertThat(after.rows()).isEqualTo(before.rows() + 2);
                    assertThat(after.cells()).isEqualTo(before.cells() + 2 * folded.cells());
                    assertThat(after.quotaMembers()).isEqualTo(before.quotaMembers() + 2 * folded.quotaMembers());
                    assertThat(after.leaseMembers()).isEqualTo(before.leaseMembers() + 2 * folded.leaseMembers());
                    assertThat(after.quotas()).isEqualTo(before.quotas());
                    assertThat(after.leases()).isEqualTo(before.leases());
                    assertThat(after.sums()).isEqualTo(before.sums());

                    assertThat(rows(query, """
                            SELECT display_order || ' ' || COALESCE(housing_type_id::text, '-') || ' '
                                || source_housing_type_name || ' ' || COALESCE(announcement_housing_type_name, '-')
                                || ' ' || source_supply_row_identifier || ' ' || admin_modified
                            FROM supply_rows WHERE announcement_id = 1 ORDER BY display_order
                            """)).containsExactly(
                            "0 11 26A/B/C 26A row-101 true",
                            "1 12 26B 26B SHEET:split:101:12 true",
                            "2 13 26C 26C SHEET:split:101:13 true",
                            "3 14 36A - row-102 false");
                    // 나눈 행의 칸은 원래 행과 같은 공급대상이고, 원래 칸이 든 묶음·임대조건에 모두 든다.
                    assertThat(rows(query, """
                            SELECT r.source_supply_row_identifier || ' ' || t.label || ' '
                                || (SELECT string_agg(q.kind || ':' || q.household_count, ',' ORDER BY q.kind,
                                        q.household_count)
                                    FROM supply_quota_members m JOIN supply_quotas q ON q.id = m.supply_quota_id
                                    WHERE m.supply_row_target_id = c.id) || ' '
                                || COALESCE((SELECT string_agg(l.rental_deposit::text, ',')
                                    FROM lease_term_members m JOIN lease_terms l ON l.id = m.lease_terms_id
                                    WHERE m.supply_row_target_id = c.id), '-')
                            FROM supply_row_targets c JOIN supply_rows r ON r.id = c.supply_row_id
                            JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                            WHERE r.announcement_id = 1 AND r.id <> 102
                            ORDER BY r.display_order, c.display_order, t.label
                            """)).isEqualTo(splitCells(folded.cellValues()));
                    assertThat(single(query, "SELECT count(*) FROM supply_rows WHERE announcement_id = 2")).isEqualTo(1);

                    for (String table : List.of("supply_targets", "supply_target_count_groups", "supply_row_groups",
                            "supply_row_housing_types", "lh_supply_row_links", "announcement_supply_match_decisions")) {
                        assertThat(rows(query, "SELECT to_regclass('" + table + "')::text")).as(table)
                                .containsExactly((String) null);
                    }
                    assertThat(rows(query, """
                            SELECT table_name || '.' || column_name FROM information_schema.columns
                            WHERE table_schema = current_schema()
                              AND ((table_name = 'supply_rows' AND column_name IN ('total_supply_household_count',
                                    'lh_total_supply_household_count_owned', 'lh_total_supply_household_count_enriched',
                                    'confirmed_target_fingerprint', 'target_counts_comparable', 'myhome_source_row_identifier',
                                    'lh_split_source_identifier', 'supply_group_id'))
                                OR (table_name = 'announcements' AND column_name = 'supply_sheet_from_document'))
                            """)).isEmpty();
                    assertThat(single(query, "SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'trg_supply_sheet_%'"))
                            .isZero();

                    // 칸 표가 스스로 ID를 만들고, 옛 대상 ID와 지금 칸 ID 다음부터 준다.
                    long maxCell = single(query, "SELECT max(id) FROM supply_row_targets");
                    long created = single(query, """
                            INSERT INTO supply_row_targets (supply_row_id, supply_target_id, display_order)
                            SELECT 102, id, 9 FROM announcement_supply_targets WHERE announcement_id = 1 LIMIT 1
                            RETURNING id
                            """);
                    assertThat(created).isGreaterThan(Math.max(maxCell, oldTargetSequence));
                }
            } finally {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    @Test
    void 나눈_이름이_이미_있는_행과_겹치면_나누지_않고_관리자가_고치거나_접힌_행이_있는_LH_원천_공고는_공고문_소유로_옮긴다() throws Exception {
        String database = "supply_sheet_only_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres"); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                migration(database).target("20261113.02").load().migrate();
                try (Connection connection = connect(database); Statement seed = connection.createStatement()) {
                    seedOverlaps(seed);
                }
                migration(database).load().migrate();

                try (Connection connection = connect(database); Statement query = connection.createStatement()) {
                    // 공고 3: 행 301(59A + 59A1)과 행 302(59A1 + 59A)는 서로의 주택형을 가리킨다. 나누면 59A·59A1이 두 번씩
                    // 생기므로 나누지 않고 원래 행 이름만 대표 주택형으로 맞춘다. 행 304(26A + 26B)의 26B는 행 303이 이미 갖는다.
                    assertThat(rows(query, """
                            SELECT announcement_id || ' ' || id || ' '
                                || COALESCE(announcement_housing_type_name, source_housing_type_name)
                            FROM supply_rows WHERE announcement_id = 3 ORDER BY id
                            """)).containsExactly("3 301 59A", "3 302 59A1", "3 303 26B", "3 304 26A");
                    assertThat(rows(query, "SELECT id || ' ' || supply_owner FROM announcements ORDER BY id"))
                            .containsExactly("3 ANNOUNCEMENT_DOCUMENT", "4 ANNOUNCEMENT_DOCUMENT", "5 LH_SOURCE",
                                    "6 ANNOUNCEMENT_DOCUMENT");
                }
            } finally {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /**
     * 이름이 겹치는 접힌 행과 LH 원천 공고 넷. 공고 3은 관리자가 고친 행이, 공고 4는 관리자가 고친 대상만 있고, 공고 5는 관리자
     * 손을 타지 않았다. 공고 6은 관리자 표시는 없지만 접힌 행(26A + 26B)이 있다.
     */
    private static void seedOverlaps(Statement seed) throws SQLException {
        seed.execute("""
                INSERT INTO housing_complexes (id, name, source_complex_identifier, city_county_district_code, latitude,
                    legal_dong_code, longitude, province_code, road_address, provider, supply_type)
                VALUES (1, '단지', 'manual:overlap-test', '11140', 37.5, '1114010100', 127, '11', '주소', 'LH',
                    'HAPPY_HOUSING')
                """);
        seed.execute("""
                INSERT INTO housing_types (id, housing_complex_id, name, exclusive_area)
                VALUES (21, 1, '59A', 59.0), (22, 1, '59A1', 59.1), (23, 1, '26A', 26.0), (24, 1, '26B', 26.1)
                """);
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider, source_announcement_identifier,
                    status, view_count, release_status, supply_owner)
                VALUES (3, '공고 3', 'https://example.com/3', DATE '2026-11-01', 'LH', 'overlap-3', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE'),
                    (4, '공고 4', 'https://example.com/4', DATE '2026-11-01', 'LH', 'overlap-4', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE'),
                    (5, '공고 5', 'https://example.com/5', DATE '2026-11-01', 'LH', 'overlap-5', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE'),
                    (6, '공고 6', 'https://example.com/6', DATE '2026-11-01', 'LH', 'overlap-6', 'ORIGINAL', 0,
                        'UNCONFIRMED', 'LH_SOURCE')
                """);
        seed.execute("""
                INSERT INTO supply_rows (id, announcement_id, housing_complex_id, housing_type_id,
                    source_supply_row_identifier, display_order, source_complex_name, source_housing_type_name,
                    supply_category, total_supply_household_count, admin_modified)
                VALUES (301, 3, 1, 21, 'row-301', 0, '단지', '59A/59A1', 'NEW_SUPPLY', 10, true),
                    (302, 3, 1, 22, 'row-302', 1, '단지', '59A1', 'NEW_SUPPLY', 10, false),
                    (303, 3, 1, 24, 'row-303', 2, '단지', '26B', 'NEW_SUPPLY', 10, false),
                    (304, 3, 1, 23, 'row-304', 3, '단지', '26A·26B', 'NEW_SUPPLY', 10, false),
                    (401, 4, 1, 21, 'row-401', 0, '단지', '59A', 'NEW_SUPPLY', 10, false),
                    (501, 5, 1, 21, 'row-501', 0, '단지', '59A', 'NEW_SUPPLY', 10, false),
                    (601, 6, 1, 23, 'row-601', 0, '단지', '26A·26B', 'NEW_SUPPLY', 10, false)
                """);
        seed.execute("""
                INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id)
                VALUES (301, 21), (301, 22), (302, 22), (302, 21), (303, 24), (304, 23), (304, 24), (401, 21), (501, 21), (601, 23), (601, 24)
                ON CONFLICT DO NOTHING
                """);
        seed.execute("""
                INSERT INTO supply_targets (supply_row_id, target, supply_household_count, display_order, admin_modified)
                VALUES (401, '청년', 10, 0, true), (501, '청년', 10, 0, false)
                """);
    }

    /**
     * 옛 구조의 공고 둘. 공고 1의 행 101은 대표 주택형 26A(11)와 함께 26B(12)·26C(13)를 가리키고, 대상 수와 함께 모집하는 수
     * 묶음(supply_target_count_groups)과 임대조건을 갖는다. 행 102와 공고 2의 행은 접히지 않았다.
     */
    private static void seedOldShape(Statement seed) throws SQLException {
        seed.execute("""
                INSERT INTO housing_complexes (id, name, source_complex_identifier, city_county_district_code, latitude,
                    legal_dong_code, longitude, province_code, road_address, provider, supply_type)
                VALUES (1, '단지', 'manual:split-test', '11140', 37.5, '1114010100', 127, '11', '주소', 'LH',
                    'HAPPY_HOUSING')
                """);
        seed.execute("""
                INSERT INTO housing_types (id, housing_complex_id, name, exclusive_area)
                VALUES (11, 1, '26A', 26.0), (12, 1, '26B', 26.1), (13, 1, '26C', 26.2), (14, 1, '36A', 36.0)
                """);
        seed.execute("""
                INSERT INTO announcements (id, name, original_url, posted_date, provider, source_announcement_identifier,
                    status, view_count, release_status)
                VALUES (1, '공고 1', 'https://example.com/1', DATE '2026-11-01', 'LH', 'split-1', 'ORIGINAL', 0,
                        'UNCONFIRMED'),
                    (2, '공고 2', 'https://example.com/2', DATE '2026-11-01', 'LH', 'split-2', 'ORIGINAL', 0,
                        'UNCONFIRMED')
                """);
        seed.execute("""
                INSERT INTO supply_rows (id, announcement_id, housing_complex_id, housing_type_id,
                    source_supply_row_identifier, display_order, source_complex_name, source_housing_type_name,
                    supply_category, total_supply_household_count, admin_modified)
                VALUES (101, 1, 1, 11, 'row-101', 0, '단지', '26A/B/C', 'NEW_SUPPLY', 30, true),
                    (102, 1, 1, 14, 'row-102', 1, '단지', '36A', 'NEW_SUPPLY', 10, false),
                    (201, 2, 1, 14, 'row-201', 0, '단지', '36A', 'NEW_SUPPLY', 5, false)
                """);
        seed.execute("""
                INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id)
                VALUES (101, 11), (101, 12), (101, 13), (102, 14), (201, 14)
                ON CONFLICT DO NOTHING
                """);
        seed.execute("INSERT INTO supply_target_count_groups (id, supply_row_id, kind, household_count)"
                + " VALUES (901, 101, 'SUPPLY', 8), (902, 101, 'RESERVE', 4)");
        seed.execute("""
                INSERT INTO supply_targets (supply_row_id, target, application_condition, supply_household_count,
                    reserve_count, supply_count_group_id, reserve_count_group_id, rental_deposit, monthly_rent,
                    display_order)
                VALUES (101, '청년', NULL, 10, 3, NULL, NULL, 1000000, 10000, 0),
                    (101, '신혼부부', '소득 있음', NULL, NULL, 901, 902, 2000000, NULL, 1),
                    (101, '고령자', NULL, NULL, NULL, 901, 902, NULL, NULL, 2),
                    (102, '청년', NULL, 4, NULL, NULL, NULL, 3000000, 20000, 0),
                    (201, '청년', NULL, 5, 1, NULL, NULL, 4000000, NULL, 0)
                """);
    }

    private static Snapshot snapshot(Statement query) throws SQLException {
        return new Snapshot(
                single(query, "SELECT count(*) FROM supply_rows"),
                single(query, "SELECT count(*) FROM supply_row_targets"),
                single(query, "SELECT count(*) FROM supply_quotas"),
                single(query, "SELECT count(*) FROM supply_quota_members"),
                single(query, "SELECT count(*) FROM lease_terms"),
                single(query, "SELECT count(*) FROM lease_term_members"),
                rows(query, """
                        SELECT announcement_id || ' ' || kind || ' ' || sum(household_count) FROM supply_quotas
                        GROUP BY announcement_id, kind ORDER BY 1
                        """));
    }

    private static Folded folded(Statement query) throws SQLException {
        return new Folded(
                single(query, "SELECT count(*) FROM supply_row_targets WHERE supply_row_id = 101"),
                single(query, """
                        SELECT count(*) FROM supply_quota_members m
                        JOIN supply_row_targets c ON c.id = m.supply_row_target_id WHERE c.supply_row_id = 101
                        """),
                single(query, """
                        SELECT count(*) FROM lease_term_members m
                        JOIN supply_row_targets c ON c.id = m.supply_row_target_id WHERE c.supply_row_id = 101
                        """),
                rows(query, """
                        SELECT t.label || ' '
                            || (SELECT string_agg(q.kind || ':' || q.household_count, ',' ORDER BY q.kind,
                                    q.household_count)
                                FROM supply_quota_members m JOIN supply_quotas q ON q.id = m.supply_quota_id
                                WHERE m.supply_row_target_id = c.id) || ' '
                            || COALESCE((SELECT string_agg(l.rental_deposit::text, ',')
                                FROM lease_term_members m JOIN lease_terms l ON l.id = m.lease_terms_id
                                WHERE m.supply_row_target_id = c.id), '-')
                        FROM supply_row_targets c JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                        WHERE c.supply_row_id = 101 ORDER BY c.display_order, t.label
                        """));
    }

    /** 원래 행의 칸 값이 원래 행과 나눈 두 행에 같은 순서로 나온다. */
    private static List<String> splitCells(List<String> originCells) {
        List<String> expected = new ArrayList<>();
        for (String row : List.of("row-101", "SHEET:split:101:12", "SHEET:split:101:13")) {
            originCells.forEach(cell -> expected.add(row + " " + cell));
        }
        return expected;
    }

    private static long single(Statement query, String sql) throws SQLException {
        try (var result = query.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
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

    private record Snapshot(long rows, long cells, long quotas, long quotaMembers, long leases, long leaseMembers,
            List<String> sums) {
    }

    private record Folded(long cells, long quotaMembers, long leaseMembers, List<String> cellValues) {
    }
}
