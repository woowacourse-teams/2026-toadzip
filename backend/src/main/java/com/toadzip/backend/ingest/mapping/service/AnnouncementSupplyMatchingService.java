package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowGroupRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.domain.AnnouncementSupplyMatchDecision;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ComplexOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.HousingTypeOption;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.RefineRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.ReleaseRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SavedMatch;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitGroup;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitRow;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitSelection;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.repository.AnnouncementSupplyMatchDecisionRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementSupplyMatchingService {
    private final MyHomeAnnouncementSourceReader sources;
    private final MyHomeAnnouncementSourceMapper mapper;
    private final MyHomeAnnouncementSupplyRowResolver resolver;
    private final MyHomeAnnouncementSupplyMatcher matcher;
    private final AnnouncementRepository announcements;
    private final MyHomeAnnouncementMappingWriter writer;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final AnnouncementSupplyMatchDecisionRepository decisions;
    private final SupplyMatchingFingerprint fingerprints;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final SupplyRowRepository productRows;
    private final SupplyRowGroupRepository groups;
    private final AnnouncementSupplySplitWriter splitWriter;

    public List<Row> rows(String identifier) {
        var result = new LinkedHashMap<String, Row>();
        for (var data : supplyRows(identifier)) {
            var group = groups.findBySourceRowIdentifier(data.source().getSourceKey()).orElse(null);
            if (group != null) {
                var members = productRows.findAllByAnnouncement(group.getAnnouncement()).stream()
                        .filter(row -> row.getSupplyGroup() != null
                                && row.getSupplyGroup().getId().equals(group.getId()))
                        .sorted(Comparator.comparingInt(SupplyRow::getDisplayOrder))
                        .toList();
                boolean review = splitWriter.reviewRequired(group, data.source(), members);
                var split = new SplitGroup(group.getId(), group.getSharedHouseholdCount(), group.getSourceUrl(),
                        group.getReason(), group.getActor(), group.getUpdatedAt(), review,
                        members.stream().map(row -> new SplitRow(row.getId(), row.displayHousingTypeName(),
                                complexId(row), typeId(row))).toList());
                Long groupComplexId = null;
                if (!members.isEmpty()) {
                    groupComplexId = complexId(members.getFirst());
                }
                String failure = null;
                if (review) {
                    failure = "보존한 공급행의 원천 또는 주택형이 변경되었습니다. 근거를 다시 확인해 주세요.";
                }
                result.putIfAbsent(data.source().getSourceKey(), new Row(data.sourceSupplyRowIdentifier(), token(data),
                        data.sourceComplexName(), data.source().getHouseTyNm(), data.pnu(), null, null,
                        groupComplexId, null,
                        failure,
                        null, data.source().getSumSuplyCo(), split));
                continue;
            }
            var match = matcher.match(data);
            Long complexId = null;
            Long typeId = null;
            if (match.complex() != null) {
                complexId = match.complex().getId();
            }
            if (match.housingType() != null) {
                typeId = match.housingType().getId();
            }
            result.put(data.sourceSupplyRowIdentifier(), new Row(data.sourceSupplyRowIdentifier(),
                    token(data),
                    data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(),
                    data.exclusiveArea(), data.supplyArea(), complexId, typeId, match.failureDetail(),
                    savedMatch(data, match.failure() != null), data.source().getSumSuplyCo(), null));
        }
        return List.copyOf(result.values());
    }

    private Long complexId(SupplyRow row) {
        if (row.getHousingComplex() == null) {
            return null;
        }
        return row.getHousingComplex().getId();
    }

    private Long typeId(SupplyRow row) {
        if (row.getHousingType() == null) {
            return null;
        }
        return row.getHousingType().getId();
    }

    @Transactional
    public MyHomeAnnouncementMappingReport refine(String identifier, RefineRequest request, String actor) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다. 외부 API는 호출하지 않는다.
        IngestExecutionScope.verifyHeld();
        if (request.rows().isEmpty() && request.splitPlans().isEmpty()) {
            throw new InvalidIngestRequestException("연결하거나 나눌 공급행을 선택해 주세요.");
        }
        if (request.rememberSelection() || !request.splitPlans().isEmpty()) {
            validateEvidence(request);
        }
        var current = resolved(identifier);
        var sourceRows = current.data().supplyRows();
        var plans = request.splitPlans().stream().map(plan -> splitPlan(
                splitSource(current, plan.rowIdentifier(), plan.token()), plan.rows(),
                request.sourceUrl(), request.reason(), actor)).toList();
        if (plans.stream().map(plan -> plan.source().sourceSupplyRowIdentifier()).distinct().count() != plans.size()) {
            throw new InvalidIngestRequestException("같은 원천행을 두 번 나눌 수 없습니다.");
        }
        var selections = new LinkedHashMap<String, MyHomeSupplyMatchResult>();
        for (var selection : request.rows()) {
            var data = sourceRows.stream().filter(row -> row.sourceSupplyRowIdentifier()
                    .equals(selection.rowIdentifier())).findFirst()
                    .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 공급행이 아닙니다."));
            if (groups.findBySourceRowIdentifier(data.source().getSourceKey()).isPresent()) {
                throw new AdminDataConflictException("복수 공급행 보정은 보정 공급행 확인·수정에서 처리해 주세요.");
            }
            if (plans.stream().anyMatch(plan -> plan.source().sourceSupplyRowIdentifier()
                    .equals(selection.rowIdentifier()))) {
                throw new InvalidIngestRequestException("같은 공급행을 연결과 분할에 함께 선택할 수 없습니다.");
            }
            if (!token(data).equals(selection.token())) {
                throw new AdminDataConflictException("원천이 변경되었습니다. 다시 조회해 주세요.");
            }
            var match = matcher.matchSelected(data, selection.complexId(), selection.housingTypeId());
            if (match.failure() != null) {
                throw new InvalidIngestRequestException("공급행 " + selection.rowIdentifier() + ": "
                        + match.failureDetail());
            }
            if (selections.putIfAbsent(selection.rowIdentifier(), match) != null) {
                throw new InvalidIngestRequestException("같은 공급행을 중복 선택할 수 없습니다.");
            }
            if (request.rememberSelection() && (selection.housingTypeId() == null
                    || !fingerprints.target(match.housingType()).equals(selection.targetToken()))) {
                throw new AdminDataConflictException("연결할 주택형을 명시적으로 선택하고 최신 정보로 확인해 주세요.");
            }
        }
        var previous = writablePrevious(identifier, current, !plans.isEmpty());
        String rowsBefore = null;
        if (!plans.isEmpty()) {
            rowsBefore = json.writeValueAsString(rows(identifier));
        }
        try {
            var report = writer.writeSelected(current, previous, selections, plans).report();
            var announcement = announcements.findBySourceAnnouncementIdentifier(identifier).orElseThrow();
            if (request.rememberSelection()) {
                for (var data : sourceRows) {
                    var match = selections.get(data.sourceSupplyRowIdentifier());
                    if (match == null) {
                        continue;
                    }
                    var decision = decisions.findByRowIdentifier(data.sourceSupplyRowIdentifier())
                            .orElseGet(() -> new AnnouncementSupplyMatchDecision(data.sourceSupplyRowIdentifier()));
                    String before = json.writeValueAsString(savedMatch(data, false));
                    decision.confirm(match.complex().getId(), match.housingType().getId(), fingerprints.source(data),
                            fingerprints.target(match.housingType()), request.sourceUrl().strip(),
                            request.reason().strip(), actor);
                    decisions.saveAndFlush(decision);
                    changes.save(new AdminDataChange("ANNOUNCEMENT", announcement.getId(), "SAVE_SUPPLY_MATCH", actor,
                            before, json.writeValueAsString(Map.of("rowIdentifier", data.sourceSupplyRowIdentifier(),
                                    "decision", savedMatch(data, false)))));
                }
            }
            if (!plans.isEmpty()) {
                productRows.flush();
                changes.save(new AdminDataChange("ANNOUNCEMENT", announcement.getId(), "SPLIT_SUPPLY", actor,
                        rowsBefore, json.writeValueAsString(rows(identifier))));
            }
            return report;
        }
        catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage());
        }
    }

    /**
     * 선택 없이 저장한 매칭과 자동 매칭으로 이 공고만 다시 정제한다. 보정 결과 화면에서 오류가 풀렸는지 바로 확인하는 용도다.
     * 단건 정제라 매칭이나 LH 보강이 하나라도 실패하면 전체를 되돌리고, 실패 기록은 바꾸지 않는다.
     */
    @Transactional
    public MyHomeAnnouncementMappingReport rerun(String identifier) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다. 외부 API는 호출하지 않는다.
        IngestExecutionScope.verifyHeld();
        var current = resolved(identifier);
        var previous = writablePrevious(identifier, current, false);
        try {
            return writer.writeSelected(current, previous, Map.of(), List.of()).report();
        }
        catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage());
        }
    }

    // 정제로 덮어쓸 수 있는 공고인지 확인하고, 연결할 이전 공고를 돌려준다.
    private Announcement writablePrevious(String identifier,
            MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement current, boolean splitting) {
        var existing = announcements.findBySourceAnnouncementIdentifierForUpdate(identifier).orElse(null);
        if (existing != null && existing.isAdminDeleted()) {
            throw new InvalidIngestRequestException("삭제된 공고입니다. 공고 관리에서 먼저 복구해 주세요.");
        }
        if (existing != null && existing.isAdminModified() && existing.getProvider() != current.data().provider()) {
            throw new InvalidIngestRequestException("관리자가 변경한 공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
        if (existing != null && splitting && existing.getProvider() != current.data().provider()) {
            throw new AdminDataConflictException("공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
        var previous = announcements.findBySourceAnnouncementIdentifier(
                current.data().previousSourceAnnouncementIdentifier()).orElse(null);
        if (current.data().previousSourceAnnouncementIdentifier() != null && previous == null) {
            throw new InvalidIngestRequestException("이전 공고를 먼저 등록해 주세요.");
        }
        return previous;
    }

    /**
     * 공고 보정에서 바꾼 공급행 연결을 원천행별 저장 매칭으로 남긴다. 재정제는 자동 매칭보다 이 매칭을 먼저 쓴다.
     * 원천에 없는 행과 분할 보정 행은 대상이 아니다. 연결을 지운 행은 저장 매칭도 해제한다.
     * 원천을 해석할 수 없거나 원천과 공급유형이 다른 단지라 정제가 쓸 수 없는 연결은 저장하지 않는다.
     */
    @Transactional
    public void rememberCorrectedLinks(String identifier, Map<String, HousingType> links, String sourceUrl,
            String reason, String actor) {
        if (identifier == null || links.isEmpty()) {
            return;
        }
        var current = sources.findAllByPblancIdOrderByIdAsc(identifier);
        if (current.isEmpty()) {
            return;
        }
        List<MyHomeSupplyRowMappingData> sourceRows;
        try {
            sourceRows = resolver.resolve(mapper.map(current)).data().supplyRows();
        }
        catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            return;
        }
        for (var data : sourceRows) {
            String rowIdentifier = data.sourceSupplyRowIdentifier();
            if (!links.containsKey(rowIdentifier)
                    || groups.findBySourceRowIdentifier(data.source().getSourceKey()).isPresent()) {
                continue;
            }
            var saved = decisions.findByRowIdentifier(rowIdentifier);
            var type = links.get(rowIdentifier);
            if (type == null) {
                saved.ifPresent(decisions::delete);
                continue;
            }
            var match = matcher.matchSelected(data, type.getHousingComplex().getId(), type.getId());
            if (match.failure() != null) {
                continue;
            }
            var decision = saved.orElseGet(() -> new AnnouncementSupplyMatchDecision(rowIdentifier));
            decision.confirm(match.complex().getId(), type.getId(), fingerprints.source(data),
                    fingerprints.target(type), sourceUrl.strip(), reason.strip(), actor);
            decisions.save(decision);
        }
        decisions.flush();
    }

    private String token(MyHomeSupplyRowMappingData data) {
        return fingerprints.splitReview(fingerprints.review(data,
                decisions.findByRowIdentifier(data.sourceSupplyRowIdentifier()).orElse(null)),
                groups.findBySourceRowIdentifier(data.source().getSourceKey()).orElse(null));
    }

    private SavedMatch savedMatch(MyHomeSupplyRowMappingData data, boolean reviewRequired) {
        return decisions.findByRowIdentifier(data.sourceSupplyRowIdentifier()).map(value -> new SavedMatch(
                value.getComplexId(), value.getHousingTypeId(), value.getSourceUrl(), value.getReason(),
                value.getActor(), value.getUpdatedAt(), reviewRequired)).orElse(null);
    }

    private void validateEvidence(RefineRequest request) {
        validateEvidence(request.sourceUrl(), request.reason());
    }

    private void validateEvidence(String sourceUrl, String reason) {
        if (reason == null || reason.isBlank() || sourceUrl == null) {
            throw new InvalidIngestRequestException("저장할 매칭의 공식 출처 URL과 확인 근거를 입력해 주세요.");
        }
        try {
            var uri = java.net.URI.create(sourceUrl.strip());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new InvalidIngestRequestException("공식 출처는 올바른 HTTP 또는 HTTPS URL로 입력해 주세요.");
        }
    }

    @Transactional
    public List<Row> split(String identifier, SplitRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        validateEvidence(request.sourceUrl(), request.reason());
        var current = resolved(identifier);
        var data = splitSource(current, request.rowIdentifier(), request.token());
        var existing = announcements.findBySourceAnnouncementIdentifierForUpdate(identifier).orElse(null);
        if (existing != null && (existing.isAdminDeleted() || existing.getProvider() != current.data().provider())) {
            throw new AdminDataConflictException("삭제 상태 또는 공고 기관이 원천과 다릅니다. 공고 관리에서 확인해 주세요.");
        }
        var plan = splitPlan(data, request.rows(), request.sourceUrl(), request.reason(), actor);
        var previous = announcements.findBySourceAnnouncementIdentifier(
                current.data().previousSourceAnnouncementIdentifier())
                .orElse(null);
        if (current.data().previousSourceAnnouncementIdentifier() != null && previous == null) {
            throw new InvalidIngestRequestException("이전 공고를 먼저 등록해 주세요.");
        }
        String before = json.writeValueAsString(rows(identifier));
        try {
            writer.writeSplit(current, previous, plan);
        } catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException("정제 실패: " + exception.getMessage());
        }
        productRows.flush();
        announcements.flush();
        var result = rows(identifier);
        var announcement = announcements.findBySourceAnnouncementIdentifier(identifier).orElseThrow();
        changes.save(new AdminDataChange("ANNOUNCEMENT", announcement.getId(), "SPLIT_SUPPLY", actor,
                before, json.writeValueAsString(result)));
        return result;
    }

    private MyHomeSupplyRowMappingData splitSource(MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement current,
            String rowIdentifier, String token) {
        var data = current.data().supplyRows().stream()
                .filter(value -> value.sourceSupplyRowIdentifier().equals(rowIdentifier)).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 원천행이 아닙니다."));
        if (!token(data).equals(token)) {
            throw new AdminDataConflictException("원천 또는 보정이 변경되었습니다. 다시 조회해 주세요.");
        }
        return data;
    }

    private AnnouncementSupplySplitWriter.SplitPlan splitPlan(MyHomeSupplyRowMappingData data,
            List<SplitSelection> rows, String sourceUrl, String reason, String actor) {
        if (rows.stream().map(SplitSelection::complexId).distinct().count() != 1
                || rows.stream().map(value -> value.name().strip().toLowerCase(java.util.Locale.ROOT))
                        .distinct().count() != rows.size()
                || rows.stream().map(SplitSelection::housingTypeId).distinct().count() != rows.size()) {
            throw new InvalidIngestRequestException("같은 단지의 서로 다른 주택형과 중복되지 않는 공고 주택형명을 선택해 주세요.");
        }
        var targets = rows.stream().map(selection -> {
            var match = matcher.matchSelected(data, selection.complexId(), selection.housingTypeId());
            if (match.failure() != null) {
                throw new InvalidIngestRequestException(match.failureDetail());
            }
            String target = fingerprints.target(match.housingType());
            if (!target.equals(selection.targetToken())) {
                throw new AdminDataConflictException("주택형 후보가 변경되었습니다. 다시 확인해 주세요.");
            }
            return new AnnouncementSupplySplitWriter.SplitTarget(selection.id(), selection.name(), match, target);
        }).toList();
        return new AnnouncementSupplySplitWriter.SplitPlan(data, targets, sourceUrl, reason, actor);
    }

    @Transactional
    public List<Row> release(String identifier, ReleaseRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        var data = supplyRows(identifier).stream()
                .filter(row -> row.sourceSupplyRowIdentifier().equals(request.rowIdentifier())).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 공급행이 아닙니다."));
        if (!token(data).equals(request.token())) {
            throw new AdminDataConflictException("원천 또는 저장한 선택이 변경되었습니다. 다시 조회해 주세요.");
        }
        var decision = decisions.findByRowIdentifier(request.rowIdentifier())
                .orElseThrow(() -> new AdminDataConflictException("저장한 매칭이 없습니다. 다시 조회해 주세요."));
        var announcement = announcements.findBySourceAnnouncementIdentifierForUpdate(identifier).orElseThrow();
        String before = json.writeValueAsString(savedMatch(data, false));
        decisions.delete(decision);
        decisions.flush();
        changes.save(new AdminDataChange("ANNOUNCEMENT", announcement.getId(), "RELEASE_SUPPLY_MATCH", actor,
                before, json.writeValueAsString(Map.of("rowIdentifier", request.rowIdentifier(),
                        "reason", request.reason().strip()))));
        return rows(identifier);
    }

    public List<ComplexOption> complexes(String query) {
        if (query == null || query.isBlank() || query.length() > 100) {
            throw new InvalidIngestRequestException("단지 이름 또는 주소를 1~100자로 입력해 주세요.");
        }
        String keyword = query.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return complexes.searchAdmin("%" + keyword + "%", query.strip(), "", "", "", "", "",
                false, false, "", PageRequest.of(0, 20)).stream()
                .map(value -> new ComplexOption(value.getId(), value.getName(),
                        value.getAddress().getRoadAddress(), value.getSupplyType())).toList();
    }

    public List<HousingTypeOption> housingTypes(long complexId) {
        var complex = complexes.findById(complexId)
                .filter(value -> !value.isAdminDeleted())
                .orElseThrow(() -> new InvalidIngestRequestException("선택한 단지가 없습니다."));
        return types.findAllByHousingComplex(complex).stream()
                .sorted(java.util.Comparator.comparing(com.toadzip.backend.housing.domain.HousingType::getId))
                .map(value -> new HousingTypeOption(value.getId(), value.getName(),
                        value.getExclusiveArea(), value.getSupplyArea(), value.getTotalHouseholdCount(),
                        fingerprints.target(value))).toList();
    }

    private List<MyHomeSupplyRowMappingData> supplyRows(String identifier) {
        return resolved(identifier).data().supplyRows();
    }

    private MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement resolved(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 100) {
            throw new InvalidIngestRequestException("공고 ID가 올바르지 않습니다.");
        }
        var current = sources.findAllByPblancIdOrderByIdAsc(identifier);
        if (current.isEmpty()) {
            throw new InvalidIngestRequestException("공고 원천이 없습니다. 먼저 공고를 수집해 주세요.");
        }
        try {
            return resolver.resolve(mapper.map(current));
        }
        catch (MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException exception) {
            throw new InvalidIngestRequestException(exception.getMessage());
        }
    }
}
