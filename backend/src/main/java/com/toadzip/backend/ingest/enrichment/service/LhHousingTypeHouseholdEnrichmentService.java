package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdEnrichmentFailureResponse;
import com.toadzip.backend.ingest.enrichment.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.enrichment.repository.LhHouseholdEnrichmentFailureStore;
import com.toadzip.backend.ingest.enrichment.service.LhHouseholdComplexMatches.CurrentMatches;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** LH 임대단지 카탈로그로 정제 단지의 주택형 세대수를 보강하고, 이번 실행의 실패로 실패 검토 목록을 맞춘다. 그 실패 목록도 조회한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class LhHousingTypeHouseholdEnrichmentService {

    private final LhHouseholdComplexMatches matches;
    private final LhHouseholdComplexWriter writer;
    private final LhHouseholdEnrichmentFailureStore failureStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<LhHouseholdEnrichmentFailureResponse> findFailures(int page, int size) {
        return failureStore.pending(page, size)
                .stream()
                .map(LhHouseholdEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LhHouseholdEnrichmentFailureResponse> findFailureHistory(int page, int size) {
        return failureStore.history(page, size)
                .stream()
                .map(LhHouseholdEnrichmentFailureResponse::from)
                .toList();
    }

    @Transactional
    public LhHousingTypeHouseholdEnrichmentReport enrichAll() {
        IngestExecutionScope.requireHeld();
        List<LhHouseholdEnrichmentFailure> failures = new ArrayList<>();
        Instant occurredAt = clock.instant();
        CurrentMatches current = matches.current(failures, occurredAt);
        LhHousingTypeHouseholdEnrichmentReport report = current.report()
                .plus(writer.write(current.matchedSources(), failures, occurredAt));
        failureStore.reconcileAfterRun(
                failures,
                IngestExecutionScope.currentExecutionId().orElse(null)
        );
        logCompleted(report);
        return report;
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
}
