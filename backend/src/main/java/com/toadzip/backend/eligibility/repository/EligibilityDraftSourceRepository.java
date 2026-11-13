package com.toadzip.backend.eligibility.repository;

import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Target;
import com.toadzip.backend.eligibility.domain.ExtractedEligibilityDraft.Unit;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** AI 추출 초안에 쓰는 공고의 현재 상태: PDF 보정을 적용한 공급행·대상, 모집공고일, 추출한 PDF의 첨부 주소. */
@Repository
public class EligibilityDraftSourceRepository {
    private final JdbcClient jdbc;

    public EligibilityDraftSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LocalDate> postedDate(long announcementId) {
        return jdbc.sql("SELECT posted_date FROM announcements WHERE id = ? AND NOT admin_deleted")
                .param(announcementId).query(LocalDate.class).optional();
    }

    public Optional<String> attachmentUrl(long announcementId, String fileName) {
        return jdbc.sql("""
                        SELECT file_url FROM announcement_attachments
                        WHERE announcement_id = ? AND file_name = ? AND file_url IS NOT NULL
                        ORDER BY display_order, id LIMIT 1
                        """)
                .params(announcementId, fileName).query(String.class).optional();
    }

    /** 관리자가 빼지 않은 공급행과 공급대상. 공급대상을 모두 뺀 공급행은 신청할 대상이 없으므로 함께 뺀다(대상이 처음부터 없는 행은 남긴다). 공통 모집수 묶음에 든 대상은 묶음의 모집수를 쓴다. 단지 지역은 주소의 시군구 코드다. */
    public List<Unit> units(long announcementId) {
        Map<Long, UnitRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.id, COALESCE(ac.source_complex_name, r.source_complex_name, hc.name) AS complex_label,
                               COALESCE(r.announcement_housing_type_name, r.source_housing_type_name, ht.name) AS type_label,
                               COALESCE(r.announcement_exclusive_area, ht.exclusive_area) AS exclusive_area,
                               hc.id AS complex_id, ht.id AS type_id, hc.city_county_district_code AS region_code,
                               COALESCE(a.supply_type, hc.supply_type) AS rental_type,
                               r.total_supply_household_count AS row_household_count,
                               t.target, COALESCE(t.supply_household_count, g.household_count) AS supply_household_count
                        FROM supply_rows r
                        JOIN announcements a ON a.id = r.announcement_id
                        LEFT JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
                        LEFT JOIN housing_complexes hc ON hc.id = COALESCE(r.housing_complex_id, ac.housing_complex_id)
                            AND NOT hc.admin_deleted
                        LEFT JOIN housing_types ht ON ht.id = r.housing_type_id AND NOT ht.admin_deleted
                        LEFT JOIN supply_targets t ON t.supply_row_id = r.id AND NOT t.admin_excluded
                        LEFT JOIN supply_target_count_groups g ON g.id = t.supply_count_group_id
                        WHERE r.announcement_id = ? AND NOT r.admin_excluded
                            AND (t.id IS NOT NULL
                                OR NOT EXISTS (SELECT 1 FROM supply_targets x WHERE x.supply_row_id = r.id))
                        ORDER BY r.display_order, r.id, t.display_order, t.id
                        """)
                .param(announcementId)
                .query((result, index) -> {
                    UnitRow read = new UnitRow(result.getLong("id"), string(result, "complex_label"),
                            string(result, "type_label"), decimal(result, "exclusive_area"), number(result, "complex_id"),
                            number(result, "type_id"), string(result, "rental_type"), string(result, "region_code"),
                            integer(result, "row_household_count"));
                    UnitRow row = rows.computeIfAbsent(read.id(), id -> read);
                    String target = string(result, "target");
                    if (target != null && !target.isBlank()) {
                        row.targets.add(new Target(target, integer(result, "supply_household_count")));
                    }
                    return row;
                })
                .list();
        return rows.values().stream().map(UnitRow::unit).toList();
    }

    /** rentalType은 저장된 공급유형 코드 원문이다. 서비스가 화면 이름으로 바꾼다. */
    private record UnitRow(long id, String complexLabel, String typeLabel, BigDecimal exclusiveArea, Long complexId,
                           Long typeId, String rentalType, String regionCode, Integer householdCount,
                           List<Target> targets) {
        private UnitRow(long id, String complexLabel, String typeLabel, BigDecimal exclusiveArea, Long complexId,
                        Long typeId, String rentalType, String regionCode, Integer householdCount) {
            this(id, complexLabel, typeLabel, exclusiveArea, complexId, typeId, rentalType, regionCode, householdCount,
                    new ArrayList<>());
        }

        private Unit unit() {
            return new Unit(id, complexLabel, typeLabel, exclusiveArea, complexId, typeId, rentalType,
                    regionCode == null ? List.of() : List.of(regionCode), List.copyOf(targets), householdCount);
        }
    }

    private static String string(ResultSet result, String column) throws SQLException {
        return result.getString(column);
    }

    private static BigDecimal decimal(ResultSet result, String column) throws SQLException {
        return result.getBigDecimal(column);
    }

    private static Long number(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer integer(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
