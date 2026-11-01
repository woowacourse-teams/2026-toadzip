package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.RefineRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Request;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitRequest;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.SplitSelection;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplySplitWriter.SplitPlan;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplySplitWriter.SplitTarget;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingWriter.Correction;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeSupplyRowMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyMatcher.MyHomeSupplyMatchResult;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 공급 보정 요청을 정제 Writer가 쓸 보정으로 바꾼다. 조회 뒤 원천·후보가 바뀌었는지, 고른 단지·주택형으로 정제할 수
 * 있는지를 쓰기 전에 모두 확인해 하나라도 틀리면 아무것도 쓰지 않게 한다.
 */
@Component
@RequiredArgsConstructor
class SupplyCorrectionPlanner {
    private final SupplyCorrectionSources sources;
    private final MyHomeAnnouncementSupplyMatcher matcher;
    private final SupplyMatchingFingerprint fingerprints;

    PlannedCorrection refine(String identifier, RefineRequest request, String actor) {
        if (request.rows().isEmpty() && request.splitPlans().isEmpty()) {
            throw new InvalidIngestRequestException("연결하거나 나눌 공급행을 선택해 주세요.");
        }
        if (request.rememberSelection() || !request.splitPlans().isEmpty()) {
            SupplyMatchingEvidence.validate(request.sourceUrl(), request.reason());
        }
        var current = sources.resolved(identifier);
        var sourceRows = current.data().supplyRows();
        var expanded = SupplyRowGroupOrigins.expandedIn(sourceRows);
        var plans = request.splitPlans().stream().map(plan -> {
            var data = sources.splitRow(current, plan.rowIdentifier(), plan.token());
            return splitPlan(data, plan.rows(), request.sourceUrl(), request.reason(), actor, expanded.apply(data));
        }).toList();
        if (plans.stream().map(plan -> plan.source().sourceSupplyRowIdentifier()).distinct().count() != plans.size()) {
            throw new InvalidIngestRequestException("같은 원천행을 두 번 나눌 수 없습니다.");
        }
        var selections = new LinkedHashMap<String, MyHomeSupplyMatchResult>();
        for (var selection : request.rows()) {
            var match = selected(sourceRows, plans, selection);
            if (selections.putIfAbsent(selection.rowIdentifier(), match) != null) {
                throw new InvalidIngestRequestException("같은 공급행을 중복 선택할 수 없습니다.");
            }
            if (request.rememberSelection() && (selection.housingTypeId() == null
                    || !fingerprints.target(match.housingType()).equals(selection.targetToken()))) {
                throw new AdminDataConflictException("연결할 주택형을 명시적으로 선택하고 최신 정보로 확인해 주세요.");
            }
        }
        return new PlannedCorrection(current, new Correction(selections, plans));
    }

    private MyHomeSupplyMatchResult selected(List<MyHomeSupplyRowMappingData> sourceRows, List<SplitPlan> plans,
            Request selection) {
        var data = sourceRows.stream().filter(row -> row.sourceSupplyRowIdentifier()
                .equals(selection.rowIdentifier())).findFirst()
                .orElseThrow(() -> new InvalidIngestRequestException("해당 공고의 공급행이 아닙니다."));
        if (sources.isSplit(data)) {
            throw new AdminDataConflictException("복수 공급행 보정은 보정 공급행 확인·수정에서 처리해 주세요.");
        }
        if (plans.stream().anyMatch(plan -> plan.source().sourceSupplyRowIdentifier()
                .equals(selection.rowIdentifier()))) {
            throw new InvalidIngestRequestException("같은 공급행을 연결과 분할에 함께 선택할 수 없습니다.");
        }
        if (!sources.token(data).equals(selection.token())) {
            throw new AdminDataConflictException("원천이 변경되었습니다. 다시 조회해 주세요.");
        }
        var match = matcher.matchSelected(data, selection.complexId(), selection.housingTypeId());
        if (match.failure() != null) {
            throw new InvalidIngestRequestException("공급행 " + selection.rowIdentifier() + ": "
                    + match.failureDetail());
        }
        return match;
    }

    SplitPlan split(ResolvedAnnouncement current, MyHomeSupplyRowMappingData data, SplitRequest request,
            String actor) {
        return splitPlan(data, request.rows(), request.sourceUrl(), request.reason(), actor,
                SupplyRowGroupOrigins.expandedIn(current.data().supplyRows()).apply(data));
    }

    private SplitPlan splitPlan(MyHomeSupplyRowMappingData data, List<SplitSelection> rows, String sourceUrl,
            String reason, String actor, boolean expanded) {
        if (rows.stream().map(SplitSelection::complexId).distinct().count() != 1
                || rows.stream().map(value -> value.name().strip().toLowerCase(Locale.ROOT))
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
            return new SplitTarget(selection.id(), selection.name(), match, target);
        }).toList();
        return new SplitPlan(data, targets, sourceUrl, reason, actor, expanded);
    }

    record PlannedCorrection(ResolvedAnnouncement current, Correction correction) {

        boolean splitting() {
            return !correction.splits().isEmpty();
        }
    }
}
