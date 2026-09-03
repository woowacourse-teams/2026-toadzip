package com.toadzip.backend.ingest.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;

@Slf4j
@Service
public class LhAnnouncementExternalCollectionService {

    private static final int ANNOUNCEMENT_BATCH_SIZE = 500;

    private final MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;
    private final LhAnnouncementExternalRepository externalRepository;
    private final LhAnnouncementCollectionExecutionLock executionLock;
    private final LhSourceStore sourceStore;
    private final LhAnnouncementCollectionProgressStore progressStore;
    private final LhAnnouncementSourceMapper sourceMapper;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver;
    private final ExternalDataRetryExecutor retryExecutor;

    public LhAnnouncementExternalCollectionService(
            MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository,
            LhAnnouncementExternalRepository externalRepository,
            LhAnnouncementCollectionExecutionLock executionLock,
            LhSourceStore sourceStore,
            LhAnnouncementCollectionProgressStore progressStore,
            LhAnnouncementSourceMapper sourceMapper,
            ExternalDataFailureRecorder failureRecorder,
            LhSupplyInfoTypeCodeResolver supplyTypeCodeResolver,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.myHomeAnnouncementRepository = myHomeAnnouncementRepository;
        this.externalRepository = externalRepository;
        this.executionLock = executionLock;
        this.sourceStore = sourceStore;
        this.progressStore = progressStore;
        this.sourceMapper = sourceMapper;
        this.failureRecorder = failureRecorder;
        this.supplyTypeCodeResolver = supplyTypeCodeResolver;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", operation(targetSource));
        ExternalDataCollectionReport report = executionLock
                .tryRun(targetSource, () -> collectAnnouncements(targetSource))
                .orElseThrow(() -> alreadyRunning(targetSource));
        log.info(
                "{} 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, externalApiCallCount={}, skippedRequestCount={}",
                operation(targetSource),
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectAnnouncements(ExternalDataSource targetSource) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(operation(targetSource));
        Set<String> visitedSourceAnnouncements = new HashSet<>();
        Set<String> attemptedRequests = new HashSet<>();
        long lastSeenId = 0L;
        while (true) {
            List<MyHomeAnnouncementSource> sources = findNextBatch(lastSeenId);
            if (sources.isEmpty()) {
                return report;
            }
            lastSeenId = sources.getLast().getId();
            report = report.plus(collectBatch(
                    targetSource,
                    sources,
                    visitedSourceAnnouncements,
                    attemptedRequests
            ));
        }
    }

    private List<MyHomeAnnouncementSource> findNextBatch(long lastSeenId) {
        return myHomeAnnouncementRepository.findByIdGreaterThanOrderByIdAsc(
                lastSeenId,
                PageRequest.of(0, ANNOUNCEMENT_BATCH_SIZE)
        );
    }

    private ExternalDataCollectionReport collectBatch(
            ExternalDataSource targetSource,
            List<MyHomeAnnouncementSource> sources,
            Set<String> visitedSourceAnnouncements,
            Set<String> attemptedRequests
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(operation(targetSource));
        List<CollectionCandidate> candidates = new ArrayList<>();
        for (MyHomeAnnouncementSource source : sources) {
            String sourceAnnouncementKey = sourceAnnouncementKey(source);
            if (!visitedSourceAnnouncements.add(sourceAnnouncementKey)) {
                continue;
            }
            String sourceDescription = sourceDescription(source);
            if (!isLhProvider(source)) {
                report = report.plus(skipReport(
                        targetSource,
                        sourceDescription,
                        "LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다."
                ));
                continue;
            }
            Optional<LhAnnouncementRequest> request = requestOf(source);
            if (request.isEmpty()) {
                report = report.plus(skipReport(
                        targetSource,
                        sourceDescription,
                        "LH 공고 조회 조건을 지원하지 않아 건너뛰었습니다."
                ));
                continue;
            }
            LhAnnouncementRequest resolved = request.orElseThrow();
            if (!attemptedRequests.add(resolved.requestDescription())) {
                continue;
            }
            candidates.add(new CollectionCandidate(sourceAnnouncementKey, sourceDescription, resolved));
        }
        return report.plus(collectCandidates(targetSource, candidates));
    }

    private ExternalDataCollectionReport collectCandidates(
            ExternalDataSource targetSource,
            List<CollectionCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return ExternalDataCollectionReport.empty(operation(targetSource));
        }
        BatchProgress progress = progressStore.findBatch(
                targetSource,
                candidates.stream().map(CollectionCandidate::requestDescription).toList(),
                candidates.stream().map(CollectionCandidate::panId).toList()
        );
        Set<String> storedPanIds = new HashSet<>(progress.storedPanIds());
        Set<String> historyPanIds = new HashSet<>(progress.historyPanIds());
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(operation(targetSource));
        for (CollectionCandidate candidate : candidates) {
            report = report.plus(collectCandidate(
                    targetSource,
                    candidate,
                    progress,
                    storedPanIds,
                    historyPanIds
            ));
        }
        return report;
    }

    private ExternalDataCollectionReport collectCandidate(
            ExternalDataSource targetSource,
            CollectionCandidate candidate,
            BatchProgress progress,
            Set<String> storedPanIds,
            Set<String> historyPanIds
    ) {
        if (progress.isCompleted(candidate.requestDescription())) {
            return ExternalDataCollectionReport.empty(operation(targetSource));
        }
        if (storedPanIds.contains(candidate.panId()) && !historyPanIds.contains(candidate.panId())) {
            complete(targetSource, candidate);
            historyPanIds.add(candidate.panId());
            return ExternalDataCollectionReport.empty(operation(targetSource));
        }
        ExternalDataCollectionReport report = fetchAndStore(
                targetSource,
                candidate.sourceAnnouncementKey(),
                candidate.sourceDescription(),
                candidate.request()
        );
        if (report.failedRequestCount() == 0) {
            storedPanIds.add(candidate.panId());
            historyPanIds.add(candidate.panId());
        }
        return report;
    }

    private void complete(ExternalDataSource targetSource, CollectionCandidate candidate) {
        resolveFailures(targetSource, candidate.requestDescription(), candidate.sourceDescription());
        progressStore.complete(
                targetSource,
                candidate.sourceAnnouncementKey(),
                candidate.requestDescription(),
                candidate.panId()
        );
    }

    private ExternalDataCollectionReport fetchAndStore(
            ExternalDataSource targetSource,
            String sourceAnnouncementKey,
            String sourceDescription,
            LhAnnouncementRequest request
    ) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        ExternalDataResponse response;
        try {
            response = retryExecutor.execute(
                    targetSource,
                    request.requestDescription(),
                    () -> fetch(targetSource, request),
                    callCounter
            );
        }
        catch (ExternalDataCallFailureException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        int storedRowCount;
        try {
            storedRowCount = store(targetSource, request.panId(), response);
        }
        catch (ExternalDataRequestException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        resolveFailures(targetSource, request.requestDescription(), sourceDescription);
        progressStore.complete(
                targetSource,
                sourceAnnouncementKey,
                request.requestDescription(),
                request.panId()
        );
        return new ExternalDataCollectionReport(operation(targetSource), storedRowCount, 0, callCounter.count());
    }

    private ExternalDataCollectionReport failedReport(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            RuntimeException exception,
            ExternalDataCallCounter callCounter
    ) {
        failureRecorder.record(
                targetSource,
                request.requestDescription(),
                exception,
                log,
                "LH 외부 API 수집에 실패했습니다"
        );
        return new ExternalDataCollectionReport(
                operation(targetSource),
                0,
                1,
                callCounter.count(),
                0,
                ExternalDataRateLimit.count(exception)
        );
    }

    private int store(ExternalDataSource targetSource, String panId, ExternalDataResponse response) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            List<LhAnnouncementDetailSource> sources = sourceMapper.details(panId, response.body());
            return sourceStore.replaceDetails(panId, sources);
        }
        List<LhAnnouncementSupplySource> sources = sourceMapper.supplies(panId, response.body());
        return sourceStore.replaceSupplies(panId, sources);
    }

    private ExternalDataCollectionReport skipReport(
            ExternalDataSource targetSource,
            String requestDescription,
            String skipReason
    ) {
        failureRecorder.skip(targetSource, requestDescription, skipReason);
        return new ExternalDataCollectionReport(operation(targetSource), 0, 0, 0, 1);
    }

    private void resolveFailures(
            ExternalDataSource targetSource,
            String requestDescription,
            String sourceDescription
    ) {
        failureRecorder.resolve(targetSource, requestDescription);
        if (!requestDescription.equals(sourceDescription)) {
            failureRecorder.resolve(targetSource, sourceDescription);
        }
    }

    private boolean isLhProvider(MyHomeAnnouncementSource source) {
        String provider = source.getSuplyInsttNm();
        if (provider == null) {
            return false;
        }
        String normalized = provider.strip();
        return "LH".equalsIgnoreCase(normalized) || "한국토지주택공사".equals(normalized);
    }

    private Optional<LhAnnouncementRequest> requestOf(MyHomeAnnouncementSource source) {
        Optional<String> supplyTypeCode = supplyTypeCodeResolver.resolve(source.getSuplyTyNm());
        if (supplyTypeCode.isEmpty()) {
            return Optional.empty();
        }
        String url = source.getUrl();
        if (url == null || url.isBlank()) {
            url = source.getPcUrl();
        }
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            return LhAnnouncementRequest.from(URI.create(url), supplyTypeCode.orElseThrow());
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private ExternalDataResponse fetch(ExternalDataSource targetSource, LhAnnouncementRequest request) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return externalRepository.fetchDetail(request);
        }
        return externalRepository.fetchSupply(request);
    }

    private String sourceDescription(MyHomeAnnouncementSource source) {
        if (source.getId() == null) {
            return source.getSourceKey();
        }
        return "myhomeAnnouncementSourceId=" + source.getId();
    }

    private String sourceAnnouncementKey(MyHomeAnnouncementSource source) {
        String pblancId = source.getPblancId();
        if (pblancId == null || pblancId.isBlank()) {
            return "source:" + source.getSourceKey();
        }
        return pblancId;
    }

    private String operation(ExternalDataSource targetSource) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            return "lh-announcement-detail";
        }
        return "lh-announcement-supply";
    }

    private void validateTargetSource(ExternalDataSource targetSource) {
        boolean supported = targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || targetSource == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning(ExternalDataSource targetSource) {
        log.warn("{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.", operation(targetSource));
        return new IngestAlreadyRunningException(operation(targetSource) + " 수집이 이미 실행 중입니다.");
    }

    private record CollectionCandidate(
            String sourceAnnouncementKey,
            String sourceDescription,
            LhAnnouncementRequest request
    ) {

        private String requestDescription() {
            return request.requestDescription();
        }

        private String panId() {
            return request.panId();
        }
    }
}
