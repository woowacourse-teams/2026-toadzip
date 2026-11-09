package com.toadzip.backend.ingest.lh.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.pending.PendingCollectionTargets;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementSharedRequestCollector.BundleReport;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LH 공급·상세 원천을 최신 LH 공고목록의 임대 공고 기준으로 수집한다. 마이홈 공고 원천은 읽지 않는다.
 * 정기 수집은 종료 정책 안의 공고만, 강제 재조회는 종료 정책과 관계없이 지정한 LH 공고만 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LhAnnouncementExternalCollectionService {

    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementSharedRequestCollector sharedRequests;
    private final MeterRegistry meterRegistry;
    private final PendingCollectionTargets pending;

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        IngestExecutionScope.requireHeld();
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", targetSource.operation());
        List<Candidate> candidates = measurePreparation(
                targetSource, false, candidateResolver::resolveCatalogTargets
        );
        Map<String, Candidate> selected = new LinkedHashMap<>();
        candidates.forEach(candidate -> selected.put(candidate.sourceAnnouncementKey(), candidate));
        pending.rememberAll(targetSource, selected, 0);
        pending.find(targetSource).stream().map(target -> pending.read(target, Candidate.class))
                .forEach(candidate -> selected.putIfAbsent(candidate.sourceAnnouncementKey(), candidate));
        List<Candidate> remaining = List.copyOf(selected.values());
        ExternalDataCollectionReport report = collectCandidates(targetSource, remaining, false);
        log.info(
                "{} 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    /**
     * 공급·상세를 요청 묶음 단위로 받고, 묶음을 다 받을 때마다 listener가 그 묶음의 공고를 이어받는다. 호출 제한이나 실패로
     * 받지 못한 묶음은 대기 상태로 남아 다음 실행이 이어받는다.
     */
    public BundleReport collectBundles(Consumer<List<Candidate>> listener) {
        IngestExecutionScope.requireHeld();
        List<Candidate> candidates = measurePreparation(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, false,
                candidateResolver::resolveCatalogTargets);
        Map<String, Candidate> selected = new LinkedHashMap<>();
        candidates.forEach(candidate -> selected.put(candidate.sourceAnnouncementKey(), candidate));
        for (ExternalDataSource source : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            pending.rememberAll(source, selected, 0);
        }
        // 한쪽 원천만 대기 중인 요청도 같은 묶음에서 공급과 상세를 함께 받는다.
        for (ExternalDataSource source : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            pending.find(source).stream().map(target -> pending.read(target, Candidate.class))
                    .forEach(candidate -> selected.putIfAbsent(candidate.sourceAnnouncementKey(), candidate));
        }
        List<Candidate> remaining = List.copyOf(selected.values());
        if (remaining.isEmpty()) {
            return new BundleReport(ExternalDataCollectionReport.empty(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY.operation()),
                    ExternalDataCollectionReport.empty(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL.operation()));
        }
        Map<String, List<Candidate>> byRequest = remaining.stream().collect(
                Collectors.groupingBy(Candidate::requestDescription, LinkedHashMap::new, Collectors.toList()));
        IngestExecutionScope.verifyCanContinue();
        IngestExecutionScope.beginWork("LH 공고 수집·정제", "요청", byRequest.size());
        return sharedRequests.collectBundles(List.copyOf(byRequest.values()), listener).withSelectedPanIds(
                remaining.stream().map(Candidate::panId).collect(Collectors.toSet()));
    }

    /** 최신 공고목록에 있는 LH 공고(panId)의 요청을 종료 정책과 관계없이 다시 받는다. */
    public ExternalDataCollectionReport refresh(ExternalDataSource targetSource, String panId) {
        IngestExecutionScope.requireHeld();
        validateTargetSource(targetSource);
        if (panId == null || panId.isBlank()) {
            throw new InvalidIngestRequestException("LH 공고 ID는 필수입니다.");
        }
        String target = panId.strip();
        log.info("{} 강제 갱신을 시작합니다: panId={}", targetSource.operation(), target);
        List<Candidate> candidates = measurePreparation(targetSource, true,
                () -> candidateResolver.resolveCatalogRequests().stream()
                        .filter(candidate -> candidate.panId().equals(target))
                        .toList());
        if (candidates.isEmpty()) {
            throw new InvalidIngestRequestException("최신 LH 공고목록에서 공고를 찾을 수 없습니다: panId=" + target);
        }
        ExternalDataCollectionReport report = collectCandidates(targetSource, candidates, true);
        log.info(
                "{} 강제 갱신을 완료했습니다: panId={}, storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                target,
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    /** 공고목록에서 정한 수집 요청 하나를 다시 받는다. 공급 감소 보류를 승인한 뒤에 쓴다. */
    public ExternalDataCollectionReport refreshCatalogTarget(ExternalDataSource targetSource, String requestDescription) {
        IngestExecutionScope.requireHeld();
        validateTargetSource(targetSource);
        List<Candidate> candidates = candidateResolver.resolveCatalogRequests().stream()
                .filter(candidate -> candidate.requestDescription().equals(requestDescription))
                .toList();
        if (candidates.isEmpty()) {
            throw new InvalidIngestRequestException("최신 공고목록에 있는 LH 공고의 수집 요청이 아닙니다.");
        }
        return collectCandidates(targetSource, candidates, true);
    }

    private ExternalDataCollectionReport collectCandidates(
            ExternalDataSource targetSource,
            List<Candidate> candidates,
            boolean forceRefresh
    ) {
        if (candidates.isEmpty()) {
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        Map<String, List<Candidate>> candidatesByRequest = candidates.stream()
                .collect(Collectors.groupingBy(
                        Candidate::requestDescription, LinkedHashMap::new, Collectors.toList()
                ));
        IngestExecutionScope.verifyCanContinue();
        recordCount(targetSource, forceRefresh, "candidates", candidates.size());
        recordCount(targetSource, forceRefresh, "requests", candidatesByRequest.size());
        IngestExecutionScope.beginWork(targetSource.operation(), "요청", candidatesByRequest.size());
        return sharedRequests.collect(targetSource, List.copyOf(candidatesByRequest.values()));
    }

    private <T> T measurePreparation(ExternalDataSource targetSource, boolean forceRefresh, Supplier<T> action) {
        return meterRegistry.timer("ingest.announcement.prepare",
                        "source", targetSource.name(), "mode", collectionMode(forceRefresh), "phase", "catalog_read")
                .record(action);
    }

    private void recordCount(ExternalDataSource targetSource, boolean forceRefresh, String name, int count) {
        meterRegistry.counter("ingest.announcement." + name,
                        "source", targetSource.name(), "mode", collectionMode(forceRefresh), "result", "refresh")
                .increment(count);
    }

    private String collectionMode(boolean forceRefresh) {
        if (forceRefresh) {
            return "forced";
        }
        return "scheduled";
    }

    private void validateTargetSource(ExternalDataSource targetSource) {
        boolean supported = targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || targetSource == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }
}
