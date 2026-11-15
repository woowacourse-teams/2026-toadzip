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

    /**
     * 관리자가 빼지 않은 공급행(공고 주택형)과 그 공급표 칸의 공급대상. 칸이 없는 공급행(관리자가 대상을 모두 뺀 행)은 신청할
     * 대상이 없으므로 뺀다. 대상 수는 그 칸만의 모집수이고, 없으면 그 칸이 든
     * 가장 작은 모집수 묶음(병합된 수량 칸)의 수다. 단지 지역은 주소의 시군구 코드다.
     */
    public List<Unit> units(long announcementId) {
        Map<Long, UnitRow> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.id, ac.source_complex_name AS complex_label, r.housing_type_name AS type_label,
                               r.exclusive_area,
                               hc.id AS complex_id, ht.id AS type_id, hc.city_county_district_code AS region_code,
                               COALESCE(a.supply_type, hc.supply_type) AS rental_type,
                               t.label AS target, q.household_count AS supply_household_count
                        FROM supply_rows r
                        JOIN announcements a ON a.id = r.announcement_id
                        JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
                        LEFT JOIN housing_complexes hc ON hc.id = ac.housing_complex_id AND NOT hc.admin_deleted
                        LEFT JOIN housing_types ht ON ht.id = r.housing_type_id AND NOT ht.admin_deleted
                        JOIN supply_row_targets c ON c.supply_row_id = r.id
                        JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                        LEFT JOIN LATERAL (
                            SELECT quota.household_count
                            FROM supply_quota_members member
                            JOIN supply_quotas quota ON quota.id = member.supply_quota_id AND quota.kind = 'WINNER'
                            WHERE member.supply_row_target_id = c.id
                            ORDER BY (SELECT COUNT(*) FROM supply_quota_members other
                                      WHERE other.supply_quota_id = quota.id), quota.id
                            LIMIT 1
                        ) q ON true
                        WHERE r.announcement_id = ? AND NOT r.admin_excluded
                        ORDER BY r.display_order, r.id, c.display_order, c.id
                        """)
                .param(announcementId)
                .query((result, index) -> {
                    UnitRow read = new UnitRow(result.getLong("id"), string(result, "complex_label"),
                            string(result, "type_label"), decimal(result, "exclusive_area"), number(result, "complex_id"),
                            number(result, "type_id"), string(result, "rental_type"), string(result, "region_code"));
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
                           Long typeId, String rentalType, String regionCode, List<Target> targets) {
        private UnitRow(long id, String complexLabel, String typeLabel, BigDecimal exclusiveArea, Long complexId,
                        Long typeId, String rentalType, String regionCode) {
            this(id, complexLabel, typeLabel, exclusiveArea, complexId, typeId, rentalType, regionCode, new ArrayList<>());
        }

        private Unit unit() {
            return new Unit(id, complexLabel, typeLabel, exclusiveArea, complexId, typeId, rentalType,
                    regionCode == null ? List.of() : List.of(regionCode), List.copyOf(targets));
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
