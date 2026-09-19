package com.toadzip.backend.ingest.enrichment.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.repository.LhCatalogSourceRepository;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureRepository;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class LhHousingTypeHouseholdEnrichmentService {

    private final LhCatalogSourceRepository sourceRepository;
    private final HousingComplexRepository complexRepository;
    private final LhHousingTypeHouseholdSourceMapper sourceMapper;
    private final LhHousingTypeHouseholdMatcher matcher;
    private final LhHousingTypeHouseholdWriter writer;
    private final LhHouseholdEnrichmentFailureStore failureStore;
    private final LhHouseholdEnrichmentFailureRepository failureRepository;
    private final Clock clock;

    public LhHousingTypeHouseholdEnrichmentService(
            LhCatalogSourceRepository sourceRepository,
            HousingComplexRepository complexRepository,
            LhHousingTypeHouseholdSourceMapper sourceMapper,
            LhHousingTypeHouseholdMatcher matcher,
            LhHousingTypeHouseholdWriter writer,
            LhHouseholdEnrichmentFailureStore failureStore,
            LhHouseholdEnrichmentFailureRepository failureRepository,
            Clock clock
    ) {
        this.sourceRepository = sourceRepository;
        this.complexRepository = complexRepository;
        this.sourceMapper = sourceMapper;
        this.matcher = matcher;
        this.writer = writer;
        this.failureStore = failureStore;
        this.failureRepository = failureRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LhHouseholdEnrichmentFailureResponse> findFailures() {
        return failureRepository.findAllByStatusOrderBySourceKeyAsc(PENDING)
                .stream()
                .map(LhHouseholdEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhHouseholdEnrichmentFailureResponse> findFailureHistory() {
        return failureRepository.findAllByOrderBySourceKeyAsc()
                .stream()
                .map(LhHouseholdEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional
    public LhHousingTypeHouseholdEnrichmentReport enrichAll() {
        Map<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> sourceGroups =
                sourceMapper.group(sourceRepository.findAllByOrderBySourceOrderAsc());
        List<HousingComplex> complexes = complexRepository.findAll();
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(sourceGroups.size());
        List<MatchedSource> matchedSources = new ArrayList<>();
        List<LhHouseholdEnrichmentFailure> failures = new ArrayList<>();
        Instant occurredAt = clock.instant();
        for (Map.Entry<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> entry
                : sourceGroups.entrySet()) {
            Optional<MatchedSource> matchedSource = match(
                    entry.getKey(), entry.getValue(), complexes, failures, occurredAt
            );
            if (matchedSource.isEmpty()) {
                report = report.plus(LhHousingTypeHouseholdEnrichmentReport.failed());
                continue;
            }
            matchedSources.add(matchedSource.get());
        }
        report = report.plus(writeUniqueMatches(matchedSources, failures, occurredAt));
        failureStore.replaceAll(failures);
        logCompleted(report);
        return report;
    }

    private Optional<MatchedSource> match(
            LhHousingTypeHouseholdSourceKey sourceKey,
            List<LhCatalogSource> sources,
            List<HousingComplex> complexes,
            List<LhHouseholdEnrichmentFailure> failures,
            Instant occurredAt
    ) {
        try {
            LhHousingTypeHouseholdSource source = sourceMapper.map(sources);
            List<HousingComplex> matches = matcher.findMatches(complexes, source);
            if (matches.size() != 1) {
                log.warn(
                        "event=lh_household.match.unresolved result=skipped source=lh_catalog "
                                + "area={} sourceName={} candidates={}",
                        source.areaName(),
                        source.complexName(),
                        matches.size()
                );
                failures.add(failure(
                        sourceKey,
                        sources,
                        matches.isEmpty()
                                ? LhHouseholdEnrichmentFailureReason.COMPLEX_NOT_FOUND
                                : LhHouseholdEnrichmentFailureReason.AMBIGUOUS_COMPLEX,
                        "일치한 단지 수: " + matches.size(),
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
            report = report.plus(writer.write(
                    matchedSource.complex(),
                    matchedSource.source().housingTypes()
            ));
        }
        return report;
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
