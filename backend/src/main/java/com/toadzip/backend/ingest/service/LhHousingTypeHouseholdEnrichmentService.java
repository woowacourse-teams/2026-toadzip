package com.toadzip.backend.ingest.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.domain.LhCatalogSource;
import com.toadzip.backend.ingest.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.repository.LhCatalogSourceRepository;
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

    public LhHousingTypeHouseholdEnrichmentService(
            LhCatalogSourceRepository sourceRepository,
            HousingComplexRepository complexRepository,
            LhHousingTypeHouseholdSourceMapper sourceMapper,
            LhHousingTypeHouseholdMatcher matcher,
            LhHousingTypeHouseholdWriter writer
    ) {
        this.sourceRepository = sourceRepository;
        this.complexRepository = complexRepository;
        this.sourceMapper = sourceMapper;
        this.matcher = matcher;
        this.writer = writer;
    }

    @Transactional
    public LhHousingTypeHouseholdEnrichmentReport enrichAll() {
        Map<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> sourceGroups =
                sourceMapper.group(sourceRepository.findAllByOrderBySourceOrderAsc());
        List<HousingComplex> complexes = complexRepository.findAll();
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(sourceGroups.size());
        List<MatchedSource> matchedSources = new ArrayList<>();
        for (List<LhCatalogSource> sources : sourceGroups.values()) {
            Optional<MatchedSource> matchedSource = match(sources, complexes);
            if (matchedSource.isEmpty()) {
                report = report.plus(LhHousingTypeHouseholdEnrichmentReport.failed());
                continue;
            }
            matchedSources.add(matchedSource.get());
        }
        report = report.plus(writeUniqueMatches(matchedSources));
        logCompleted(report);
        return report;
    }

    private Optional<MatchedSource> match(
            List<LhCatalogSource> sources,
            List<HousingComplex> complexes
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
                return Optional.empty();
            }
            return Optional.of(new MatchedSource(matches.getFirst(), source));
        }
        catch (IllegalArgumentException exception) {
            LhCatalogSource first = sources.getFirst();
            log.warn(
                    "event=lh_household.map.failed result=skipped source=lh_catalog "
                            + "sourceOrder={} detail={}",
                    first.getSourceOrder(),
                    exception.getMessage()
            );
            return Optional.empty();
        }
    }

    private LhHousingTypeHouseholdEnrichmentReport writeUniqueMatches(
            List<MatchedSource> matchedSources
    ) {
        Map<Long, Integer> sourceGroupCounts = sourceGroupCounts(matchedSources);
        LhHousingTypeHouseholdEnrichmentReport report =
                LhHousingTypeHouseholdEnrichmentReport.empty(0);
        for (MatchedSource matchedSource : matchedSources) {
            int sourceGroupCount = sourceGroupCounts.get(matchedSource.complex().getId());
            if (sourceGroupCount > 1) {
                logDuplicateMatch(matchedSource, sourceGroupCount);
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
            HousingComplex complex,
            LhHousingTypeHouseholdSource source
    ) {
    }
}
