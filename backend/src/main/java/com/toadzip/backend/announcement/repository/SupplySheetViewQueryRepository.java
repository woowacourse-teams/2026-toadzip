package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import com.toadzip.backend.announcement.dto.correction.SupplySheetView;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 공고 공급표를 관리자 보기({@link SupplySheetView})로 읽는다. 관리자가 제외한 옛 공급행과 그 칸은 보이지 않고, 수량 묶음·임대조건의
 * 멤버도 보이는 칸만 남긴다(보이는 멤버가 없으면 뺀다). 주택형의 단지명·단지는 그 공고 단지의 이름과 연결이다(공개 화면과 같다).
 *
 * <p>JDBC로 바로 읽는다. 공급표 쓰기(SupplySheetStore)가 JDBC로 쓰므로, 같은 트랜잭션의 영속성 컨텍스트에 읽어 둔 공급표 엔티티는 쓴 뒤에
 * 낡는다. 부르는 쪽은 읽기 전에 엔티티 변경을 flush한다.
 */
@Repository
@RequiredArgsConstructor
public class SupplySheetViewQueryRepository {
    private final JdbcClient jdbc;

    public SupplySheetView read(long announcementId) {
        var housingTypes = jdbc.sql("""
                SELECT r.id, r.announcement_complex_id, ac.source_complex_name, ac.housing_complex_id,
                  c.name AS housing_complex_name, r.housing_type_id, r.housing_type_name AS name, r.exclusive_area,
                  r.supply_area, r.expected_move_in_month, r.supply_pnu, r.supply_category, r.display_order,
                  r.admin_modified
                FROM supply_rows r
                JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
                LEFT JOIN housing_complexes c ON c.id = ac.housing_complex_id
                WHERE r.announcement_id = :id AND NOT r.admin_excluded
                ORDER BY r.display_order, r.id
                """).param("id", announcementId).query((row, index) -> housingType(row)).list();
        var cells = jdbc.sql("""
                SELECT c.id, c.supply_row_id, c.supply_target_id, c.display_order, t.display_order AS target_order
                FROM supply_row_targets c
                JOIN supply_rows r ON r.id = c.supply_row_id
                JOIN announcement_supply_targets t ON t.id = c.supply_target_id
                WHERE r.announcement_id = :id AND NOT r.admin_excluded
                ORDER BY r.display_order, r.id, c.display_order, t.display_order, c.id
                """).param("id", announcementId).query((row, index) -> new SupplySheetView.Cell(
                        "cell:" + row.getLong("id"), row.getLong("id"), "row:" + row.getLong("supply_row_id"),
                        "target:" + row.getLong("supply_target_id"), row.getInt("display_order"))).list();
        var usedTargets = cells.stream().map(SupplySheetView.Cell::target).collect(Collectors.toSet());
        var targets = jdbc.sql("""
                SELECT id, label, application_condition, display_order,
                       ARRAY(SELECT jsonb_array_elements_text(target_groups)) AS target_groups
                FROM announcement_supply_targets
                WHERE announcement_id = :id ORDER BY display_order, id
                """).param("id", announcementId).query((row, index) -> new SupplySheetView.Target(
                        "target:" + row.getLong("id"), row.getLong("id"), row.getString("label"),
                        row.getString("application_condition"), groups(row.getArray("target_groups")),
                        row.getInt("display_order"))).list()
                .stream().filter(target -> usedTargets.contains(target.key())).toList();
        Map<String, Integer> cellOrder = new HashMap<>();
        cells.forEach(cell -> cellOrder.put(cell.key(), cellOrder.size()));
        return new SupplySheetView(housingTypes, targets, cells, quotas(announcementId, cellOrder),
                leases(announcementId, cellOrder));
    }

    /** 공급대상 ID별 관리자 수정 표시. 보기에는 싣지 않고 보정이 보호 규칙을 정할 때만 쓴다. */
    public Map<Long, Boolean> targetAdminModified(long announcementId) {
        Map<Long, Boolean> result = new HashMap<>();
        jdbc.sql("SELECT id, admin_modified FROM announcement_supply_targets WHERE announcement_id = :id")
                .param("id", announcementId).query((row, index) -> Map.entry(row.getLong("id"), row.getBoolean("admin_modified")))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private List<SupplySheetView.Quota> quotas(long announcementId, Map<String, Integer> cellOrder) {
        Map<Long, List<String>> members = members("""
                SELECT m.supply_quota_id AS owner, m.supply_row_target_id AS cell
                FROM supply_quota_members m JOIN supply_quotas q ON q.id = m.supply_quota_id
                WHERE q.announcement_id = :id
                """, announcementId, cellOrder);
        return jdbc.sql("SELECT id, kind, household_count, admin_modified FROM supply_quotas WHERE announcement_id = :id ORDER BY id")
                .param("id", announcementId).query((row, index) -> new SupplySheetView.Quota("quota:" + row.getLong("id"),
                        row.getLong("id"), SupplyQuota.Kind.valueOf(row.getString("kind")), row.getInt("household_count"),
                        members.getOrDefault(row.getLong("id"), List.of()), row.getBoolean("admin_modified")))
                .list().stream().filter(quota -> !quota.members().isEmpty()).toList();
    }

    private List<SupplySheetView.Lease> leases(long announcementId, Map<String, Integer> cellOrder) {
        Map<Long, List<String>> members = members("""
                SELECT m.lease_terms_id AS owner, m.supply_row_target_id AS cell
                FROM lease_term_members m JOIN lease_terms l ON l.id = m.lease_terms_id
                WHERE l.announcement_id = :id
                """, announcementId, cellOrder);
        return jdbc.sql("""
                SELECT id, rental_deposit, monthly_rent, converted_deposit, admin_modified
                FROM lease_terms WHERE announcement_id = :id ORDER BY id
                """).param("id", announcementId).query((row, index) -> new SupplySheetView.Lease(
                        "lease:" + row.getLong("id"), row.getLong("id"), money(row.getBigDecimal("rental_deposit")),
                        money(row.getBigDecimal("monthly_rent")), money(row.getBigDecimal("converted_deposit")),
                        members.getOrDefault(row.getLong("id"), List.of()), row.getBoolean("admin_modified")))
                .list().stream().filter(lease -> !lease.members().isEmpty()).toList();
    }

    /** 묶음 ID별로 보이는 멤버 칸 key를 칸 순서대로. */
    private Map<Long, List<String>> members(String sql, long announcementId, Map<String, Integer> cellOrder) {
        Map<Long, List<String>> members = new HashMap<>();
        jdbc.sql(sql).param("id", announcementId).query((row, index) -> Map.entry(row.getLong("owner"),
                "cell:" + row.getLong("cell"))).list().stream().filter(entry -> cellOrder.containsKey(entry.getValue()))
                .forEach(entry -> members.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue()));
        members.values().forEach(keys -> keys.sort(Comparator.comparing(cellOrder::get)));
        return members;
    }

    private static SupplySheetView.HousingType housingType(ResultSet row) throws SQLException {
        String month = row.getString("expected_move_in_month");
        String category = row.getString("supply_category");
        return new SupplySheetView.HousingType("row:" + row.getLong("id"), row.getLong("id"),
                nullableLong(row, "announcement_complex_id"), row.getString("source_complex_name"),
                nullableLong(row, "housing_complex_id"), row.getString("housing_complex_name"),
                nullableLong(row, "housing_type_id"), null, row.getString("name"), area(row.getBigDecimal("exclusive_area")),
                area(row.getBigDecimal("supply_area")), month == null ? null : YearMonth.parse(month),
                row.getString("supply_pnu"), category == null ? null : SupplyCategory.valueOf(category),
                row.getInt("display_order"), row.getBoolean("admin_modified"));
    }

    /** 저장된 분류 이름. DB 제약이 상수 이름만 받는다. */
    private static List<SupplyTargetGroup> groups(Array names) throws SQLException {
        return Arrays.stream((String[]) names.getArray()).map(SupplyTargetGroup::valueOf).toList();
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    /** 면적은 끝 0을 뗀 값으로 보인다(84.9600 → 84.96). 요청 값과 비교할 때도 같은 모양으로 맞춘다. */
    public static BigDecimal area(BigDecimal value) {
        if (value == null) { return null; }
        var normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    /** 금액은 원 단위라 소수 자리를 떼어 보인다. */
    public static BigDecimal money(BigDecimal value) {
        return area(value);
    }
}
