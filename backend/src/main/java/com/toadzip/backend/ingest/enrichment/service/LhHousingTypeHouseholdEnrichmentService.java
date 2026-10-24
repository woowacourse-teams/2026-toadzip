package com.toadzip.backend.ingest.enrichment.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceReader;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdComplexLink;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdComplexLinkRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHousehold;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSourceKey;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class LhHousingTypeHouseholdEnrichmentService {

    private final LhLeaseCatalogSourceReader sourceRepository;
    private final HousingComplexRepository complexRepository;
    private final HousingTypeRepository housingTypeRepository;
    private final LhHousingTypeHouseholdSourceMapper sourceMapper;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final LhHouseholdEnrichmentFailureStore failureStore;
    private final LhHouseholdEnrichmentFailureRepository failureRepository;
    private final Clock clock;
    private final LhHouseholdComplexLinkRepository linkRepository;
    private final LhHouseholdComplexLinks links;

    @Transactional(readOnly = true)
    public List<LhHouseholdEnrichmentFailureResponse> findFailures(int page, int size) {
        return failureRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                        PENDING,
                        PageRequest.of(page, size)
                )
                .stream()
                .map(LhHouseholdEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhHouseholdEnrichmentFailureResponse> findFailureHistory(int page, int size) {
        return failureRepository.findAllByOrderBySourceKeyAscIdAsc(PageRequest.of(page, size))
                .stream()
                .map(LhHouseholdEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional
    public LhHousingTypeHouseholdEnrichmentReport enrichAll() {
        Map<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> sourceGroups =
                sourceMapper.group(sourceRepository.findAllByOrderBySourceOrderAsc());
        List<HousingComplex> complexes = complexRepository.findAll();
        var indexedComplexes = matcher.index(complexes);
        var complexesById = complexes.stream().collect(Collectors.toMap(HousingComplex::getId, complex -> complex));
        var savedLinks = linkRepository.findAll().stream()
                .collect(Collectors.toMap(LhHouseholdComplexLink::getSourceKey, link -> link));
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(sourceGroups.size());
        List<MatchedSource> matchedSources = new ArrayList<>();
        List<LhHouseholdEnrichmentFailure> failures = new ArrayList<>();
        Instant occurredAt = clock.instant();
        for (Map.Entry<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> entry
                : sourceGroups.entrySet()) {
            Optional<MatchedSource> matchedSource = match(
                    entry.getKey(), entry.getValue(), indexedComplexes, failures, occurredAt,
                    savedLinks.get(entry.getKey().failureKey()), complexesById
            );
            if (matchedSource.isEmpty()) {
                report = report.plus(LhHousingTypeHouseholdEnrichmentReport.failed());
                continue;
            }
            matchedSources.add(matchedSource.get());
        }
        report = report.plus(writeUniqueMatches(matchedSources, failures, occurredAt));
        failureStore.reconcileAfterRun(
                failures,
                IngestExecutionContext.currentExecutionId().orElse(null)
        );
        logCompleted(report);
        return report;
    }

    private Optional<MatchedSource> match(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhCatalogSource> sources,
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
            return Optional.of(new MatchedSource(sourceKey, matches.getFirst(), source));
        }
        catch (IllegalArgumentException exception) {
            LhCatalogSource first = sources.getFirst();
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
    private Optional<MatchedSource> linkedMatch(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhCatalogSource> sources,
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
        return Optional.of(new MatchedSource(sourceKey, target, source));
    }

    private String failureDetail(IllegalArgumentException exception) {
        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "LH 카탈로그 원천을 주택형 세대수 보강 입력으로 변환하지 못했습니다.";
        }
        return exception.getMessage();
    }

    private LhHousingTypeHouseholdEnrichmentReport writeUniqueMatches(
            List<MatchedSource> matchedSources,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        Map<Long, Integer> sourceGroupCounts = sourceGroupCounts(matchedSources);
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(0);
        for (MatchedSource matchedSource : matchedSources) {
            int sourceGroupCount = sourceGroupCounts.get(matchedSource.complex().getId());
            if (sourceGroupCount > 1) {
                logDuplicateMatch(matchedSource, sourceGroupCount);
                failures.add(LhHouseholdEnrichmentFailure.create(
                        matchedSource.sourceKey().failureKey(),
                        matchedSource.source().areaName(),
                        matchedSource.source().supplyTypeName(),
                        matchedSource.source().complexName(),
                        LhHouseholdEnrichmentFailureReason.DUPLICATE_TARGET_COMPLEX,
                        "같은 정제 단지에 연결된 LH 원천 그룹 수: " + sourceGroupCount,
                        occurredAt
                ));
                report = report.plus(LhHousingTypeHouseholdEnrichmentReport.failed());
                continue;
            }
            report = report.plus(write(
                    matchedSource.complex(),
                    matchedSource.source().housingTypes()
            ));
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

    private LhHouseholdEnrichmentFailure failure(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhCatalogSource> sources,
            LhHouseholdEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        LhCatalogSource first = sources.getFirst();
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

    private Map<Long, Integer> sourceGroupCounts(List<MatchedSource> matchedSources) {
        Map<Long, Integer> result = new HashMap<>();
        for (MatchedSource matchedSource : matchedSources) {
            result.merge(matchedSource.complex().getId(), 1, Integer::sum);
        }
        return result;
    }

    private void logDuplicateMatch(MatchedSource matchedSource, int sourceGroupCount) {
        log.warn(
                "event=lh_household.match.duplicate result=skipped source=lh_catalog "
                        + "sourceName={} targetId={} groups={}",
                matchedSource.source().complexName(),
                matchedSource.complex().getSourceComplexIdentifier(),
                sourceGroupCount
        );
    }

    private void logCompleted(LhHousingTypeHouseholdEnrichmentReport report) {
        log.info(
                "event=lh_household.enrich.completed result=success source=lh_catalog "
                        + "sources={} matched={} failed={} updatedTypes={}",
                report.sourceComplexCount(),
                report.matchedComplexCount(),
                report.failedSourceComplexCount(),
                report.updatedHousingTypeCount()
        );
    }

    private record MatchedSource(
            LhHousingTypeHouseholdSourceKey sourceKey,
            HousingComplex complex,
            LhHousingTypeHouseholdSource source
    ) {
    }
}
