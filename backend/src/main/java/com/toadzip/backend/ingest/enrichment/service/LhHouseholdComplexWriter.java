package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdTargetDecision;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdTargetDecisionRepository;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHousehold;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 단지를 정한 LH 원천 묶음의 세대수를 단지 주택형에 쓴다. 한 단지에 여러 묶음이 연결되면 관리자 결정이나 같은 세대수일 때만
 * 쓰고, 그렇지 않으면 묶음마다 실패로 남긴다. 주택형은 주거전용면적이 하나로 맞을 때만 고친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LhHouseholdComplexWriter {

    private final HousingTypeRepository housingTypeRepository;
    private final LhHouseholdComplexLinks links;
    private final LhHouseholdTargetDecisionRepository decisionRepository;

    LhHousingTypeHouseholdEnrichmentReport write(
            List<LhHouseholdMatchedSource> matchedSources,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        var decisions = decisionRepository.findAll().stream()
                .collect(Collectors.toMap(LhHouseholdTargetDecision::getHousingComplexId, decision -> decision));
        Map<Long, List<LhHouseholdMatchedSource>> groupsByComplex = new LinkedHashMap<>();
        matchedSources.forEach(matched -> groupsByComplex
                .computeIfAbsent(matched.complex().getId(), ignored -> new ArrayList<>()).add(matched));
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(0);
        for (List<LhHouseholdMatchedSource> group : groupsByComplex.values()) {
            report = report.plus(writeComplex(group, decisions.get(group.getFirst().complex().getId()), failures,
                    occurredAt));
        }
        return report;
    }

    // 한 단지에 매칭된 묶음이 하나면 그대로, 관리자 결정이 있으면 그 결정대로, 세대수가 모두 같으면 한 번만 쓴다.
    private LhHousingTypeHouseholdEnrichmentReport writeComplex(
            List<LhHouseholdMatchedSource> group,
            LhHouseholdTargetDecision decision,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        HousingComplex complex = group.getFirst().complex();
        if (decision != null) {
            return writeDecided(group, decision, failures, occurredAt);
        }
        if (group.size() == 1 || links.identical(group)) {
            return write(complex, group.getFirst().source().housingTypes());
        }
        logDuplicateMatch(group.getFirst(), group.size());
        return failGroup(group, LhHouseholdEnrichmentFailureReason.DUPLICATE_TARGET_COMPLEX,
                "같은 정제 단지에 세대수가 서로 다른 LH 원천 묶음 " + group.size() + "개가 연결됩니다.",
                failures, occurredAt);
    }

    private LhHousingTypeHouseholdEnrichmentReport writeDecided(
            List<LhHouseholdMatchedSource> group,
            LhHouseholdTargetDecision decision,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        HousingComplex complex = group.getFirst().complex();
        boolean current = links.sourcesFingerprint(group).equals(decision.getSourcesFingerprint())
                && links.decisionTargetFingerprint(complex, housingTypeRepository.findAllByHousingComplex(complex))
                        .equals(decision.getTargetFingerprint());
        if (!current) {
            return failGroup(group, LhHouseholdEnrichmentFailureReason.TARGET_DECISION_REVIEW_REQUIRED,
                    "저장한 단지 결정 이후 경쟁하는 LH 원천 묶음이나 단지가 바뀌었습니다. 다시 확인해 주세요.",
                    failures, occurredAt);
        }
        if (decision.getDecision() == LhHouseholdTargetDecision.Decision.SKIP) {
            return LhHousingTypeHouseholdEnrichmentReport.empty(0);
        }
        return group.stream().filter(matched -> matched.sourceKey().failureKey().equals(decision.getChosenSourceKey()))
                .findFirst().map(matched -> write(complex, matched.source().housingTypes()))
                .orElseGet(() -> LhHousingTypeHouseholdEnrichmentReport.empty(0));
    }

    private LhHousingTypeHouseholdEnrichmentReport failGroup(
            List<LhHouseholdMatchedSource> group,
            LhHouseholdEnrichmentFailureReason reason,
            String detail,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        LhHousingTypeHouseholdEnrichmentReport report = LhHousingTypeHouseholdEnrichmentReport.empty(0);
        for (LhHouseholdMatchedSource matchedSource : group) {
            failures.add(LhHouseholdEnrichmentFailure.create(
                    matchedSource.sourceKey().failureKey(),
                    matchedSource.source().areaName(),
                    matchedSource.source().supplyTypeName(),
                    matchedSource.source().complexName(),
                    reason,
                    detail,
                    occurredAt
            ));
            report = report.plus(LhHousingTypeHouseholdEnrichmentReport.failed());
        }
        return report;
    }

    private LhHousingTypeHouseholdEnrichmentReport write(
            HousingComplex complex,
            List<LhHousingTypeHousehold> sources
    ) {
        List<HousingType> housingTypes = housingTypeRepository.findAllByHousingComplex(complex).stream()
                .filter(type -> type.getSourceHousingTypeIdentifier() != null)
                .toList();
        int updatedCount = 0;
        int unchangedCount = 0;
        int unmatchedCount = 0;
        for (LhHousingTypeHousehold source : sources) {
            List<HousingType> matches = housingTypes.stream()
                    .filter(type -> type.getExclusiveArea().compareTo(source.exclusiveArea()) == 0)
                    .toList();
            if (matches.size() != 1) {
                unmatchedCount++;
                continue;
            }
            if (matches.getFirst().enrichHouseholdCountFromLh(source.totalHouseholdCount())) {
                updatedCount++;
                continue;
            }
            unchangedCount++;
        }
        return LhHousingTypeHouseholdEnrichmentReport.matched(
                updatedCount, unchangedCount, unmatchedCount
        );
    }

    private void logDuplicateMatch(LhHouseholdMatchedSource matchedSource, int sourceGroupCount) {
        log.warn(
                "event=lh_household.match.duplicate result=skipped source=lh_catalog "
                        + "sourceName={} targetId={} groups={}",
                matchedSource.source().complexName(),
                matchedSource.complex().getSourceComplexIdentifier(),
                sourceGroupCount
        );
    }
}
