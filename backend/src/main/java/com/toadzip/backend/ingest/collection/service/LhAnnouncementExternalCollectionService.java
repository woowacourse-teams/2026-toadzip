package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhAnnouncementExternalCollectionService {

    private static final int ANNOUNCEMENT_BATCH_SIZE = 500;
    private static final int MAX_CONCURRENT_REQUESTS = 2;

    private final MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;
    private final LhAnnouncementCollectionExecutionLock executionLock;
    private final LhAnnouncementCollectionProgressManager progressManager;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCandidateCollector candidateCollector;
    private final LhAnnouncementRefreshPolicy refreshPolicy;

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", targetSource.operation());
        ExternalDataCollectionReport report = executionLock
                .tryRun(targetSource, () -> collectAnnouncements(targetSource))
                .orElseThrow(() -> alreadyRunning(targetSource));
        log.info(
                "{} 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    public ExternalDataCollectionReport refresh(ExternalDataSource targetSource, String pblancId) {
        validateTargetSource(targetSource);
        validatePblancId(pblancId);
        log.info("{} 강제 갱신을 시작합니다: pblancId={}", targetSource.operation(), pblancId);
        ExternalDataCollectionReport report = executionLock
                .tryRun(targetSource, () -> refreshAnnouncement(targetSource, pblancId.strip()))
                .orElseThrow(() -> alreadyRunning(targetSource));
        log.info(
                "{} 강제 갱신을 완료했습니다: pblancId={}, storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                pblancId,
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectAnnouncements(ExternalDataSource targetSource) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        Set<String> visitedSourceAnnouncements = new HashSet<>();
        long lastSeenId = 0L;
        while (true) {
            List<MyHomeAnnouncementSource> sources = findNextBatch(lastSeenId);
            if (sources.isEmpty()) {
                return report;
            }
            lastSeenId = sources.getLast().getId();
            ExternalDataCollectionReport batchReport = collectBatch(
                    targetSource,
                    sources,
                    visitedSourceAnnouncements,
                    false
            );
            report = report.plus(batchReport);
            if (batchReport.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
    }

    private ExternalDataCollectionReport refreshAnnouncement(
            ExternalDataSource targetSource,
            String pblancId
    ) {
        List<MyHomeAnnouncementSource> sources = myHomeAnnouncementRepository
                .findAllByPblancIdOrderByIdAsc(pblancId);
        if (sources.isEmpty()) {
            throw new InvalidIngestRequestException("마이홈 공고 원천을 찾을 수 없습니다: pblancId=" + pblancId);
        }
        return collectBatch(targetSource, sources, new HashSet<>(), true);
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
            boolean forceRefresh
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Duration> refreshTtlByRequest = new HashMap<>();
        for (MyHomeAnnouncementSource source : sources) {
            Resolution resolution = candidateResolver.resolve(source);
            if (visitedSourceAnnouncements.contains(resolution.sourceAnnouncementKey())) {
                continue;
            }
            if (resolution instanceof Skipped skipped) {
                report = report.plus(skipReport(
                        targetSource,
                        skipped.sourceDescription(),
                        skipped.reason()
                ));
                continue;
            }
            Candidate candidate = (Candidate) resolution;
            if (!forceRefresh) {
                var refreshTtl = refreshPolicy.scheduledRefreshTtl(source);
                if (refreshTtl.isEmpty()) {
                    continue;
                }
                refreshTtlByRequest.merge(
                        candidate.requestDescription(),
                        refreshTtl.orElseThrow(),
                        (left, right) -> left.compareTo(right) <= 0 ? left : right
                );
            }
            if (!visitedSourceAnnouncements.add(candidate.sourceAnnouncementKey())) {
                continue;
            }
            candidates.add(candidate);
        }
        return report.plus(collectCandidates(
                targetSource,
                candidates,
                refreshTtlByRequest,
                forceRefresh
        ));
    }

    private ExternalDataCollectionReport collectCandidates(
            ExternalDataSource targetSource,
            List<Candidate> candidates,
            Map<String, Duration> refreshTtlByRequest,
            boolean forceRefresh
    ) {
        if (candidates.isEmpty()) {
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        Map<String, List<Candidate>> candidatesByRequest = candidates.stream()
                .collect(Collectors.groupingBy(
                        Candidate::requestDescription,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<List<Candidate>> requests = new ArrayList<>(candidatesByRequest.values());
        BatchProgress progress = forceRefresh
                ? BatchProgress.empty()
                : findProgress(targetSource, requests, refreshTtlByRequest);
        return collectRequests(
                targetSource,
                requests,
                progress,
                forceRefresh
        );
    }

    private BatchProgress findProgress(
            ExternalDataSource targetSource,
            List<List<Candidate>> requests,
            Map<String, Duration> refreshTtlByRequest
    ) {
        Map<Duration, List<Candidate>> candidatesByRefreshTtl = new LinkedHashMap<>();
        for (List<Candidate> requestCandidates : requests) {
            Duration refreshTtl = refreshTtlByRequest.get(
                    requestCandidates.getFirst().requestDescription()
            );
            candidatesByRefreshTtl.computeIfAbsent(refreshTtl, ignored -> new ArrayList<>())
                    .addAll(requestCandidates);
        }
        BatchProgress progress = BatchProgress.empty();
        for (Map.Entry<Duration, List<Candidate>> entry : candidatesByRefreshTtl.entrySet()) {
            progress = progress.plus(progressManager.findBatch(
                    targetSource,
                    entry.getValue(),
                    entry.getKey()
            ));
        }
        return progress;
    }

    private ExternalDataCollectionReport collectRequests(
            ExternalDataSource targetSource,
            List<List<Candidate>> requests,
            BatchProgress progress,
            boolean forceRefresh
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS)) {
            int nextRequest = 0;
            while (nextRequest < requests.size()) {
                List<List<Candidate>> window = nextWindow(requests, nextRequest);
                List<Callable<ExternalDataCollectionReport>> tasks = window.stream()
                        .map(request -> collectionTask(targetSource, request, progress, forceRefresh))
                        .toList();
                nextRequest += window.size();
                Throwable failure = null;
                for (Future<ExternalDataCollectionReport> result : executor.invokeAll(tasks)) {
                    try {
                        report = report.plus(result.get());
                    }
                    catch (ExecutionException exception) {
                        failure = appendFailure(failure, exception.getCause());
                    }
                }
                if (failure != null) {
                    throw propagate(failure);
                }
                if (report.rateLimitedRequestCount() > 0) {
                    return report;
                }
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LH 공고 수집 대기가 중단되었습니다.", exception);
        }
        return report;
    }

    private Throwable appendFailure(Throwable failure, Throwable additionalFailure) {
        if (failure == null) {
            return additionalFailure;
        }
        if (failure != additionalFailure) {
            failure.addSuppressed(additionalFailure);
        }
        return failure;
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("LH 공고 수집 작업이 실패했습니다.", failure);
    }

    private List<List<Candidate>> nextWindow(List<List<Candidate>> requests, int offset) {
        List<List<Candidate>> window = new ArrayList<>();
        Set<String> panIds = new HashSet<>();
        for (int index = offset; index < requests.size() && window.size() < MAX_CONCURRENT_REQUESTS; index++) {
            List<Candidate> request = requests.get(index);
            // 기존의 같은 panId 요청 순서를 유지한다. 원천은 요청 해시별로 저장된다.
            if (!panIds.add(request.getFirst().panId())) {
                break;
            }
            window.add(request);
        }
        return window;
    }

    private Callable<ExternalDataCollectionReport> collectionTask(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates,
            BatchProgress progress,
            boolean forceRefresh
    ) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                return collectRequest(targetSource, requestCandidates, progress, forceRefresh);
            }
            finally {
                MDC.clear();
            }
        };
    }

    private ExternalDataCollectionReport collectRequest(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates,
            BatchProgress progress,
            boolean forceRefresh
    ) {
        ExternalDataCollectionReport report = collectCandidate(
                targetSource,
                requestCandidates.getFirst(),
                progress,
                forceRefresh
        );
        if (report.failedRequestCount() > 0) {
            return report;
        }
        for (Candidate linkedCandidate : requestCandidates.subList(1, requestCandidates.size())) {
            if (!progress.isLinkedTo(linkedCandidate.sourceAnnouncementKey(), linkedCandidate.requestDescription())) {
                progressManager.link(targetSource, linkedCandidate);
            }
        }
        return report;
    }

    private ExternalDataCollectionReport collectCandidate(
            ExternalDataSource targetSource,
            Candidate candidate,
            BatchProgress progress,
            boolean forceRefresh
    ) {
        if (!forceRefresh && progress.isFresh(candidate.requestDescription())) {
            if (!progress.isLinkedTo(candidate.sourceAnnouncementKey(), candidate.requestDescription())) {
                progressManager.link(targetSource, candidate);
            }
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        return candidateCollector.collect(targetSource, candidate);
    }

    private ExternalDataCollectionReport skipReport(
            ExternalDataSource targetSource,
            String requestDescription,
            String skipReason
    ) {
        failureRecorder.skip(targetSource, requestDescription, skipReason);
        return new ExternalDataCollectionReport(targetSource.operation(), 0, 0, 0, 1);
    }

    private void validateTargetSource(ExternalDataSource targetSource) {
        boolean supported = targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || targetSource == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private void validatePblancId(String pblancId) {
        if (pblancId == null || pblancId.isBlank()) {
            throw new InvalidIngestRequestException("공고 식별자는 필수입니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning(ExternalDataSource targetSource) {
        log.warn("{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.", targetSource.operation());
        return new IngestAlreadyRunningException(targetSource.operation() + " 수집이 이미 실행 중입니다.");
    }

}
