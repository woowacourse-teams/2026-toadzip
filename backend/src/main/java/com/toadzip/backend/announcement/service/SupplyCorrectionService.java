package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.exception.AdminDataStaleException;
import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.service.QualityComparisonService;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyCorrectionRecord;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection;
import com.toadzip.backend.admin.quality.dto.QualityComparison;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
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
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyMatchingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
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
    private final SupplyCorrectionGroups groupCorrections;
    private final AnnouncementSupplyMatchingService supplyMatching;
    private final ObjectMapper json;

    public SupplyCorrection.Workspace workspace(long id) {
        return snapshot(id).workspace();
    }

    /** 화면에 주는 토큰과 그 토큰을 만든 값. 충돌 로그에서 어느 쪽이 바뀌었는지 가려낼 때 쓴다. */
    private record Snapshot(SupplyCorrection.Workspace workspace, QualityComparison comparison, List<Row> rows) { }

    private Snapshot snapshot(long id) {
        var announcement = find(id);
        var comparison = comparisons.detail(QualityResource.ANNOUNCEMENT, id, "basic");
        var stored = rows.findAllByAnnouncement(announcement);
        var currentRows = stored.stream()
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .map(this::rowData).sorted(Comparator.comparingInt(Row::displayOrder).thenComparing(Row::key)).toList();
        var rowSources = stored.stream()
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .flatMap(row -> rowSources(row, comparison.sources()).stream()).toList();
        var deletable = stored.stream().filter(SupplyCorrectionService::deletable).map(row -> "row:" + row.getId()).sorted().toList();
        var workspace = new SupplyCorrection.Workspace(hash(List.of(comparison.snapshotToken(), currentRows)),
                announcement.getVersion(), currentRows, comparison.sources(), sourceStatus.missingWarnings(id), rowSources,
                deletable);
        var snapshot = new Snapshot(workspace, comparison, currentRows);
        if (log.isDebugEnabled()) {
            var parts = tokenParts(snapshot);
            log.debug("event=announcement.supply_correction.snapshot_loaded announcementId={} version={} snapshotToken={} "
                    + "comparisonToken={} detailHash={} sourcesHash={} rowsHash={}", id, workspace.version(),
                    shortToken(workspace.snapshotToken()), parts.get(0), parts.get(1), parts.get(2), parts.get(3));
        }
        return snapshot;
    }

    /**
     * 공급행이 기록한 원천행. 마이홈은 원천 키를 그대로 기록한다. LH는 "LH:{panId}:SUPPLY:{순번}"만 기록하므로
     * 원천 키 "{panId}:{요청 해시}:{순번}" 중 공고 번호와 순번이 같은 것이 하나일 때만 대응한다.
     * 맞는 원천이 없으면 기록값을 그대로 돌려 찾을 수 없음을 알리고, 여러 개면 추정하지 않는다.
     */
    private List<SupplyCorrection.RowSource> rowSources(SupplyRow row, List<QualityComparison.Source> sources) {
        var result = new ArrayList<SupplyCorrection.RowSource>();
        String key = "row:" + row.getId();
        if (row.getMyhomeSourceRowIdentifier() != null) {
            result.add(new SupplyCorrection.RowSource(key, IngestSourceCategory.MYHOME_ANNOUNCEMENT.name(),
                    row.getMyhomeSourceRowIdentifier()));
        }
        String recorded = row.getLhSourceSupplyRowIdentifier();
        String[] parts = recorded == null ? new String[0] : recorded.split(":");
        if (parts.length == 4 && parts[0].equals("LH") && parts[2].equals("SUPPLY")) {
            var candidates = sources.stream()
                    .filter(source -> source.category() == IngestSourceCategory.LH_ANNOUNCEMENT_SUPPLY)
                    .map(QualityComparison.Source::sourceKey)
                    .filter(sourceKey -> sourceKey.startsWith(parts[1] + ":") && sourceKey.endsWith(":" + parts[3]))
                    .distinct().toList();
            if (candidates.size() <= 1) {
                result.add(new SupplyCorrection.RowSource(key, IngestSourceCategory.LH_ANNOUNCEMENT_SUPPLY.name(),
                        candidates.isEmpty() ? recorded : candidates.getFirst()));
            }
        }
        return result;
    }

    public SupplyCorrection.Preview preview(long id, SupplyCorrection.Request request) {
        return preview(id, checked(id, request.snapshotToken()), request);
    }

    /** 같은 트랜잭션에서 이미 읽은 공급정보로 미리보기를 만든다. 원천 비교 스냅샷을 다시 계산하지 않는다. */
    public SupplyCorrection.Preview preview(long id, SupplyCorrection.Workspace before,
            SupplyCorrection.Request request) {
        if (!before.snapshotToken().equals(request.snapshotToken())) {
            log.warn("event=announcement.supply_correction.conflict_detected announcementId={} check=snapshot "
                    + "requestToken={} currentToken={}", id, shortToken(request.snapshotToken()),
                    shortToken(before.snapshotToken()));
            throw new AdminDataStaleException("등록값이나 원천이 바뀌었습니다. 입력을 보관하고 최신 값과 다시 비교하세요.");
        }
        var announcement = find(id);
        var planned = plan(announcement, before.rows(), request.rows(), Set.copyOf(before.deletableRowKeys()),
                request.deletedRowKeys());
        var groupPlans = groupCorrections.plan(announcement, planned, request.newGroups());
        var warnings = validateSchedulesAndCounts(id, planned, request.newGroups());
        if (groupPlans.isEmpty() && same(before.rows(), planned)) {
            throw new IllegalArgumentException("변경한 공급정보가 없습니다.");
        }
        return new SupplyCorrection.Preview(hash(List.of(request, planned, candidateValues(planned), groupPlans)),
                before.rows(), planned, warnings, request.newGroups());
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
        var written = write(announcement, id, request, actor);
        var change = changes.saveAndFlush(new AdminDataChange("ANNOUNCEMENT", id, "CORRECT_SUPPLY", actor,
                json.writeValueAsString(Map.of("rows", written.before())),
                json.writeValueAsString(audit(written.after(), request.correction()))));
        recordOperation(id, request, change.getId(), announcement.getVersion());
        return new SupplyCorrection.Applied(change.getId(), false, written.after());
    }

    /**
     * 다른 보정 이력 한 건에 공급행 변경을 포함할 때 공급행만 저장한다. 호출자가 같은 잠금 트랜잭션에서 만든 미리보기를 쓴다.
     * 호출자는 같은 트랜잭션에서 이력을 남긴 뒤 {@link #recordOperation}으로 작업 번호를 그 이력에 연결한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeWithoutHistory(long id, SupplyCorrection.Apply request, SupplyCorrection.Preview preview,
            String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        if (corrections.findByOperationId(request.correction().operationId()).isPresent()) {
            throw new AdminDataConflictException("이미 적용한 공급정보 보정 작업입니다. 최신값을 다시 확인하세요.");
        }
        confirm(id, preview, request);
        writePlanned(announcement, preview, request, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOperation(long id, SupplyCorrection.Apply request, long changeId, long announcementVersion) {
        corrections.save(new SupplyCorrectionRecord(request.correction().operationId(), id, hash(request),
                changeId, announcementVersion));
    }

    /** 공급행 보정 이력의 변경 후 값. 다른 보정 이력에 포함할 때도 같은 필드를 남긴다. */
    public Map<String, Object> audit(SupplyCorrection.Workspace after, SupplyCorrection.Request correction) {
        var audit = new LinkedHashMap<String, Object>();
        audit.put("rows", after.rows());
        audit.put("reason", correction.reason().strip());
        audit.put("sourceUrl", correction.sourceUrl());
        audit.put("sourcePage", correction.sourcePage());
        audit.put("operationId", correction.operationId());
        if (!correction.deletedRowKeys().isEmpty()) { audit.put("deletedRowKeys", correction.deletedRowKeys()); }
        audit.put("sources", after.sources());
        return audit;
    }

    public record Written(List<Row> before, SupplyCorrection.Workspace after) { }

    private Written write(Announcement announcement, long id, SupplyCorrection.Apply request, String actor) {
        var preview = preview(id, request.correction());
        confirm(id, preview, request);
        writePlanned(announcement, preview, request, actor);
        return new Written(preview.before(), workspace(id));
    }

    private void confirm(long id, SupplyCorrection.Preview preview, SupplyCorrection.Apply request) {
        if (!preview.confirmationToken().equals(request.confirmationToken())) {
            log.warn("event=announcement.supply_correction.conflict_detected announcementId={} check=confirmation "
                    + "requestToken={} currentToken={}", id, shortToken(request.confirmationToken()),
                    shortToken(preview.confirmationToken()));
            throw new AdminDataStaleException("미리보기 이후 선택한 단지·주택형 또는 변경안이 바뀌었습니다. 다시 확인하세요.");
        }
    }

    private void writePlanned(Announcement announcement, SupplyCorrection.Preview preview,
            SupplyCorrection.Apply request, String actor) {
        var stored = rows.findAllByAnnouncement(announcement).stream()
                .collect(Collectors.toMap(SupplyRow::getId, Function.identity()));
        var groupPlans = groupCorrections.plan(announcement, preview.after(), request.correction().newGroups());
        Map<String, SupplyRow> written = new LinkedHashMap<>();
        var beforeById = preview.before().stream().collect(Collectors.toMap(Row::id, Function.identity()));
        for (var row : preview.after()) {
            var saved = stored.get(row.id());
            if (row.id() == null || !same(beforeById.get(row.id()), row)) {
                saved = writeRow(announcement, saved, row, request.correction());
            }
            written.put(row.key(), saved);
        }
        groupCorrections.apply(announcement, groupPlans, written, request.correction(), actor);
        for (var key : request.correction().deletedRowKeys()) {
            var row = preview.before().stream().filter(item -> item.key().equals(key)).findFirst().map(item -> stored.get(item.id()))
                    .orElseThrow();
            targets.deleteAll(targets.findAllBySupplyRow(row));
            rows.delete(row);
        }
        supplyMatching.rememberCorrectedLinks(announcement.getSourceAnnouncementIdentifier(),
                changedLinks(preview, beforeById, written), request.correction().sourceUrl(),
                request.correction().reason(), actor);
        announcement.recordAdminSupplyChange();
        announcements.flush();
    }

    /** 기존 행 중 주택형 연결이 바뀐 행. 연결은 정제의 저장 매칭이 소유하므로 원천행 식별자로 넘긴다. */
    private Map<String, HousingType> changedLinks(SupplyCorrection.Preview preview, Map<Long, Row> beforeById,
            Map<String, SupplyRow> written) {
        Map<String, HousingType> links = new HashMap<>();
        for (var row : preview.after()) {
            if (row.id() == null || row.groupId() != null) { continue; }
            var saved = written.get(row.key());
            Long typeId = saved.getHousingType() == null ? null : saved.getHousingType().getId();
            if (!Objects.equals(beforeById.get(row.id()).housingTypeId(), typeId)) {
                links.put(saved.getSourceSupplyRowIdentifier(), saved.getHousingType());
            }
        }
        return links;
    }

    private List<Row> plan(Announcement announcement, List<Row> before, List<Row> requested, Set<String> deletable,
            List<String> deleted) {
        var known = before.stream().collect(Collectors.toMap(Row::id, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (var key : deleted) {
            var row = before.stream().filter(item -> item.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("지울 공급행이 이 공고에 없습니다."));
            if (!deletable.contains(key)) {
                throw new IllegalArgumentException("원천에서 온 공급행과 공통 모집 묶음의 행은 지울 수 없습니다. 제외로 표시하세요.");
            }
            if (!seen.add(row.id())) { throw new IllegalArgumentException("같은 공급행을 두 번 지울 수 없습니다."); }
        }
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
        // 기존 행의 원천 단지명은 정제가 소유하므로 요청 값과 관계없이 저장값을 유지한다.
        String complexName = old == null ? row.complexName() : old.complexName();
        var transientRow = SupplyRow.create(announcement, complex, type, "PREVIEW:" + row.key(), row.displayOrder(),
                complexName, row.typeName(), row.pnu(), row.moveInMonth(), row.category(), null, row.householdCount());
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
                complexName, row.typeName(), row.pnu(), row.moveInMonth(), row.category(), row.householdCount(),
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

    private List<String> validateSchedulesAndCounts(long id, List<Row> planned, List<SupplyCorrection.NewGroup> newGroups) {
        var linked = planned.stream().filter(row -> !row.excluded()).map(Row::housingComplexId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (schedules.findAllByAnnouncementIdIn(List.of(id)).stream().anyMatch(schedule ->
                schedule.getHousingComplex() != null && !linked.contains(schedule.getHousingComplex().getId()))) {
            throw new AdminDataConflictException("제외·연결 변경할 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요.");
        }
        long totalCount = newGroups.stream().mapToLong(SupplyCorrection.NewGroup::sharedHouseholdCount).sum();
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

    private SupplyRow writeRow(Announcement announcement, SupplyRow stored, Row input, SupplyCorrection.Request request) {
        var complex = complex(input.housingComplexId());
        var type = type(input, complex, "MANUAL:" + request.operationId() + ":" + input.key());
        if (input.newHousingType() != null) { types.save(type); }
        if (stored == null) {
            // 원천이 없는 직접 추가 행은 처음 입력한 표기를 원천 이름으로 남긴다.
            stored = rows.save(SupplyRow.create(announcement, complex, type,
                    "MANUAL:" + request.operationId() + ":" + input.key(), input.displayOrder(), input.complexName(),
                    input.typeName(), input.pnu(), input.moveInMonth(), input.category(), null, input.householdCount()));
        }
        stored.reviseByAdmin(complex, type, input.typeName(), input.pnu(), input.moveInMonth(),
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
        return stored;
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

    /** 원천 없이 관리자가 추가한 행(MANUAL)만 지울 수 있다. 원천행은 다음 정제가 다시 만들므로 제외로만 숨긴다. */
    private static boolean deletable(SupplyRow row) {
        return row.getSourceSupplyRowIdentifier().startsWith("MANUAL:") && row.getSupplyGroup() == null;
    }

    private Row rowData(SupplyRow row) {
        Long complexId = null; Long typeId = null; Long groupId = null; Integer shared = null;
        if (row.getHousingComplex() != null) { complexId = row.getHousingComplex().getId(); }
        if (row.getHousingType() != null) { typeId = row.getHousingType().getId(); }
        if (row.getSupplyGroup() != null) { groupId = row.getSupplyGroup().getId(); shared = row.getSupplyGroup().getSharedHouseholdCount(); }
        return new Row(row.getId(), "row:" + row.getId(), complexId, complexName(row.getHousingComplex()), typeId, null,
                groupId, shared, row.isAdminModified(), row.isAdminExcluded(), row.getDisplayOrder(),
                row.getSourceComplexName(), row.displayHousingTypeName(), row.getSupplyPnu(), row.getExpectedMoveInMonth(),
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
        var snapshot = snapshot(id);
        var current = snapshot.workspace();
        if (!current.snapshotToken().equals(token)) {
            var parts = tokenParts(snapshot);
            log.warn("event=announcement.supply_correction.conflict_detected announcementId={} check=snapshot version={} "
                    + "requestToken={} currentToken={} comparisonToken={} detailHash={} sourcesHash={} rowsHash={}",
                    id, current.version(), shortToken(token), shortToken(current.snapshotToken()),
                    parts.get(0), parts.get(1), parts.get(2), parts.get(3));
            throw new AdminDataStaleException("등록값이나 원천이 바뀌었습니다. 입력을 보관하고 최신 값과 다시 비교하세요.");
        }
        return current;
    }

    private boolean same(Object first, Object second) { return json.valueToTree(first).equals(json.valueToTree(second)); }

    /** 토큰을 이루는 공고 상세·비교 원천·공급행의 해시. 원문은 남기지 않는다. */
    private List<String> tokenParts(Snapshot snapshot) {
        return List.of(shortToken(snapshot.comparison().snapshotToken()), shortToken(hash(snapshot.comparison().detail())),
                shortToken(hash(snapshot.comparison().sources())), shortToken(hash(snapshot.rows())));
    }

    private static String shortToken(String token) {
        return token == null ? null : token.substring(0, Math.min(12, token.length()));
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
