package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSourceView;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdComplexLink;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdComplexLinkRepository;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSourceKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LH 임대단지 카탈로그 원천 묶음마다 세대수를 보강할 정제 단지를 정한다. 관리자가 저장한 연결을 이름 비교보다 먼저 쓰고,
 * 단지를 하나로 정하지 못한 묶음은 이유와 함께 failures에 담는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LhHouseholdComplexMatches {

    private final LhLeaseCatalogSourceReader sourceRepository;
    private final HousingComplexRepository complexRepository;
    private final LhHousingTypeHouseholdSourceMapper sourceMapper;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final LhHouseholdComplexLinkRepository linkRepository;
    private final LhHouseholdComplexLinks links;

    /** 현재 원천과 단지로 묶음별 매칭을 계산한다. 매칭하지 못한 묶음은 failures에 담는다. 저장하지 않는다. */
    CurrentMatches current(List<LhHouseholdEnrichmentFailure> failures, Instant occurredAt) {
        Map<LhHousingTypeHouseholdSourceKey, List<LhLeaseCatalogSourceView>> sourceGroups =
                sourceMapper.group(sourceRepository.findAllByOrderBySourceOrderAsc());
        List<HousingComplex> complexes = complexRepository.findAll();
        var indexedComplexes = matcher.index(complexes);
        var complexesById = complexes.stream().collect(Collectors.toMap(HousingComplex::getId, complex -> complex));
        var savedLinks = linkRepository.findAll().stream()
                .collect(Collectors.toMap(LhHouseholdComplexLink::getSourceKey, link -> link));
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(sourceGroups.size());
        List<LhHouseholdMatchedSource> matchedSources = new ArrayList<>();
        for (Map.Entry<LhHousingTypeHouseholdSourceKey, List<LhLeaseCatalogSourceView>> entry
                : sourceGroups.entrySet()) {
            Optional<LhHouseholdMatchedSource> matchedSource = match(
                    entry.getKey(), entry.getValue(), indexedComplexes, failures, occurredAt,
                    savedLinks.get(entry.getKey().failureKey()), complexesById
            );
            if (matchedSource.isEmpty()) {
                report = report.plus(LhHousingTypeHouseholdEnrichmentReport.failed());
                continue;
            }
            matchedSources.add(matchedSource.get());
        }
        return new CurrentMatches(matchedSources, report);
    }

    private Optional<LhHouseholdMatchedSource> match(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhLeaseCatalogSourceView> sources,
            Map<LhHousingTypeHouseholdMatcher.MatchKey, List<HousingComplex>> indexedComplexes,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt,
            LhHouseholdComplexLink savedLink,
            Map<Long, HousingComplex> complexesById
    ) {
        try {
            LhHousingTypeHouseholdSource source = sourceMapper.map(sources);
            if (savedLink != null) {
                return linkedMatch(sourceKey, sources, source, savedLink, complexesById, failures, occurredAt);
            }
            List<HousingComplex> matches = matcher.findMatches(indexedComplexes, source);
            int structuralCount = matcher.structuralMatches(indexedComplexes, source).size();
            if (matches.size() != 1) {
                log.warn(
                        "event=lh_household.match.unresolved result=skipped source=lh_catalog "
                                + "area={} sourceName={} candidates={}",
                        source.areaName(),
                        source.complexName(),
                        matches.size()
                );
                LhHouseholdEnrichmentFailureReason reason = LhHouseholdEnrichmentFailureReason.AMBIGUOUS_COMPLEX;
                if (matches.isEmpty()) {
                    reason = LhHouseholdEnrichmentFailureReason.COMPLEX_NOT_FOUND;
                }
                failures.add(failure(
                        sourceKey,
                        sources,
                        reason,
                        "공급유형·전체 세대수·지역이 같은 단지 " + structuralCount + "개 중 이름이 맞는 단지 "
                                + matches.size() + "개",
                        occurredAt
                ));
                return Optional.empty();
            }
            return Optional.of(new LhHouseholdMatchedSource(sourceKey, matches.getFirst(), source));
        }
        catch (IllegalArgumentException exception) {
            LhLeaseCatalogSourceView first = sources.getFirst();
            log.warn(
                    "event=lh_household.map.failed result=skipped source=lh_catalog "
                            + "sourceOrder={} detail={}",
                    first.getSourceOrder(),
                    exception.getMessage()
            );
            failures.add(failure(
                    sourceKey,
                    sources,
                    LhHouseholdEnrichmentFailureReason.INVALID_SOURCE,
                    failureDetail(exception),
                    occurredAt
            ));
            return Optional.empty();
        }
    }

    // 관리자가 저장한 연결을 이름 비교보다 먼저 쓴다. 원천이나 단지가 바뀌었으면 자동 매칭으로 대체하지 않는다.
    private Optional<LhHouseholdMatchedSource> linkedMatch(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhLeaseCatalogSourceView> sources,
            LhHousingTypeHouseholdSource source,
            LhHouseholdComplexLink savedLink,
            Map<Long, HousingComplex> complexesById,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        HousingComplex target = links.validTarget(savedLink, source,
                complexesById.get(savedLink.getHousingComplexId()));
        if (target == null) {
            failures.add(failure(sourceKey, sources, LhHouseholdEnrichmentFailureReason.MANUAL_LINK_REVIEW_REQUIRED,
                    "저장한 단지 연결의 LH 원천 또는 단지가 바뀌었습니다. 다시 확인하고 연결을 저장해 주세요.",
                    occurredAt));
            return Optional.empty();
        }
        return Optional.of(new LhHouseholdMatchedSource(sourceKey, target, source));
    }

    private String failureDetail(IllegalArgumentException exception) {
        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "LH 카탈로그 원천을 주택형 세대수 보강 입력으로 변환하지 못했습니다.";
        }
        return exception.getMessage();
    }

    private LhHouseholdEnrichmentFailure failure(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhLeaseCatalogSourceView> sources,
            LhHouseholdEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        LhLeaseCatalogSourceView first = sources.getFirst();
        return LhHouseholdEnrichmentFailure.create(
                sourceKey.failureKey(),
                first.getAreaName(),
                first.getSupplyTypeName(),
                first.getComplexLabel(),
                reason,
                detail,
                occurredAt
        );
    }

    record CurrentMatches(List<LhHouseholdMatchedSource> matchedSources, LhHousingTypeHouseholdEnrichmentReport report) {
    }
}
