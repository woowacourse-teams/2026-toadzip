package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.configuration.LhAnnouncementClientProperties;
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
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
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

    private final MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;
    private final LhAnnouncementCollectionExecutionLock executionLock;
    private final LhAnnouncementCollectionProgressManager progressManager;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCandidateCollector candidateCollector;
    private final LhAnnouncementRefreshPolicy refreshPolicy;
    private final LhAnnouncementClientProperties clientProperties;
    private final MeterRegistry meterRegistry;

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
            List<MyHomeAnnouncementSource> sources = findNextBatch(targetSource, lastSeenId);
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
        List<MyHomeAnnouncementSource> sources = readSources(targetSource, true,
                () -> myHomeAnnouncementRepository.findAllByPblancIdOrderByIdAsc(pblancId));
        if (sources.isEmpty()) {
            throw new InvalidIngestRequestException("마이홈 공고 원천을 찾을 수 없습니다: pblancId=" + pblancId);
        }
        return collectBatch(targetSource, sources, new HashSet<>(), true);
    }

    private List<MyHomeAnnouncementSource> findNextBatch(ExternalDataSource targetSource, long lastSeenId) {
        return readSources(targetSource, false,
                () -> myHomeAnnouncementRepository.findByIdGreaterThanOrderByIdAsc(
                        lastSeenId, PageRequest.of(0, ANNOUNCEMENT_BATCH_SIZE)
                ));
    }

    private List<MyHomeAnnouncementSource> readSources(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            Supplier<List<MyHomeAnnouncementSource>> query
    ) {
        List<MyHomeAnnouncementSource> sources = measurePreparation(targetSource, forceRefresh, "source_read", query);
        recordCount(targetSource, forceRefresh, "source.rows", "read", sources.size());
        return sources;
    }

    private ExternalDataCollectionReport collectBatch(
            ExternalDataSource targetSource,
            List<MyHomeAnnouncementSource> sources,
            Set<String> visitedSourceAnnouncements,
            boolean forceRefresh
    ) {
        List<Resolution> resolutions = measurePreparation(targetSource, forceRefresh, "candidate_resolution",
                () -> candidateResolver.resolveAll(sources));
        CandidateSelection selection = measurePreparation(targetSource, forceRefresh, "candidate_selection",
                () -> selectCandidates(sources, resolutions, visitedSourceAnnouncements, forceRefresh));
        recordCount(targetSource, forceRefresh, "source.rows", "candidate", selection.candidates().size());
        recordCount(targetSource, forceRefresh, "source.rows", "unsupported", selection.skipped().size());
        recordCount(targetSource, forceRefresh, "source.rows", "policy_excluded", selection.policyExcludedCount());
        recordCount(targetSource, forceRefresh, "source.rows", "duplicate", selection.duplicateCount());
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        for (Skipped skipped : selection.skipped()) {
            report = report.plus(skipReport(targetSource, skipped.sourceDescription(), skipped.reason()));
        }
        return report.plus(collectCandidates(
                targetSource, selection.candidates(), selection.refreshTtlByRequest(), forceRefresh
        ));
    }

    private CandidateSelection selectCandidates(
            List<MyHomeAnnouncementSource> sources,
            List<Resolution> resolutions,
            Set<String> visitedSourceAnnouncements,
            boolean forceRefresh
    ) {
        List<Candidate> candidates = new ArrayList<>();
        List<Skipped> skippedSources = new ArrayList<>();
        Map<String, Duration> refreshTtlByRequest = new HashMap<>();
        int policyExcludedCount = 0;
        int duplicateCount = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            MyHomeAnnouncementSource source = sources.get(sourceIndex);
            Resolution resolution = resolutions.get(sourceIndex);
            if (resolution instanceof Skipped skipped) {
                skippedSources.add(skipped);
                continue;
            }
            Candidate candidate = (Candidate) resolution;
            if (!forceRefresh) {
                var refreshTtl = refreshPolicy.scheduledRefreshTtl(source, candidate);
                if (refreshTtl.isEmpty()) {
                    policyExcludedCount++;
                    continue;
                }
                refreshTtlByRequest.merge(
                        candidate.requestDescription(),
                        refreshTtl.orElseThrow(),
                        (left, right) -> left.compareTo(right) <= 0 ? left : right
                );
            }
            if (!visitedSourceAnnouncements.add(candidate.sourceAnnouncementKey())) {
                duplicateCount++;
                continue;
            }
            candidates.add(candidate);
        }
        return new CandidateSelection(
                candidates, refreshTtlByRequest, skippedSources, policyExcludedCount, duplicateCount
        );
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
        BatchProgress progress = BatchProgress.empty();
        if (!forceRefresh) {
            progress = measurePreparation(targetSource, false, "checkpoint_read",
                    () -> findProgress(targetSource, requests, refreshTtlByRequest));
        }
        // 배치의 판정 결과다. 이후 수집이 중단되면 실제 호출 수는 더 적을 수 있다.
        recordDecisions(targetSource, forceRefresh, requests, progress);
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

    private void recordDecisions(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            List<List<Candidate>> requests,
            BatchProgress progress
    ) {
        int candidateCount = 0;
        int freshCandidateCount = 0;
        int freshRequestCount = 0;
        for (List<Candidate> request : requests) {
            candidateCount += request.size();
            if (!forceRefresh && progress.isFresh(request.getFirst().requestDescription())) {
                freshCandidateCount += request.size();
                freshRequestCount++;
            }
        }
        recordCount(targetSource, forceRefresh, "candidates", "ttl_fresh", freshCandidateCount);
        recordCount(targetSource, forceRefresh, "candidates", "refresh", candidateCount - freshCandidateCount);
        recordCount(targetSource, forceRefresh, "requests", "ttl_fresh", freshRequestCount);
        recordCount(targetSource, forceRefresh, "requests", "refresh", requests.size() - freshRequestCount);
    }

    private <T> T measurePreparation(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            String phase,
            Supplier<T> action
    ) {
        return meterRegistry.timer("ingest.announcement.prepare",
                        "source", targetSource.name(), "mode", collectionMode(forceRefresh), "phase", phase)
                .record(action);
    }

    private void recordCount(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            String name,
            String result,
            int count
    ) {
        meterRegistry.counter("ingest.announcement." + name,
                        "source", targetSource.name(), "mode", collectionMode(forceRefresh), "result", result)
                .increment(count);
    }

    private String collectionMode(boolean forceRefresh) {
        if (forceRefresh) {
            return "forced";
        }
        return "scheduled";
    }

    private record CandidateSelection(
            List<Candidate> candidates,
            Map<String, Duration> refreshTtlByRequest,
            List<Skipped> skipped,
            int policyExcludedCount,
            int duplicateCount
    ) {
    }

    private ExternalDataCollectionReport collectRequests(
            ExternalDataSource targetSource,
            List<List<Candidate>> requests,
            BatchProgress progress,
            boolean forceRefresh
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        try (ExecutorService executor = Executors.newFixedThreadPool(clientProperties.maxConcurrentRequests())) {
            CompletionService<ExternalDataCollectionReport> completedRequests =
                    new ExecutorCompletionService<>(executor);
            List<Integer> pending = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                pending.add(index);
            }
            Map<Future<ExternalDataCollectionReport>, Integer> running = new HashMap<>();
            Set<String> runningPanIds = new HashSet<>();
            Map<Integer, Throwable> failures = new TreeMap<>();
            boolean stopScheduling = false;
            while (!pending.isEmpty() || !running.isEmpty()) {
                while (!stopScheduling && running.size() < clientProperties.maxConcurrentRequests()) {
                    Integer index = takeNextEligible(pending, requests, runningPanIds);
                    if (index == null) {
                        break;
                    }
                    List<Candidate> request = requests.get(index);
                    Future<ExternalDataCollectionReport> result = completedRequests.submit(
                            collectionTask(targetSource, request, progress, forceRefresh)
                    );
                    running.put(result, index);
                    runningPanIds.add(request.getFirst().panId());
                }
                if (running.isEmpty()) {
                    break;
                }
                Future<ExternalDataCollectionReport> completed = completedRequests.take();
                int index = running.remove(completed);
                runningPanIds.remove(requests.get(index).getFirst().panId());
                try {
                    report = report.plus(completed.get());
                    stopScheduling |= report.rateLimitedRequestCount() > 0;
                }
                catch (ExecutionException exception) {
                    failures.put(index, exception.getCause());
                    stopScheduling = true;
                }
            }
            Throwable failure = null;
            for (Throwable additionalFailure : failures.values()) {
                failure = appendFailure(failure, additionalFailure);
            }
            if (failure != null) {
                throw propagate(failure);
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

    private Integer takeNextEligible(
            List<Integer> pending,
            List<List<Candidate>> requests,
            Set<String> runningPanIds
    ) {
        Iterator<Integer> iterator = pending.iterator();
        while (iterator.hasNext()) {
            int index = iterator.next();
            if (runningPanIds.contains(requests.get(index).getFirst().panId())) {
                continue;
            }
            iterator.remove();
            return index;
        }
        return null;
    }

    private Callable<ExternalDataCollectionReport> collectionTask(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates,
            BatchProgress progress,
            boolean forceRefresh
    ) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return IngestExecutionScope.propagate(() -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                return collectRequest(targetSource, requestCandidates, progress, forceRefresh);
            }
            finally {
                MDC.clear();
            }
        });
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
