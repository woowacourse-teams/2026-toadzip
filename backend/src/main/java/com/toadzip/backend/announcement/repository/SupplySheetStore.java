package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplySheetDraft;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Cell;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.HousingTypeRow;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Lease;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Quota;
import com.toadzip.backend.announcement.domain.SupplySheetDraft.Target;
import com.toadzip.backend.announcement.domain.SupplyTargetGroup;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 공고 하나의 공급표(주택형·공급대상·칸·수량 묶음·임대조건)를 {@link SupplySheetDraft} 그대로 바꾼다. 부르는 쪽의 트랜잭션에서 쓴다.
 *
 * <p>주택형(supply_rows)은 엔티티로 쓴다. 주택형 연결 검사와 공고 버전이 엔티티 규칙을 따라야 하기 때문이다. 공급대상·칸·묶음·
 * 임대조건은 JdbcClient로 정한 순서대로 쓴다. Hibernate는 flush에서 삽입을 삭제보다 먼저 보내 "칸 하나에 임대조건 하나" 유일 제약에
 * 걸리기 때문이다.
 *
 * <p>공급행(주택형)은 여기서만 만든다. 공고 단지를 고르지 않은 주택형은 (공고, 단지명)의 공고 단지를 찾거나 만들어 그 안에 든다.
 * 칸·묶음·임대조건과 draft에 없는 공급행의 삭제도 여기서만 한다. 공급행을 지우면 그 칸이 cascade로 지워져 묶음·임대조건 멤버가
 * 빠지므로, 공급행을 직접 지우는 쪽은 같은 트랜잭션에서 바로 이 메서드로 공고의 묶음·임대조건을 다시 써야 한다.
 *
 * <p>ID는 다시 써도 유지한다. 주택형은 draft의 공급행 ID나 (공고 단지, 신청형 이름)으로, 공급대상은 (표기, 신청 조건)으로,
 * 칸은 (주택형, 공급대상)으로 기존 행을 찾는다. 신청자격 단위(unit-{공급행 ID})와 공개 응답의 대상 ID가 이 ID를 가리킨다.
 * 수량 묶음과 임대조건은 공고 단위로 지우고 다시 넣는다.
 *
 * <p>JDBC로 쓰므로 같은 영속성 컨텍스트에 이미 읽어 둔 공급표 엔티티(공급대상·칸·묶음·임대조건)는 낡는다. 쓴 뒤 공급표를 다시 읽으려면 영속성 컨텍스트를 비우고 읽는다.
 *
 * <p>공급표를 쓰면 공고의 정본이 확정된다({@link Announcement#confirmSupply}). 그 뒤 공개·관리 조회는 기관 준비 데이터 대신 이 공급표를
 * 읽는다. 공급표를 쓰는 곳은 관리자 등록·JSON 등록·작업실·PDF 보정 적용·'준비 데이터로 정본 시작'이고, 정제는 쓰지 않는다.
 *
 * <p>{@code @Repository}의 예외 변환을 받지 않게 {@code @Component}로 둔다. 다른 공고의 공급행 거부는 IllegalArgumentException 그대로 전한다.
 */
@Component
@RequiredArgsConstructor
public class SupplySheetStore {

    private final EntityManager entities;
    private final SupplyRowRepository supplyRows;
    private final AnnouncementComplexRepository announcementComplexes;
    private final JdbcClient jdbc;

    public Saved replace(Announcement announcement, SupplySheetDraft draft) {
        entities.flush();
        long announcementId = announcement.getId();
        // 같은 공고를 함께 쓰면 자연 키로 찾지 못한 주택형이 두 번 생긴다. 공고 행을 잠가 차례로 쓴다.
        jdbc.sql("SELECT 1 FROM announcements WHERE id = ? FOR NO KEY UPDATE").param(announcementId).query().singleRow();
        announcement.confirmSupply();
        Rows rows = writeRows(announcement, draft.housingTypes());
        entities.flush();
        Map<String, Long> targetIds = upsertTargets(announcementId, draft.targets());
        clearQuotasAndLeases(announcementId);
        Map<String, Long> cellIds = writeCells(announcementId, draft.cells(), rows.ids(), targetIds);
        draft.quotas().forEach(quota -> insertQuota(announcementId, quota, cellIds));
        draft.leases().forEach(lease -> insertLease(announcementId, lease, cellIds));
        jdbc.sql("""
                DELETE FROM announcement_supply_targets st
                WHERE st.announcement_id = ?
                  AND NOT EXISTS (SELECT 1 FROM supply_row_targets c WHERE c.supply_target_id = st.id)
                """).param(announcementId).update();
        rows.stale().forEach(entities::remove);
        entities.flush();
        return new Saved(rows.ids(), targetIds, cellIds);
    }

    private Rows writeRows(Announcement announcement, List<HousingTypeRow> types) {
        Map<String, AnnouncementComplex> complexes = complexes(announcement, types);
        List<SupplyRow> unclaimed = new ArrayList<>(supplyRows.findAllByAnnouncement(announcement));
        unclaimed.sort(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId));
        Map<String, SupplyRow> matched = new HashMap<>();
        types.stream().filter(type -> type.supplyRowId() != null)
                .forEach(type -> matched.put(type.key(), claimById(unclaimed, type.supplyRowId())));
        types.stream().filter(type -> type.supplyRowId() == null)
                .forEach(type -> claimBySheetKey(unclaimed, complexes.get(type.key()), type)
                        .ifPresent(row -> matched.put(type.key(), row)));
        Map<String, Long> ids = new LinkedHashMap<>();
        types.forEach(type -> ids.put(type.key(),
                write(announcement, matched.get(type.key()), complexes.get(type.key()), type).getId()));
        return new Rows(ids, unclaimed);
    }

    /**
     * 주택형마다 들 공고 단지. 공고 단지를 고르지 않은 주택형은 (공고, 단지명)의 공고 단지를 찾거나 만든다. 정본 행은 공고 단지 없이
     * 두지 않는다.
     */
    private Map<String, AnnouncementComplex> complexes(Announcement announcement, List<HousingTypeRow> types) {
        List<AnnouncementComplex> own =
                new ArrayList<>(announcementComplexes.findAllByAnnouncementOrderByIdAsc(announcement));
        Map<String, AnnouncementComplex> result = new HashMap<>();
        for (HousingTypeRow type : types) {
            if (type.announcementComplexId() != null) {
                result.put(type.key(), find(AnnouncementComplex.class, type.announcementComplexId(), "공고 단지"));
                continue;
            }
            result.put(type.key(), own.stream()
                    .filter(complex -> complex.getSourceComplexName().strip().equals(type.complexName())).findFirst()
                    .orElseGet(() -> created(own, announcementComplexes.save(
                            AnnouncementComplex.observe(announcement, type.complexName(), null, null)))));
        }
        return result;
    }

    private static AnnouncementComplex created(List<AnnouncementComplex> own, AnnouncementComplex complex) {
        own.add(complex);
        return complex;
    }

    private static SupplyRow claimById(List<SupplyRow> unclaimed, long supplyRowId) {
        SupplyRow row = unclaimed.stream().filter(candidate -> candidate.getId() == supplyRowId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("이 공고의 공급행이 아니다: " + supplyRowId));
        unclaimed.remove(row);
        return row;
    }

    private static Optional<SupplyRow> claimBySheetKey(List<SupplyRow> unclaimed, AnnouncementComplex complex,
            HousingTypeRow type) {
        Optional<SupplyRow> row = unclaimed.stream()
                .filter(candidate -> candidate.sameSheetHousingType(complex, type.typeName())).findFirst();
        row.ifPresent(unclaimed::remove);
        return row;
    }

    private SupplyRow write(Announcement announcement, SupplyRow stored, AnnouncementComplex complex,
            HousingTypeRow type) {
        HousingComplex housing = find(HousingComplex.class, type.housingComplexId(), "단지");
        HousingType housingType = find(HousingType.class, type.housingTypeId(), "주택형");
        if (stored == null) {
            return supplyRows.save(SupplyRow.createFromSheet(announcement, type, complex, housing, housingType));
        }
        stored.applySheet(type, complex, housing, housingType);
        return stored;
    }

    private <T> T find(Class<T> entity, Long id, String name) {
        if (id == null) {
            return null;
        }
        T found = entities.find(entity, id);
        if (found == null) {
            throw new IllegalArgumentException(name + "을 찾을 수 없다: " + id);
        }
        return found;
    }

    /** (공고, 표기, 신청 조건)이 같은 공급대상은 지우지 않고 고쳐 ID를 지킨다. 분류는 상수 이름의 JSON 배열로 쓴다. */
    private Map<String, Long> upsertTargets(long announcementId, List<Target> targets) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Target target : targets) {
            ids.put(target.key(), jdbc.sql("""
                    INSERT INTO announcement_supply_targets
                        (announcement_id, label, application_condition, target_groups, display_order, admin_modified)
                    VALUES (:announcement, :label, :condition, CAST(:groups AS jsonb), :order, :adminModified)
                    ON CONFLICT (announcement_id, label, application_condition)
                        DO UPDATE SET target_groups = EXCLUDED.target_groups, display_order = EXCLUDED.display_order,
                            admin_modified = EXCLUDED.admin_modified
                    RETURNING id
                    """).param("announcement", announcementId).param("label", target.label())
                    .param("condition", target.applicationCondition()).param("groups", groupsJson(target.groups()))
                    .param("order", target.displayOrder()).param("adminModified", target.adminModified())
                    .query(Long.class).single());
        }
        return ids;
    }

    private static String groupsJson(List<SupplyTargetGroup> groups) {
        return groups.stream().map(group -> "\"" + group.name() + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private void clearQuotasAndLeases(long announcementId) {
        jdbc.sql("""
                DELETE FROM supply_quota_members m USING supply_quotas q
                WHERE m.supply_quota_id = q.id AND q.announcement_id = ?
                """).param(announcementId).update();
        jdbc.sql("DELETE FROM supply_quotas WHERE announcement_id = ?").param(announcementId).update();
        jdbc.sql("""
                DELETE FROM lease_term_members m USING lease_terms l
                WHERE m.lease_terms_id = l.id AND l.announcement_id = ?
                """).param(announcementId).update();
        jdbc.sql("DELETE FROM lease_terms WHERE announcement_id = ?").param(announcementId).update();
    }

    /** 이미 있는 (주택형, 공급대상) 칸은 ID를 그대로 두고, 새 칸은 새 ID를 받는다. 공급표에 없는 칸은 지운다. */
    private Map<String, Long> writeCells(long announcementId, List<Cell> cells, Map<String, Long> rowIds,
            Map<String, Long> targetIds) {
        Map<Place, Long> stored = new HashMap<>();
        jdbc.sql("""
                SELECT c.id, c.supply_row_id, c.supply_target_id
                FROM supply_row_targets c
                JOIN supply_rows r ON r.id = c.supply_row_id
                WHERE r.announcement_id = ?
                """).param(announcementId).query((result, index) -> Map.entry(
                        new Place(result.getLong("supply_row_id"), result.getLong("supply_target_id")),
                        result.getLong("id"))).list().forEach(cell -> stored.put(cell.getKey(), cell.getValue()));
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Cell cell : cells) {
            Place place = new Place(rowIds.get(cell.housingTypeKey()), targetIds.get(cell.targetKey()));
            ids.put(cell.key(), keepOrInsert(stored.remove(place), place, cell.displayOrder()));
        }
        stored.values().forEach(id -> jdbc.sql("DELETE FROM supply_row_targets WHERE id = ?").param(id).update());
        return ids;
    }

    private long keepOrInsert(Long storedId, Place place, int displayOrder) {
        if (storedId != null) {
            jdbc.sql("UPDATE supply_row_targets SET display_order = ? WHERE id = ?")
                    .params(displayOrder, storedId).update();
            return storedId;
        }
        return jdbc.sql("""
                INSERT INTO supply_row_targets (supply_row_id, supply_target_id, display_order)
                VALUES (?, ?, ?)
                RETURNING id
                """).params(place.rowId(), place.targetId(), displayOrder).query(Long.class).single();
    }

    private void insertQuota(long announcementId, Quota quota, Map<String, Long> cellIds) {
        long quotaId = jdbc.sql("""
                INSERT INTO supply_quotas (announcement_id, kind, household_count, admin_modified)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """).params(announcementId, quota.kind().name(), quota.count(), quota.adminModified())
                .query(Long.class).single();
        quota.cellKeys().forEach(key -> jdbc.sql(
                "INSERT INTO supply_quota_members (supply_quota_id, supply_row_target_id) VALUES (?, ?)")
                .params(quotaId, cellIds.get(key)).update());
    }

    private void insertLease(long announcementId, Lease lease, Map<String, Long> cellIds) {
        long leaseId = jdbc.sql("""
                INSERT INTO lease_terms (announcement_id, rental_deposit, monthly_rent, converted_deposit, admin_modified)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """).params(announcementId, lease.amounts().rentalDeposit(), lease.amounts().monthlyRent(),
                        lease.amounts().convertedDeposit(), lease.adminModified())
                .query(Long.class).single();
        lease.cellKeys().forEach(key -> jdbc.sql(
                "INSERT INTO lease_term_members (lease_terms_id, supply_row_target_id) VALUES (?, ?)")
                .params(leaseId, cellIds.get(key)).update());
    }

    /** draft 키별로 저장한 ID: 주택형 → 공급행 ID, 공급대상 → 공급대상 ID, 칸 → 칸 ID. */
    public record Saved(Map<String, Long> housingTypes, Map<String, Long> targets, Map<String, Long> cells) {

        public Saved {
            housingTypes = Map.copyOf(housingTypes);
            targets = Map.copyOf(targets);
            cells = Map.copyOf(cells);
        }
    }

    private record Rows(Map<String, Long> ids, List<SupplyRow> stale) {
    }

    private record Place(long rowId, long targetId) {
    }
}
