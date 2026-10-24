package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdTargetDecision;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.HousingTypeCount;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView.ComplexHousingType;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView.ReleaseRequest;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView.SaveRequest;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView.SavedDecision;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView.SourceGroup;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdTargetDecisionRepository;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService.MatchedSource;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 세대수가 서로 다른 LH 묶음 여러 개가 같은 단지에 매칭될 때, 관리자가 쓸 묶음을 정하거나 보강하지 않기로 결정한다.
 * 결정은 다음 세대수 보강부터 반영된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LhHouseholdTargetDecisionService {
    private static final String RESOURCE = "LH_HOUSEHOLD_DECISION";

    private final LhHouseholdEnrichmentFailureRepository failures;
    private final LhHousingTypeHouseholdEnrichmentService enrichment;
    private final HousingTypeRepository types;
    private final LhHouseholdTargetDecisionRepository decisions;
    private final LhHouseholdComplexLinks fingerprints;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final Clock clock;

    public LhHouseholdTargetDecisionView view(long failureId) {
        return view(context(failureId));
    }

    @Transactional
    public LhHouseholdTargetDecisionView save(long failureId, SaveRequest request, String actor) {
        // POST 인터셉터가 트랜잭션 종료까지 전역 수집 잠금을 유지한다.
        IngestExecutionScope.verifyHeld();
        var context = verifiedContext(failureId, request.token());
        if (context.group().size() < 2) {
            throw new AdminDataConflictException("같은 단지에 매칭된 LH 묶음이 둘 이상일 때만 결정할 수 있습니다.");
        }
        var decision = LhHouseholdTargetDecision.Decision.valueOf(request.decision());
        String chosen = null;
        if (decision == LhHouseholdTargetDecision.Decision.USE_SOURCE) {
            chosen = context.group().stream().map(matched -> matched.sourceKey().failureKey())
                    .filter(key -> key.equals(request.chosenSourceKey())).findFirst()
                    .orElseThrow(() -> new InvalidIngestRequestException("이 단지를 두고 경쟁하는 LH 묶음 중에서 골라 주세요."));
        }
        var saved = context.decision();
        if (saved == null) {
            saved = new LhHouseholdTargetDecision(context.complex().getId());
        }
        String before = json.writeValueAsString(savedDecision(saved, context));
        saved.confirm(decision, chosen, fingerprints.sourcesFingerprint(context.group()),
                fingerprints.decisionTargetFingerprint(context.complex(), context.housingTypes()),
                request.reason().strip(), actor);
        decisions.saveAndFlush(saved);
        changes.save(new AdminDataChange(RESOURCE, saved.getId(), "SAVE_LH_HOUSEHOLD_DECISION", actor, before,
                json.writeValueAsString(Map.of("complexId", context.complex().getId(),
                        "decision", savedDecision(saved, context(failureId))))));
        return view(context(failureId));
    }

    @Transactional
    public LhHouseholdTargetDecisionView release(long failureId, ReleaseRequest request, String actor) {
        IngestExecutionScope.verifyHeld();
        var context = verifiedContext(failureId, request.token());
        var saved = context.decision();
        if (saved == null) {
            throw new AdminDataConflictException("저장한 단지 결정이 없습니다. 다시 조회해 주세요.");
        }
        long decisionId = saved.getId();
        String before = json.writeValueAsString(Map.of("complexId", context.complex().getId(),
                "decision", savedDecision(saved, context)));
        decisions.delete(saved);
        decisions.flush();
        changes.save(new AdminDataChange(RESOURCE, decisionId, "RELEASE_LH_HOUSEHOLD_DECISION", actor, before,
                json.writeValueAsString(Map.of("complexId", context.complex().getId(),
                        "reason", request.reason().strip()))));
        return view(context(failureId));
    }

    private Context verifiedContext(long failureId, String token) {
        var context = context(failureId);
        if (context.complex() == null) {
            throw new AdminDataConflictException("이 묶음은 지금 어느 단지에도 매칭되지 않습니다. 다시 조회해 주세요.");
        }
        if (!token(context).equals(token)) {
            throw new AdminDataConflictException("경쟁하는 LH 묶음이나 단지, 저장한 결정이 바뀌었습니다. 다시 조회해 주세요.");
        }
        return context;
    }

    private Context context(long failureId) {
        var failure = failures.findById(failureId)
                .orElseThrow(() -> new InvalidIngestRequestException("세대수 보강 오류가 없습니다."));
        List<MatchedSource> matches = enrichment.currentMatches(new ArrayList<>(), clock.instant()).matchedSources();
        var own = matches.stream().filter(matched -> matched.sourceKey().failureKey().equals(failure.getSourceKey()))
                .findFirst().orElse(null);
        if (own == null) {
            return new Context(failureId, failure.getSourceKey(), null, List.of(), List.of(), null);
        }
        HousingComplex complex = own.complex();
        List<MatchedSource> group = matches.stream()
                .filter(matched -> matched.complex().getId().equals(complex.getId()))
                .sorted(Comparator.comparing(matched -> matched.sourceKey().failureKey())).toList();
        List<HousingType> housingTypes = types.findAllByHousingComplex(complex).stream()
                .sorted(Comparator.comparing(HousingType::getExclusiveArea).thenComparing(HousingType::getId)).toList();
        return new Context(failureId, failure.getSourceKey(), complex, group, housingTypes,
                decisions.findByHousingComplexId(complex.getId()).orElse(null));
    }

    private String token(Context context) {
        String decisionVersion = "";
        if (context.decision() != null) {
            decisionVersion = context.decision().getId() + ":" + context.decision().getVersion();
        }
        String target = "";
        if (context.complex() != null) {
            target = fingerprints.decisionTargetFingerprint(context.complex(), context.housingTypes());
        }
        return fingerprints.hash(context.sourceKey(), fingerprints.sourcesFingerprint(context.group()), target,
                decisionVersion);
    }

    private LhHouseholdTargetDecisionView view(Context context) {
        if (context.complex() == null) {
            return new LhHouseholdTargetDecisionView(context.failureId(), token(context), null, List.of(), false,
                    false, null);
        }
        var complex = context.complex();
        var housingTypes = context.housingTypes().stream().map(type -> new ComplexHousingType(type.getId(),
                type.getName(), type.getExclusiveArea(), type.getTotalHouseholdCount())).toList();
        var groups = context.group().stream().map(matched -> new SourceGroup(matched.sourceKey().failureKey(),
                matched.source().complexName(), matched.source().supplyTypeName(), matched.source().areaName(),
                matched.source().totalHouseholdCount(), matched.source().housingTypes().stream()
                        .map(type -> new HousingTypeCount(type.exclusiveArea(), type.totalHouseholdCount())).toList(),
                matched.sourceKey().failureKey().equals(context.sourceKey()))).toList();
        boolean identical = context.group().size() > 1 && fingerprints.identical(context.group());
        SavedDecision decision = null;
        if (context.decision() != null) {
            decision = savedDecision(context.decision(), context);
        }
        return new LhHouseholdTargetDecisionView(context.failureId(), token(context),
                new LhHouseholdTargetDecisionView.Complex(complex.getId(), complex.getName(), complex.getSupplyType(),
                        complex.getTotalHouseholdCount(), complex.getAddress().getRoadAddress(), housingTypes),
                groups, identical, context.group().size() > 1 && !identical, decision);
    }

    private SavedDecision savedDecision(LhHouseholdTargetDecision decision, Context context) {
        if (decision.getDecision() == null) {
            return null;
        }
        boolean reviewRequired = context.complex() == null
                || !fingerprints.sourcesFingerprint(context.group()).equals(decision.getSourcesFingerprint())
                || !fingerprints.decisionTargetFingerprint(context.complex(), context.housingTypes())
                        .equals(decision.getTargetFingerprint());
        return new SavedDecision(decision.getDecision().name(), decision.getChosenSourceKey(), decision.getReason(),
                decision.getActor(), decision.getUpdatedAt(), reviewRequired);
    }

    private record Context(long failureId, String sourceKey, HousingComplex complex, List<MatchedSource> group,
            List<HousingType> housingTypes, LhHouseholdTargetDecision decision) {
    }
}
