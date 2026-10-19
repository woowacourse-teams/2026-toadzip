package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.service.QualityComparisonService;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyCorrectionRecord;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Target;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyCorrectionRecordRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplySourceQueryRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SupplyCorrectionService {
    private final AnnouncementRepository announcements;
    private final SupplyRowRepository rows;
    private final SupplyTargetRepository targets;
    private final SupplySourceQueryRepository sourceStatus;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final AnnouncementApplicationScheduleRepository schedules;
    private final SupplyCorrectionRecordRepository corrections;
    private final AdminDataChangeRepository changes;
    private final QualityComparisonService comparisons;
    private final ObjectMapper json;

    public SupplyCorrection.Workspace workspace(long id) {
        var announcement = find(id);
        var comparison = comparisons.detail(QualityResource.ANNOUNCEMENT, id, "basic");
        var currentRows = rows.findAllByAnnouncement(announcement).stream()
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .map(this::rowData).sorted(Comparator.comparingInt(Row::displayOrder).thenComparing(Row::key)).toList();
        return new SupplyCorrection.Workspace(hash(List.of(comparison.snapshotToken(), currentRows)),
                announcement.getVersion(), currentRows, comparison.sources(), sourceStatus.missingWarnings(id));
    }

    public SupplyCorrection.Preview preview(long id, SupplyCorrection.Request request) {
        var before = checked(id, request.snapshotToken());
        var planned = plan(find(id), before.rows(), request.rows());
        var warnings = validateSchedulesAndCounts(id, planned);
        if (same(before.rows(), planned)) {
            throw new IllegalArgumentException("변경한 공급정보가 없습니다.");
        }
        return new SupplyCorrection.Preview(hash(List.of(request, planned, candidateValues(planned))),
                before.rows(), planned, warnings);
    }

    @Transactional
    public SupplyCorrection.Applied apply(long id, SupplyCorrection.Apply request, String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        String requestHash = hash(request);
        var previous = corrections.findByOperationId(request.correction().operationId());
        if (previous.isPresent()) {
            var applied = previous.get();
            if (applied.getAnnouncementId() != id || !applied.getRequestHash().equals(requestHash)) {
                throw new AdminDataConflictException("같은 작업 번호에 다른 변경 내용이 있습니다. 새 미리보기를 만드세요.");
            }
            return new SupplyCorrection.Applied(applied.getChangeId(), true, workspace(id));
        }
        var preview = preview(id, request.correction());
        if (!preview.confirmationToken().equals(request.confirmationToken())) {
            throw new AdminDataConflictException("미리보기 이후 선택한 단지·주택형 또는 변경안이 바뀌었습니다. 다시 확인하세요.");
        }
        var stored = rows.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(SupplyRow::getId, Function.identity()));
        var beforeById = preview.before().stream().collect(Collectors.toMap(Row::id, Function.identity()));
        for (var row : preview.after()) {
            if (row.id() == null || !same(beforeById.get(row.id()), row)) {
                writeRow(announcement, stored.get(row.id()), row, request.correction());
            }
        }
        announcement.recordAdminSupplyChange();
        announcements.flush();
        var after = workspace(id);
        var audit = new LinkedHashMap<String, Object>();
        audit.put("rows", after.rows());
        audit.put("reason", request.correction().reason().strip());
        audit.put("sourceUrl", request.correction().sourceUrl());
        audit.put("sourcePage", request.correction().sourcePage());
        audit.put("operationId", request.correction().operationId());
        audit.put("sources", after.sources());
        var change = changes.saveAndFlush(new AdminDataChange("ANNOUNCEMENT", id, "CORRECT_SUPPLY", actor,
                json.writeValueAsString(Map.of("rows", preview.before())), json.writeValueAsString(audit)));
        corrections.save(new SupplyCorrectionRecord(request.correction().operationId(), id, requestHash,
                change.getId(), announcement.getVersion()));
        return new SupplyCorrection.Applied(change.getId(), false, after);
    }

    private List<Row> plan(Announcement announcement, List<Row> before, List<Row> requested) {
        var known = before.stream().collect(Collectors.toMap(Row::id, Function.identity()));
        Set<Long> seen = new HashSet<>();
        Set<String> keys = new HashSet<>();
        List<Row> result = new ArrayList<>();
        for (var row : requested) {
            if (!keys.add(row.key())) { throw new IllegalArgumentException("공급행 작업 키가 중복되었습니다."); }
            Row old = null;
            if (row.id() != null) {
                old = known.get(row.id());
                if (old == null || !seen.add(row.id())) {
                    throw new IllegalArgumentException("공급행이 이 공고에 없거나 중복되었습니다.");
                }
                if (!old.key().equals(row.key())) { throw new IllegalArgumentException("기존 공급행 키를 유지하세요."); }
            }
            result.add(planRow(announcement, old, row));
        }
        if (!seen.equals(known.keySet())) {
            throw new IllegalArgumentException("기존 공급행을 생략할 수 없습니다. 필요 없는 행은 제외로 표시하세요.");
        }
        return result.stream().sorted(Comparator.comparingInt(Row::displayOrder).thenComparing(Row::key)).toList();
    }

    private Row planRow(Announcement announcement, Row old, Row row) {
        if (row.groupId() == null && row.sharedHouseholdCount() != null) {
            throw new IllegalArgumentException("공통 모집 묶음이 없는 행에 공통 모집수를 지정할 수 없습니다.");
        }
        var complex = complex(row.housingComplexId());
        var type = type(row, complex, "PREVIEW:" + row.key());
        if (old == null && row.groupId() != null || old != null && !Objects.equals(old.groupId(), row.groupId())) {
            throw new IllegalArgumentException("공통 모집 묶음은 원천별 공급행 보정에서 변경하세요.");
        }
        if (old != null && old.groupId() != null && (!Objects.equals(old.sharedHouseholdCount(), row.sharedHouseholdCount())
                || !Objects.equals(old.housingComplexId(), row.housingComplexId()) || row.householdCount() != null)) {
            throw new IllegalArgumentException("공통 모집수와 묶음 단지는 원천별 공급행 보정에서 함께 변경하세요.");
        }
        var transientRow = SupplyRow.create(announcement, complex, type, "PREVIEW:" + row.key(), row.displayOrder(),
                row.complexName(), row.typeName(), row.pnu(), row.moveInMonth(), row.category(), null, row.householdCount());
        var plannedTargets = planTargets(transientRow, old, row.targets());
        BigDecimal exclusiveArea = row.exclusiveArea();
        BigDecimal supplyArea = row.supplyArea();
        if (!row.areaOverride()) {
            exclusiveArea = transientRow.effectiveExclusiveArea();
            supplyArea = transientRow.effectiveSupplyArea();
        }
        boolean modified = old == null || old.protectedValues();
        var result = new Row(row.id(), row.key(), row.housingComplexId(), complexName(complex), row.housingTypeId(),
                row.newHousingType(), row.groupId(), row.sharedHouseholdCount(), modified, row.excluded(), row.displayOrder(),
                row.complexName(), row.typeName(), row.pnu(), row.moveInMonth(), row.category(), row.householdCount(),
                row.areaOverride(), decimal(exclusiveArea), decimal(supplyArea), plannedTargets, row.targetCountsComparable());
        if (old != null && !same(old, result) && !modified) {
            return new Row(result.id(), result.key(), result.housingComplexId(), result.housingComplexName(),
                    result.housingTypeId(), result.newHousingType(), result.groupId(), result.sharedHouseholdCount(), true,
                    result.excluded(), result.displayOrder(), result.complexName(), result.typeName(), result.pnu(),
                    result.moveInMonth(), result.category(), result.householdCount(), result.areaOverride(),
                    result.exclusiveArea(), result.supplyArea(), result.targets(), result.targetCountsComparable());
        }
        return result;
    }

    private List<Target> planTargets(SupplyRow row, Row old, List<Target> requested) {
        Map<Long, Target> known = Map.of();
        if (old != null) { known = old.targets().stream().collect(Collectors.toMap(Target::id, Function.identity())); }
        Set<Long> seen = new HashSet<>();
        Set<String> keys = new HashSet<>();
        List<Target> result = new ArrayList<>();
        for (var target : requested) {
            if (!keys.add(target.key())) { throw new IllegalArgumentException("공급대상 작업 키가 중복되었습니다."); }
            Target previous = null;
            if (target.id() != null) {
                previous = known.get(target.id());
                if (previous == null || !seen.add(target.id()) || !previous.key().equals(target.key())) {
                    throw new IllegalArgumentException("공급대상이 이 행에 없거나 중복되었습니다.");
                }
            }
            SupplyTarget.create(row, target.target(), target.supplyRank(), target.householdCount(), target.reserveCount(),
                    target.deposit(), target.rent(), target.convertedDeposit(), target.condition(), target.displayOrder());
            boolean modified = previous == null || previous.protectedValues() || !same(previous, target);
            result.add(new Target(target.id(), target.key(), target.excluded(), modified, target.displayOrder(),
                    target.target(), target.supplyRank(), target.householdCount(), target.reserveCount(),
                    decimal(target.deposit()), decimal(target.rent()), decimal(target.convertedDeposit()), target.condition()));
        }
        if (!seen.equals(known.keySet())) {
            throw new IllegalArgumentException("기존 공급대상을 생략할 수 없습니다. 필요 없는 조건은 제외로 표시하세요.");
        }
        return result.stream().sorted(Comparator.comparingInt(Target::displayOrder).thenComparing(Target::key)).toList();
    }

    private List<String> validateSchedulesAndCounts(long id, List<Row> planned) {
        var linked = planned.stream().filter(row -> !row.excluded()).map(Row::housingComplexId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (schedules.findAllByAnnouncementIdIn(List.of(id)).stream().anyMatch(schedule ->
                schedule.getHousingComplex() != null && !linked.contains(schedule.getHousingComplex().getId()))) {
            throw new AdminDataConflictException("제외·연결 변경할 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요.");
        }
        long totalCount = 0;
        Set<Long> countedGroups = new HashSet<>();
        for (var row : planned) {
            if (row.excluded()) { continue; }
            Integer count = row.householdCount();
            if (row.groupId() != null) {
                count = null;
                if (countedGroups.add(row.groupId())) { count = row.sharedHouseholdCount(); }
            }
            if (count != null) { totalCount += count; }
        }
        if (totalCount > Integer.MAX_VALUE) { throw new IllegalArgumentException("공고 모집수 합계가 허용 범위를 초과합니다."); }
        List<String> warnings = new ArrayList<>();
        for (var row : planned) {
            if (row.excluded()) { continue; }
            if (row.housingComplexId() == null || row.housingTypeId() == null && row.newHousingType() == null) {
                warnings.add(row.complexName() + " · " + row.typeName() + ": 단지 또는 주택형 연결이 미확정입니다.");
            }
            var active = row.targets().stream().filter(target -> !target.excluded()).toList();
            long sum = active.stream().map(Target::householdCount).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
            if (row.targetCountsComparable() && row.householdCount() != null && sum > row.householdCount()) {
                throw new IllegalArgumentException(row.typeName() + ": 대상별 모집수 합계가 행 모집수를 초과합니다.");
            }
            if (!row.targetCountsComparable() && active.size() > 1) {
                warnings.add(row.typeName() + ": 대상별 수량의 범위·중복 여부를 확인하지 않아 합계 검증을 하지 않았습니다.");
            }
            if (row.householdCount() == null || active.stream().anyMatch(target -> target.householdCount() == null)) {
                warnings.add(row.typeName() + ": 미확정 모집수는 0으로 간주하지 않습니다.");
            }
            if (active.isEmpty()) { warnings.add(row.typeName() + ": 표시할 공급대상·임대조건이 없습니다."); }
        }
        if (planned.stream().allMatch(Row::excluded)) { warnings.add("표시할 공급행이 없어 단지 검색에 공고가 나타나지 않습니다."); }
        return List.copyOf(warnings);
    }

    private void writeRow(Announcement announcement, SupplyRow stored, Row input, SupplyCorrection.Request request) {
        var complex = complex(input.housingComplexId());
        var type = type(input, complex, "MANUAL:" + request.operationId() + ":" + input.key());
        if (input.newHousingType() != null) { types.save(type); }
        if (stored == null) {
            stored = rows.save(SupplyRow.create(announcement, complex, type,
                    "MANUAL:" + request.operationId() + ":" + input.key(), input.displayOrder(), input.complexName(),
                    input.typeName(), input.pnu(), input.moveInMonth(), input.category(), null, input.householdCount()));
        }
        stored.reviseByAdmin(complex, type, input.complexName(), input.typeName(), input.pnu(), input.moveInMonth(),
                input.category(), input.householdCount());
        stored.correctPresentation(input.excluded(), input.displayOrder(), input.areaOverride(),
                input.exclusiveArea(), input.supplyArea());
        stored.confirmTargetCountScope(input.targetCountsComparable());
        var existing = targets.findAllBySupplyRow(stored).stream()
                .collect(Collectors.toMap(SupplyTarget::getId, Function.identity()));
        for (var target : input.targets()) {
            var condition = existing.get(target.id());
            if (condition != null && same(targetData(condition), target)) { continue; }
            if (condition == null) {
                condition = targets.save(SupplyTarget.create(stored, target.target(), target.supplyRank(),
                        target.householdCount(), target.reserveCount(), target.deposit(), target.rent(),
                        target.convertedDeposit(), target.condition(), target.displayOrder()));
            }
            condition.reviseByAdmin(target.target(), target.supplyRank(), target.householdCount(), target.reserveCount(),
                    target.deposit(), target.rent(), target.convertedDeposit(), target.condition());
            condition.correctPresentation(target.excluded(), target.displayOrder());
        }
    }

    private HousingComplex complex(Long id) {
        if (id == null) { return null; }
        return complexes.findById(id).filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new AdminDataConflictException("연결할 단지가 없거나 휴지통에 있습니다."));
    }

    private HousingType type(Row row, HousingComplex complex, String identifier) {
        if (row.newHousingType() != null) {
            if (complex == null || row.housingTypeId() != null) {
                throw new IllegalArgumentException("새 주택형을 만들 단지만 선택하세요. 기존 주택형과 함께 지정할 수 없습니다.");
            }
            var input = row.newHousingType();
            var created = HousingType.createFromMyHome(complex, identifier, input.name(), input.exclusiveArea(), input.supplyArea());
            created.reviseByAdmin(input.name(), input.exclusiveArea(), null);
            return created;
        }
        if (row.housingTypeId() == null) { return null; }
        var type = types.findById(row.housingTypeId()).orElseThrow(
                () -> new AdminDataConflictException("연결할 주택형을 찾을 수 없습니다."));
        if (complex == null || !type.getHousingComplex().getId().equals(complex.getId())) {
            throw new IllegalArgumentException("선택한 단지에 속한 주택형을 선택하세요.");
        }
        return type;
    }

    private List<Object> candidateValues(List<Row> planned) {
        List<Object> result = new ArrayList<>();
        for (var row : planned) {
            var complex = complex(row.housingComplexId());
            if (complex != null) { result.add(List.of(complex.getId(), complex.getVersion(), complex.getName())); }
            if (row.housingTypeId() == null) { continue; }
            var type = types.findById(row.housingTypeId()).orElseThrow();
            var value = new LinkedHashMap<String, Object>();
            value.put("id", type.getId()); value.put("name", type.getName());
            value.put("exclusiveArea", type.getExclusiveArea()); value.put("supplyArea", type.getSupplyArea());
            value.put("householdCount", type.getTotalHouseholdCount());
            result.add(value);
        }
        return result;
    }

    private Row rowData(SupplyRow row) {
        Long complexId = null; Long typeId = null; Long groupId = null; Integer shared = null;
        if (row.getHousingComplex() != null) { complexId = row.getHousingComplex().getId(); }
        if (row.getHousingType() != null) { typeId = row.getHousingType().getId(); }
        if (row.getSupplyGroup() != null) { groupId = row.getSupplyGroup().getId(); shared = row.getSupplyGroup().getSharedHouseholdCount(); }
        return new Row(row.getId(), "row:" + row.getId(), complexId, complexName(row.getHousingComplex()), typeId, null,
                groupId, shared, row.isAdminModified(), row.isAdminExcluded(), row.getDisplayOrder(),
                row.getSourceComplexName(), row.getSourceHousingTypeName(), row.getSupplyPnu(), row.getExpectedMoveInMonth(),
                row.getSupplyCategory(), row.getTotalSupplyHouseholdCount(), row.isAreaOverride(),
                decimal(row.effectiveExclusiveArea()), decimal(row.effectiveSupplyArea()),
                targets.findAllBySupplyRow(row).stream().sorted(Comparator.comparingInt(SupplyTarget::getDisplayOrder)
                        .thenComparing(SupplyTarget::getId)).map(this::targetData)
                        .sorted(Comparator.comparingInt(Target::displayOrder).thenComparing(Target::key)).toList(), row.isTargetCountsComparable());
    }

    private Target targetData(SupplyTarget target) {
        return new Target(target.getId(), "target:" + target.getId(), target.isAdminExcluded(), target.isAdminModified(),
                target.getDisplayOrder(), target.getTarget(), target.getSupplyRank(), target.getSupplyHouseholdCount(),
                target.getReserveCount(), decimal(target.getRentalDeposit()), decimal(target.getMonthlyRent()),
                decimal(target.getConvertedDeposit()), target.getApplicationCondition());
    }

    private BigDecimal decimal(BigDecimal value) {
        if (value == null) { return null; }
        var normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) { return normalized.setScale(0); }
        return normalized;
    }

    private String complexName(HousingComplex complex) {
        if (complex == null) { return null; }
        return complex.getName();
    }

    private Announcement find(long id) {
        var result = announcements.findById(id).orElseThrow(AnnouncementNotFoundException::new);
        if (result.isAdminDeleted()) { throw new AdminDataConflictException("공고를 복구한 뒤 보정하세요."); }
        return result;
    }

    private SupplyCorrection.Workspace checked(long id, String token) {
        var current = workspace(id);
        if (!current.snapshotToken().equals(token)) {
            throw new AdminDataConflictException("등록값이나 원천이 바뀌었습니다. 입력을 보관하고 최신 값과 다시 비교하세요.");
        }
        return current;
    }

    private boolean same(Object first, Object second) { return json.valueToTree(first).equals(json.valueToTree(second)); }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
