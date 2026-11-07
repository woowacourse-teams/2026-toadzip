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
import com.toadzip.backend.ingest.lh.refinement.domain.LhSourceIdentifier;
import com.toadzip.backend.ingest.source.dto.IngestSourceCategory;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.LinkedHousingType;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Row;
import com.toadzip.backend.announcement.dto.correction.SupplyCorrection.Target;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyCorrectionRecordRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplySourceQueryRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.mapping.service.CorrectedSupplyLinkService;
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
    private final SupplyCorrectionLhRows lhRows;
    private final CorrectedSupplyLinkService correctedLinks;
    private final ObjectMapper json;
    private final AnnouncementComplexAdminLinks adminLinks;
    private final AnnouncementComplexRepository announcementComplexRepository;

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
        var lhSupplyRows = lhRows.of(announcement, stored);
        var lhRowsByRowKey = lhSupplyRows.stream().filter(row -> row.attachedRowKey() != null)
                .collect(Collectors.groupingBy(SupplyCorrection.LhSupplyRow::attachedRowKey, LinkedHashMap::new,
                        Collectors.mapping(SupplyCorrection.LhSupplyRow::rowIdentifier, Collectors.toList())));
        var rowSources = stored.stream()
                .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder).thenComparing(SupplyRow::getId))
                .flatMap(row -> rowSources(row, lhRowsByRowKey.getOrDefault(LhRowAttachments.key(row), List.of()),
                        comparison.sources()).stream()).toList();
        var deletable = stored.stream().filter(SupplyCorrectionService::deletable).map(row -> LhRowAttachments.key(row)).sorted().toList();
        // 공고 단지 연결은 공급행이 없는 공고 단지도 있어 토큰에 함께 넣는다. 화면이 본 뒤 연결이 바뀌면 연결 변경을 거절한다.
        var announcementComplexes = announcementComplexes(announcement, stored);
        var workspace = new SupplyCorrection.Workspace(hash(List.of(comparison.snapshotToken(), currentRows, announcementComplexes)),
                announcement.getVersion(), currentRows, comparison.sources(), sourceStatus.missingWarnings(id),
                rowSources, deletable, unattachedLhSupplyRows(announcement, lhSupplyRows), announcementComplexes, lhSupplyRows);
        var snapshot = new Snapshot(workspace, comparison, currentRows);
        if (log.isDebugEnabled()) {
            var parts = tokenParts(snapshot);
            log.debug("event=announcement.supply_correction.snapshot_loaded announcementId={} version={} snapshotToken={} "
                    + "comparisonToken={} detailHash={} sourcesHash={} rowsHash={}", id, workspace.version(),
                    shortToken(workspace.snapshotToken()), parts.get(0), parts.get(1), parts.get(2), parts.get(3));
        }
        return snapshot;
    }

    private List<SupplyCorrection.AnnouncementComplexView> announcementComplexes(Announcement announcement, List<SupplyRow> stored) {
        return announcementComplexRepository.findAllByAnnouncementOrderByIdAsc(announcement).stream().map(complex -> {
            var linked = complex.linkedComplex();
            int rowCount = (int) stored.stream().filter(row -> !row.isAdminExcluded() && row.getAnnouncementComplex() != null
                    && row.getAnnouncementComplex().getId().equals(complex.getId())).count();
            return new SupplyCorrection.AnnouncementComplexView(complex.getId(), complex.getSourceComplexName(), complex.getRoadAddress(),
                    linked == null ? null : linked.getId(), complexName(linked), linked == null ? null : complex.getLinkMethod().name(), rowCount);
        }).toList();
    }

    /**
     * 공고 단지 하나의 연결 단지를 관리자가 바꾼다(ADMIN). 그 공고 단지의 공급행도 새 단지로 옮긴다(AnnouncementComplexAdminLinks.relink).
     * 공급정보 소유는 바꾸지 않는다. LH 원천 소유 공고는 정제가 이 연결로 공급행을 다시 맞춘다. 화면이 본 뒤 공급정보나 연결이 바뀌었으면 거절한다.
     */
    @Transactional
    public SupplyCorrection.Workspace linkAnnouncementComplex(long id, long announcementComplexId, SupplyCorrection.ComplexLinkRequest request,
            String actor) {
        var announcement = find(id);
        if (!snapshot(id).workspace().snapshotToken().equals(request.snapshotToken())) {
            throw new AdminDataStaleException("공급정보가 바뀌었습니다. 최신 값을 다시 불러온 뒤 연결하세요.");
        }
        adminLinks.relink(announcement, announcementComplexId, request.housingComplexId(), actor);
        announcements.flush();
        return workspace(id);
    }

    /**
     * LH 원천 소유 공고에서 같은 전용면적 공급행이 없어 붙지 않은 LH 공급 원천 행. 오류가 아니라 정보로 보인다.
     * 공고문 소유 공고는 공고문이 공급행을 정하므로 보지 않는다.
     */
    private static List<SupplyCorrection.LhSupplyRow> unattachedLhSupplyRows(Announcement announcement,
            List<SupplyCorrection.LhSupplyRow> lhSupplyRows) {
        if (!announcement.suppliedByLh()) { return List.of(); }
        return lhSupplyRows.stream().filter(row -> row.attachedRowKey() == null).toList();
    }

    /**
     * 공급행이 기록한 원천행. 마이홈은 원천 키를 그대로 기록한다. GH 공급행 틀은 틀 행 키다. LH는 면적으로 이 행에 붙은 LH 줄
     * "LH:{panId}:SUPPLY:{순번}"마다 원천 키 "{panId}:{요청 해시}:{순번}" 중 공고 번호와 순번이 같은 것이 하나일 때만 대응한다.
     * 맞는 원천이 없으면 LH 줄 식별자를 그대로 돌려 찾을 수 없음을 알리고, 여러 개면 추정하지 않는다.
     */
    private List<SupplyCorrection.RowSource> rowSources(SupplyRow row, List<String> lhRows, List<QualityComparison.Source> sources) {
        var result = new ArrayList<SupplyCorrection.RowSource>();
        String key = LhRowAttachments.key(row);
        if (row.getMyhomeSourceRowIdentifier() != null) {
            result.add(new SupplyCorrection.RowSource(key, IngestSourceCategory.MYHOME_ANNOUNCEMENT.name(),
                    row.getMyhomeSourceRowIdentifier()));
        }
        for (String recorded : lhRows) {
            LhSourceIdentifier.parseSupply(recorded).ifPresent(supply -> {
                var candidates = sources.stream()
                        .filter(source -> source.category() == IngestSourceCategory.LH_ANNOUNCEMENT_SUPPLY)
                        .map(QualityComparison.Source::sourceKey)
                        .filter(sourceKey -> sourceKey.startsWith(supply.panId() + ":")
                                && sourceKey.endsWith(":" + supply.sourceOrder()))
                        .distinct().toList();
                if (candidates.size() <= 1) {
                    result.add(new SupplyCorrection.RowSource(key, IngestSourceCategory.LH_ANNOUNCEMENT_SUPPLY.name(),
                            candidates.isEmpty() ? recorded : candidates.getFirst()));
                }
            });
        }
        // GH 공급행 틀은 틀 행 키를 원천 키로 준다. PDF 추출 결과의 sourceRow가 이 키라 화면이 그 틀 행을 고친다.
        if (row.ghFrameRow()) {
            result.add(new SupplyCorrection.RowSource(key, IngestSourceCategory.GH_ANNOUNCEMENT.name(),
                    row.getSourceSupplyRowIdentifier()));
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
        checkNewTypes(planned);
        checkOneRowPerHousingType(before.rows(), planned, Set.copyOf(request.deletedRowKeys()));
        var warnings = new ArrayList<>(validateSchedulesAndCounts(id, planned));
        warnings.addAll(adminLinks.warnings(adminLinks.plan(announcement, before.rows(), planned)));
        if (same(before.rows(), planned)) {
            throw new IllegalArgumentException("변경한 공급정보가 없습니다.");
        }
        return new SupplyCorrection.Preview(hash(List.of(request, planned, candidateValues(planned))), before.rows(), planned,
                List.copyOf(warnings));
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
     * 요청 행의 작업 키마다 저장한 공급행을 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, SupplyRow> writeWithoutHistory(long id, SupplyCorrection.Apply request, SupplyCorrection.Preview preview,
            String actor) {
        var announcement = announcements.findByIdForUpdate(id).orElseThrow(AnnouncementNotFoundException::new);
        if (corrections.findByOperationId(request.correction().operationId()).isPresent()) {
            throw new AdminDataConflictException("이미 적용한 공급정보 보정 작업입니다. 최신값을 다시 확인하세요.");
        }
        confirm(id, preview, request);
        return writePlanned(announcement, preview, request, actor);
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
        if (!after.lhSupplyRows().isEmpty()) { audit.put("lhSupplyRows", after.lhSupplyRows()); }
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

    private Map<String, SupplyRow> writePlanned(Announcement announcement, SupplyCorrection.Preview preview,
            SupplyCorrection.Apply request, String actor) {
        var storedRows = rows.findAllByAnnouncement(announcement);
        var stored = storedRows.stream().collect(Collectors.toMap(SupplyRow::getId, Function.identity()));
        Map<String, SupplyRow> written = new LinkedHashMap<>();
        Map<String, HousingType> createdTypes = new HashMap<>();
        var beforeById = preview.before().stream().collect(Collectors.toMap(Row::id, Function.identity()));
        for (var row : preview.after()) {
            var saved = stored.get(row.id());
            var before = beforeById.get(row.id());
            // 순서만 밀린 행은 값과 관리자 수정 표시를 그대로 두고 순서만 옮긴다.
            if (row.id() != null && !same(before, row) && untouched(before, row)) {
                saved.reorder(row.displayOrder());
            } else if (row.id() == null || !same(before, row)) {
                saved = writeRow(announcement, saved, row, request.correction(), createdTypes);
            }
            written.put(row.key(), saved);
        }
        var links = adminLinks.plan(announcement, preview.before(), preview.after());
        adminLinks.apply(announcement, links, preview.after(), written, actor);
        for (var key : request.correction().deletedRowKeys()) {
            var row = preview.before().stream().filter(item -> item.key().equals(key)).findFirst().map(item -> stored.get(item.id()))
                    .orElseThrow();
            targets.deleteAll(targets.findAllBySupplyRow(row));
            rows.delete(row);
        }
        correctedLinks.remember(announcement.getSourceAnnouncementIdentifier(),
                changedLinks(preview, beforeById, written), request.correction().sourceUrl(),
                request.correction().reason(), actor);
        announcement.recordAdminSupplyChange();
        announcements.flush();
        return written;
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
            result.add(planRow(announcement, old, withAreaHousingType(old, row)));
        }
        if (!seen.equals(known.keySet())) {
            throw new IllegalArgumentException("기존 공급행을 생략할 수 없습니다. 필요 없는 행은 제외로 표시하세요.");
        }
        checkLeftGroups(known, result);
        return result.stream().sorted(Comparator.comparingInt(Row::displayOrder).thenComparing(Row::key)).toList();
    }

    /**
     * 옛 공통 모집 묶음에서 한 행이라도 빠지면 그 묶음의 나머지 행도 함께 빠지거나 제외해야 한다. 묶음에 남은 행이 공통 모집수를
     * 계속 세면 빠진 행의 모집수와 두 번 집계되기 때문이다.
     */
    private static void checkLeftGroups(Map<Long, Row> known, List<Row> planned) {
        var left = planned.stream().filter(row -> row.id() != null && row.groupId() == null
                && known.get(row.id()).groupId() != null).map(row -> known.get(row.id()).groupId()).collect(Collectors.toSet());
        for (var row : planned) {
            if (row.groupId() != null && left.contains(row.groupId()) && !row.excluded()) {
                throw new IllegalArgumentException(row.typeName() + ": 같은 공통 모집 묶음의 다른 행이 묶음에서 빠졌습니다. "
                        + "이 행도 묶음에서 빼거나 제외하세요.");
            }
        }
    }

    /**
     * 주택형을 정하지 않고 전용면적만 준 행은 서버가 단지 주택형을 정한다. 같은 면적 주택형이 있으면 {@link HousingTypeAreas#pick}으로
     * 고르고, 없으면 그 행의 형명·면적으로 새 주택형을 만든다. 관리자가 주택형을 직접 고르지 않아도 되게 하고, 화면과 서버가
     * 면적 반올림을 따로 계산해 어긋나지 않게 하려는 것이다. 미리보기 결과(after)에 정한 주택형이 담긴다.
     * 주택형이 있던 기존 행을 비워 보낸 요청은 관리자가 연결을 끊은 것이라 다시 채우지 않는다. 이번 저장에서 새로 넣거나 고친,
     * 제외하지 않은 행에만 적용한다.
     */
    private Row withAreaHousingType(Row old, Row row) {
        boolean clearsLink = old != null && old.housingTypeId() != null;
        // 손대지 않은 행(표시 순서만 밀린 행 포함)과 제외한 행은 다른 행을 저장할 때 주택형이 생기거나 바뀌지 않게 한다.
        boolean untouched = old != null && untouched(old, row.withLinksFrom(old));
        if (clearsLink || untouched || row.excluded() || row.housingComplexId() == null || row.groupId() != null || row.housingTypeId() != null
                || row.newHousingType() != null || row.exclusiveArea() == null || row.exclusiveArea().signum() <= 0) {
            return row;
        }
        var complex = complex(row.housingComplexId());
        var picked = HousingTypeAreas.pick(types.findAllByHousingComplex(complex), row.exclusiveArea(), row.typeName());
        if (picked.isPresent()) { return row.withHousingType(picked.get().getId(), null); }
        return row.withHousingType(null, new SupplyCorrection.NewType(newTypeName(row.typeName()), row.exclusiveArea(), row.supplyArea()));
    }

    /**
     * 새 단지 주택형 이름. 단지 주택형은 다른 공고와 단지 상세가 함께 쓰므로 이 공고의 표기를 이어 적은 이름(26A·26B,
     * "36A형(고령자) · 36A형(청년)")을 그대로 쓰지 않고 첫 표기에서 끝 괄호(신청자 구분)를 뗀 이름으로 만든다.
     */
    private static String newTypeName(String typeName) {
        String first = typeName.split("[·]")[0].strip();
        String core = first.replaceAll("\\s*\\([^()]*\\)\\s*$", "").strip();
        return core.isEmpty() ? first : core;
    }

    private Row planRow(Announcement announcement, Row old, Row row) {
        if (row.groupId() == null && row.sharedHouseholdCount() != null) {
            throw new IllegalArgumentException("공통 모집 묶음이 없는 행에 공통 모집수를 지정할 수 없습니다.");
        }
        var complex = complex(row.housingComplexId());
        var type = type(row, complex, "PREVIEW:" + row.key());
        // 옛 공통 모집 묶음에서 빠지는 것(groupId를 비움)만 허용한다. 새 묶음을 만들거나 다른 묶음으로 옮길 수는 없다.
        boolean leavesGroup = old != null && old.groupId() != null && row.groupId() == null;
        if (old == null && row.groupId() != null || old != null && !leavesGroup && !Objects.equals(old.groupId(), row.groupId())) {
            throw new IllegalArgumentException("공통 모집 묶음은 원천별 공급행 보정에서 변경하세요.");
        }
        if (old != null && old.groupId() != null && !leavesGroup && (!Objects.equals(old.sharedHouseholdCount(), row.sharedHouseholdCount())
                || !Objects.equals(old.housingComplexId(), row.housingComplexId()) || row.householdCount() != null)) {
            throw new IllegalArgumentException("공통 모집수와 묶음 단지는 원천별 공급행 보정에서 함께 변경하세요.");
        }
        // 기존 행의 원천 단지명은 정제가 소유하므로 요청 값과 관계없이 저장값을 유지한다.
        String complexName = old == null ? row.complexName() : old.complexName();
        // 지번을 모르는 단지의 공급행은 소재지 PNU가 없다. 빈 입력은 요청 행(SupplyCorrection.Row)이 null로 받는다.
        String pnu = row.pnu();
        var transientRow = SupplyRow.create(announcement, complex, type, "PREVIEW:" + row.key(), row.displayOrder(),
                complexName, row.typeName(), pnu, row.moveInMonth(), row.category(), null, row.householdCount());
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
                complexName, row.typeName(), pnu, row.moveInMonth(), row.category(), row.householdCount(),
                row.areaOverride(), decimal(exclusiveArea), decimal(supplyArea), plannedTargets, row.targetCountsComparable(),
                linkedTypes(old, row, complex, type));
        // 앞에 끼어든 행 때문에 표시 순서만 밀린 행은 관리자가 값을 고친 행이 아니다.
        if (old != null && !untouched(old, result) && !modified) {
            return new Row(result.id(), result.key(), result.housingComplexId(), result.housingComplexName(),
                    result.housingTypeId(), result.newHousingType(), result.groupId(), result.sharedHouseholdCount(), true,
                    result.excluded(), result.displayOrder(), result.complexName(), result.typeName(), result.pnu(),
                    result.moveInMonth(), result.category(), result.householdCount(), result.areaOverride(),
                    result.exclusiveArea(), result.supplyArea(), result.targets(), result.targetCountsComparable(),
                    result.linkedHousingTypes());
        }
        return result;
    }

    /**
     * 대표와 함께 모집하는 주택형(대표 제외). 생략하면 {@link Row#withLinksFrom}으로 채운다.
     * 한 행에는 전용면적마다 주택형 하나만 연결한다. 세부 타입은 전용면적마다 하나이고, 이 저장에서 만들 새 주택형과
     * 그 공고 값(linkedNewHousingTypeArea)은 행 안에서 전용면적으로 찾기 때문이다.
     */
    private List<LinkedHousingType> linkedTypes(Row old, Row row, HousingComplex complex, HousingType representative) {
        if (row.linkedHousingTypes() == null) { return row.withLinksFrom(old).linkedHousingTypes(); }
        if (row.linkedHousingTypes().isEmpty()) { return List.of(); }
        if (row.groupId() != null) {
            throw new IllegalArgumentException("공통 모집 묶음 행에는 함께 모집하는 주택형을 연결할 수 없습니다.");
        }
        if (representative == null) {
            throw new IllegalArgumentException(row.typeName() + ": 대표 주택형을 정한 뒤 함께 모집하는 주택형을 연결하세요.");
        }
        Set<BigDecimal> areas = new HashSet<>();
        areas.add(HousingTypeAreas.key(representative.getExclusiveArea()));
        for (var link : row.linkedHousingTypes()) {
            if ((link.housingTypeId() == null) == (link.newHousingType() == null)) {
                throw new IllegalArgumentException("함께 모집하는 주택형은 기존 주택형이나 새 주택형 중 하나로 지정하세요.");
            }
            BigDecimal area = link.newHousingType() == null ? existingType(link.housingTypeId(), complex).getExclusiveArea()
                    : link.newHousingType().exclusiveArea();
            if (!areas.add(HousingTypeAreas.key(area))) {
                throw new IllegalArgumentException(row.typeName() + ": 한 공급행에는 전용면적마다 주택형 하나만 연결하세요. "
                        + "대표 주택형과 같은 주택형이나 면적도 연결할 수 없습니다.");
            }
        }
        return row.withLinksFrom(old).linkedHousingTypes();
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

    private SupplyRow writeRow(Announcement announcement, SupplyRow stored, Row input, SupplyCorrection.Request request,
            Map<String, HousingType> createdTypes) {
        var complex = complex(input.housingComplexId());
        String identifier = "MANUAL:" + request.operationId() + ":" + input.key();
        var type = input.newHousingType() == null ? type(input, complex, null)
                : newType(complex, input, input.newHousingType(), identifier, createdTypes);
        List<HousingType> linked = new ArrayList<>();
        for (var link : input.linkedHousingTypes()) {
            var value = link.newHousingType();
            linked.add(value == null ? existingType(link.housingTypeId(), complex) : newType(complex, input, value,
                    identifier + ":" + HousingTypeAreas.key(value.exclusiveArea()).toPlainString(), createdTypes));
        }
        if (stored == null) {
            // 원천이 없는 직접 추가 행은 처음 입력한 표기를 원천 이름으로 남긴다.
            stored = rows.save(SupplyRow.create(announcement, complex, type,
                    "MANUAL:" + request.operationId() + ":" + input.key(), input.displayOrder(), input.complexName(),
                    input.typeName(), input.pnu(), input.moveInMonth(), input.category(), null, input.householdCount()));
        }
        if (input.groupId() == null && stored.getSupplyGroup() != null) {
            stored.leaveGroupByAdmin();
        }
        stored.reviseByAdmin(complex, type, input.typeName(), input.pnu(), input.moveInMonth(),
                input.category(), input.householdCount());
        stored.linkHousingTypesByAdmin(linked);
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

    /** 한 저장에서 같은 단지·전용면적의 새 주택형은 대표든 함께 모집하는 주택형이든 행이 달라도 하나를 만들어 함께 쓴다. */
    private HousingType newType(HousingComplex complex, Row input, SupplyCorrection.NewType value, String identifier,
            Map<String, HousingType> createdTypes) {
        return createdTypes.computeIfAbsent(input.newHousingTypeKey(value), key -> types.save(HousingType.createFromAnnouncement(
                complex, identifier, value.name(), value.exclusiveArea(), value.supplyArea(), null)));
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
            return HousingType.createFromAnnouncement(complex, identifier, input.name(), input.exclusiveArea(),
                    input.supplyArea(), null);
        }
        if (row.housingTypeId() == null) { return null; }
        return existingType(row.housingTypeId(), complex);
    }

    private HousingType existingType(long id, HousingComplex complex) {
        var type = types.findById(id).orElseThrow(() -> new AdminDataConflictException("연결할 주택형을 찾을 수 없습니다."));
        if (complex == null || !type.getHousingComplex().getId().equals(complex.getId())) {
            throw new IllegalArgumentException("선택한 단지에 속한 주택형을 선택하세요.");
        }
        return type;
    }

    /**
     * 새 주택형은 단지에 같은 전용면적 주택형이 없을 때만 만든다. 있으면 그 주택형을 고르게 하고, 휴지통에 있으면
     * 복구한 뒤 연결하게 한다. 같은 면적 주택형이 둘이 되면 공급행을 면적으로 맞출 수 없기 때문이다.
     */
    private void checkNewTypes(List<Row> planned) {
        for (var row : planned) {
            if (row.housingComplexId() == null) { continue; }
            var created = new ArrayList<SupplyCorrection.NewType>();
            if (row.newHousingType() != null) { created.add(row.newHousingType()); }
            row.linkedHousingTypes().stream().map(LinkedHousingType::newHousingType).filter(Objects::nonNull).forEach(created::add);
            for (var input : created) {
                var area = input.exclusiveArea();
                var same = types.findAllIncludingTrashByHousingComplexId(row.housingComplexId()).stream()
                        .filter(type -> HousingTypeAreas.sameArea(type.getExclusiveArea(), area)).toList();
                String label = row.typeName() + ": 전용 " + area.stripTrailingZeros().toPlainString() + "㎡ 주택형이 ";
                if (same.stream().anyMatch(type -> !type.isAdminDeleted())) {
                    throw new IllegalArgumentException(label + "이 단지에 있습니다. 새로 만들지 말고 그 주택형을 선택하세요.");
                }
                if (!same.isEmpty()) {
                    throw new IllegalArgumentException(label + "휴지통에 있습니다. 단지 상세에서 복구한 뒤 연결하세요.");
                }
            }
        }
    }

    /**
     * 한 단지에서 공급행은 전용면적마다 하나다. 이번 저장에서 바꾸거나 새로 만든 행이 제외하지 않은 다른 행과 같은 주택형을
     * 가리키면 거절한다. 다시 보정하지 않아 신청자 묶음마다 나뉜 채 남은 옛 공급행끼리는 손대지 않는 한 그대로 둔다.
     */
    private void checkOneRowPerHousingType(List<Row> before, List<Row> planned, Set<String> deleted) {
        var beforeByKey = before.stream().collect(Collectors.toMap(Row::key, Function.identity()));
        var active = planned.stream().filter(row -> !row.excluded() && !deleted.contains(row.key())).toList();
        for (var row : active) {
            // 앞에 끼어든 행 때문에 표시 순서만 밀린 행은 손대지 않은 행이다.
            if (row.id() != null && untouched(beforeByKey.get(row.key()), row)) { continue; }
            var mine = typeKeys(row);
            for (var other : active) {
                if (other == row) { continue; }
                var shared = typeKeys(other).stream().filter(mine::contains).findFirst();
                if (shared.isPresent()) {
                    throw new IllegalArgumentException(row.typeName() + ": '" + other.typeName() + "' 공급행과 같은 주택형을 가리킵니다. "
                            + "한 단지에서 공급행은 주택형(전용면적)마다 하나입니다. 한 행으로 합치고 나머지는 제외하거나 지우세요.");
                }
            }
        }
    }

    /** 행이 가리키는 주택형 키. 단지와 전용면적(소수 둘째 자리)이다. 같은 면적의 다른 이름 주택형(101A1·101A9)도 같은 키다. */
    private Set<String> typeKeys(Row row) {
        var keys = new HashSet<String>();
        if (row.housingComplexId() == null) { return keys; }
        var areas = new ArrayList<BigDecimal>();
        if (row.housingTypeId() != null) { types.findById(row.housingTypeId()).ifPresent(type -> areas.add(type.getExclusiveArea())); }
        if (row.newHousingType() != null) { areas.add(row.newHousingType().exclusiveArea()); }
        for (var link : row.linkedHousingTypes()) {
            if (link.housingTypeId() != null) { types.findById(link.housingTypeId()).ifPresent(type -> areas.add(type.getExclusiveArea())); }
            if (link.newHousingType() != null) { areas.add(link.newHousingType().exclusiveArea()); }
        }
        areas.forEach(area -> keys.add(row.housingComplexId() + ":" + HousingTypeAreas.key(area).toPlainString()));
        return keys;
    }

    private List<Object> candidateValues(List<Row> planned) {
        List<Object> result = new ArrayList<>();
        for (var row : planned) {
            var complex = complex(row.housingComplexId());
            if (complex != null) { result.add(List.of(complex.getId(), complex.getVersion(), complex.getName())); }
            var typeIds = new ArrayList<Long>();
            if (row.housingTypeId() != null) { typeIds.add(row.housingTypeId()); }
            row.linkedHousingTypes().stream().map(LinkedHousingType::housingTypeId).filter(Objects::nonNull).forEach(typeIds::add);
            for (var typeId : typeIds) {
                var type = types.findById(typeId).orElseThrow();
                var value = new LinkedHashMap<String, Object>();
                value.put("id", type.getId()); value.put("name", type.getName());
                value.put("exclusiveArea", type.getExclusiveArea()); value.put("supplyArea", type.getSupplyArea());
                value.put("householdCount", type.getTotalHouseholdCount());
                result.add(value);
            }
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
        Long representativeId = typeId;
        return new Row(row.getId(), LhRowAttachments.key(row), complexId, complexName(row.getHousingComplex()), typeId, null,
                groupId, shared, row.isAdminModified(), row.isAdminExcluded(), row.getDisplayOrder(),
                row.getSourceComplexName(), row.displayHousingTypeName(), row.getSupplyPnu(), row.getExpectedMoveInMonth(),
                row.getSupplyCategory(), row.getTotalSupplyHouseholdCount(), row.isAreaOverride(),
                decimal(row.effectiveExclusiveArea()), decimal(row.effectiveSupplyArea()),
                targets.findAllBySupplyRow(row).stream().sorted(Comparator.comparingInt(SupplyTarget::getDisplayOrder)
                        .thenComparing(SupplyTarget::getId)).map(this::targetData)
                        .sorted(Comparator.comparingInt(Target::displayOrder).thenComparing(Target::key)).toList(), row.isTargetCountsComparable(),
                row.getHousingTypes().stream().map(HousingType::getId).filter(id -> !id.equals(representativeId)).sorted()
                        .map(id -> new LinkedHousingType(id, null)).toList());
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

    /** 앞에 끼어든 행 때문에 표시 순서만 밀린 행은 관리자가 손대지 않은 행이다. 표시 순서를 뺀 값이 저장값과 같으면 true다. */
    private boolean untouched(Row before, Row row) {
        return same(before.withDisplayOrder(row.displayOrder()), row);
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
