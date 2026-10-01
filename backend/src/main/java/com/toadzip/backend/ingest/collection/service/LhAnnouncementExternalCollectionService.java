package com.toadzip.backend.ingest.collection.service;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.LH_ANNOUNCEMENT_COLLECTION;

import com.toadzip.backend.ingest.collection.configuration.LhAnnouncementClientProperties;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BinaryOperator;
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
    private final IngestOperationLock executionLock;
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
                .tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> collectAnnouncements(targetSource))
                .orElseThrow(() -> alreadyRunning(targetSource));
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

    public ExternalDataCollectionReport refresh(ExternalDataSource targetSource, String pblancId) {
        validateTargetSource(targetSource);
        validatePblancId(pblancId);
        log.info("{} 강제 갱신을 시작합니다: pblancId={}", targetSource.operation(), pblancId);
        ExternalDataCollectionReport report = executionLock
                .tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> refreshAnnouncement(targetSource, pblancId.strip()))
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
        Set<String> handledAnnouncementKeys = new HashSet<>();
        Set<String> loadedAnnouncementKeys = new HashSet<>();
        long lastSeenId = 0L;
        while (true) {
            IngestExecutionScope.checkStopRequested();
            List<MyHomeAnnouncementSource> sources = findNextBatch(targetSource, lastSeenId);
            if (sources.isEmpty()) {
                return report;
            }
            lastSeenId = sources.getLast().getId();
            List<MyHomeAnnouncementSource> completeSources = completeSourceGroups(
                    targetSource, sources, loadedAnnouncementKeys
            );
            if (completeSources.isEmpty()) {
                continue;
            }
            ExternalDataCollectionReport batchReport = collectBatch(
                    targetSource,
                    completeSources,
                    handledAnnouncementKeys,
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

    private List<MyHomeAnnouncementSource> completeSourceGroups(
            ExternalDataSource targetSource,
            List<MyHomeAnnouncementSource> batch,
            Set<String> loadedAnnouncementKeys
    ) {
        List<MyHomeAnnouncementSource> unread = batch.stream()
                .filter(source -> loadedAnnouncementKeys.add(sourceGroupKey(source)))
                .toList();
        if (unread.isEmpty()) {
            return List.of();
        }
        List<String> identifiers = unread.stream()
                .map(MyHomeAnnouncementSource::getPblancId)
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .distinct()
                .toList();
        List<MyHomeAnnouncementSource> sources = new ArrayList<>(measurePreparation(
                targetSource, false, "source_group_read",
                () -> myHomeAnnouncementRepository.findAllByPblancIdInOrderByIdAsc(identifiers)
        ));
        unread.stream()
                .filter(source -> source.getPblancId() == null || source.getPblancId().isBlank())
                .forEach(sources::add);
        return sources;
    }

    private List<MyHomeAnnouncementSource> currentSources(List<MyHomeAnnouncementSource> sources) {
        return sources.stream()
                .collect(Collectors.groupingBy(this::sourceGroupKey, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .flatMap(group -> MyHomeAnnouncementCurrentSources.select(group).stream())
                .toList();
    }

    private String sourceGroupKey(MyHomeAnnouncementSource source) {
        if (source.getPblancId() == null || source.getPblancId().isBlank()) {
            return "source:" + source.getSourceKey();
        }
        return source.getPblancId();
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
            Set<String> handledAnnouncementKeys,
            boolean forceRefresh
    ) {
        List<MyHomeAnnouncementSource> current = measurePreparation(
                targetSource, forceRefresh, "current_source_selection", () -> currentSources(sources)
        );
        List<Resolution> resolutions = measurePreparation(targetSource, forceRefresh, "candidate_resolution",
                () -> candidateResolver.resolveAll(current));
        CandidateSelection selection = measurePreparation(targetSource, forceRefresh, "candidate_selection",
                () -> selectCandidates(current, resolutions, handledAnnouncementKeys, forceRefresh));
        recordSelection(targetSource, forceRefresh, selection, sources.size() - current.size());
        ExternalDataCollectionReport report = selectionReport(targetSource, selection);
        return report.plus(collectCandidates(
                targetSource, selection.candidates(), selection.refreshTtlByRequest(), forceRefresh
        ));
    }

    private void recordSelection(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            CandidateSelection selection,
            int historicalSourceCount
    ) {
        recordCount(targetSource, forceRefresh, "source.rows", "candidate", selection.candidates().size());
        recordCount(targetSource, forceRefresh, "source.rows", "unsupported", selection.skipped().size());
        recordCount(targetSource, forceRefresh, "source.rows", "policy_excluded", selection.policyExcludedCount());
        recordCount(targetSource, forceRefresh, "source.rows", "duplicate", selection.duplicateCount());
        recordCount(targetSource, forceRefresh, "source.rows", "historical", historicalSourceCount);
        recordCount(targetSource, forceRefresh, "candidates", "conflicting", selection.conflicts().size());
    }

    private ExternalDataCollectionReport selectionReport(
            ExternalDataSource targetSource,
            CandidateSelection selection
    ) {
        failureRecorder.skipAll(targetSource,
                selection.sourceKeysWithoutEligibleCandidates().stream()
                        .map(this::sourceSelectionDescription).toList(),
                "현재 마이홈 공고 원천이 모두 LH 수집 대상에서 제외되어 충돌 재처리를 건너뜁니다.");
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        for (Candidate conflict : selection.conflicts()) {
            report = report.plus(conflictReport(targetSource, conflict));
        }
        for (Skipped skipped : selection.skipped()) {
            report = report.plus(skipReport(targetSource, skipped.sourceDescription(), skipped.reason()));
        }
        return report;
    }

    private CandidateSelection selectCandidates(
            List<MyHomeAnnouncementSource> sources,
            List<Resolution> resolutions,
            Set<String> handledAnnouncementKeys,
            boolean forceRefresh
    ) {
        Set<String> conflictingKeys = conflictingSourceKeys(resolutions);
        Set<String> sourceKeysWithoutEligibleCandidates = resolutions.stream()
                .map(Resolution::sourceAnnouncementKey)
                .collect(Collectors.toSet());
        List<Skipped> skippedSources = new ArrayList<>();
        List<Candidate> conflicts = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Duration> refreshTtlByRequest = new HashMap<>();
        int policyExcludedCount = 0;
        int duplicateCount = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            Resolution resolution = resolutions.get(sourceIndex);
            if (resolution instanceof Skipped skipped) {
                skippedSources.add(skipped);
                continue;
            }
            Candidate candidate = (Candidate) resolution;
            Optional<Duration> refreshTtl = Optional.empty();
            if (!forceRefresh) {
                refreshTtl = refreshPolicy.scheduledRefreshTtl(sources.get(sourceIndex), candidate);
            }
            if (!forceRefresh && refreshTtl.isEmpty()) {
                policyExcludedCount++;
                continue;
            }
            sourceKeysWithoutEligibleCandidates.remove(candidate.sourceAnnouncementKey());
            if (conflictingKeys.contains(candidate.sourceAnnouncementKey())) {
                addConflict(candidate, handledAnnouncementKeys, conflicts);
                continue;
            }
            if (!forceRefresh) {
                refreshTtlByRequest.merge(
                        candidate.requestDescription(),
                        refreshTtl.orElseThrow(),
                        BinaryOperator.minBy(Duration::compareTo)
                );
            }
            if (!handledAnnouncementKeys.add(candidate.sourceAnnouncementKey())) {
                duplicateCount++;
                continue;
            }
            candidates.add(candidate);
        }
        return new CandidateSelection(
                candidates, refreshTtlByRequest, skippedSources, conflicts, sourceKeysWithoutEligibleCandidates,
                policyExcludedCount, duplicateCount
        );
    }

    private Set<String> conflictingSourceKeys(List<Resolution> resolutions) {
        Map<String, Set<String>> requestsBySource = new HashMap<>();
        for (Resolution resolution : resolutions) {
            if (!(resolution instanceof Candidate candidate)) {
                continue;
            }
            requestsBySource.computeIfAbsent(candidate.sourceAnnouncementKey(), ignored -> new HashSet<>())
                    .add(candidate.requestDescription());
        }
        return requestsBySource.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private void addConflict(Candidate candidate, Set<String> visited, List<Candidate> conflicts) {
        if (visited.add(candidate.sourceAnnouncementKey())) {
            conflicts.add(candidate);
        }
    }

    private ExternalDataCollectionReport conflictReport(ExternalDataSource targetSource, Candidate conflict) {
        failureRecorder.record(targetSource, sourceSelectionDescription(conflict.sourceAnnouncementKey()),
                new IllegalStateException("현재 마이홈 공고 원천의 LH 요청이 서로 다릅니다: pblancId="
                        + conflict.sourceAnnouncementKey()), log, "LH 현재 원천 선택 실패");
        return new ExternalDataCollectionReport(targetSource.operation(), 0, 1, 0, 0, 0, 0, 1);
    }

    private String sourceSelectionDescription(String sourceAnnouncementKey) {
        return "myhomeAnnouncementCurrentSource=" + sourceAnnouncementKey;
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
        Set<String> pendingConflicts = measurePreparation(targetSource, forceRefresh, "failure_read",
                () -> failureRecorder.findPendingRequestDescriptions(targetSource, candidates.stream()
                        .map(candidate -> sourceSelectionDescription(candidate.sourceAnnouncementKey())).toList()));
        // 배치의 판정 결과다. 이후 수집이 중단되면 실제 호출 수는 더 적을 수 있다.
        int pendingRequestCount = recordRequestDecisions(targetSource, forceRefresh, requests, progress);
        IngestExecutionScope.beginWork(
                targetSource.operation() + " · 현재 묶음 (최대 500개 원천 행)", "요청", pendingRequestCount
        );
        return collectRequests(
                targetSource,
                requests,
                progress,
                pendingConflicts
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

    private int recordRequestDecisions(
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
            if (progress.isFresh(request.getFirst().requestDescription())) {
                freshCandidateCount += request.size();
                freshRequestCount++;
            }
        }
        recordCount(targetSource, forceRefresh, "candidates", "ttl_fresh", freshCandidateCount);
        recordCount(targetSource, forceRefresh, "candidates", "refresh", candidateCount - freshCandidateCount);
        recordCount(targetSource, forceRefresh, "requests", "ttl_fresh", freshRequestCount);
        int pendingRequestCount = requests.size() - freshRequestCount;
        recordCount(targetSource, forceRefresh, "requests", "refresh", pendingRequestCount);
        return pendingRequestCount;
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
            List<Candidate> conflicts,
            Set<String> sourceKeysWithoutEligibleCandidates,
            int policyExcludedCount,
            int duplicateCount
    ) {
    }

    private ExternalDataCollectionReport collectRequests(
            ExternalDataSource targetSource,
            List<List<Candidate>> requests,
            BatchProgress progress,
            Set<String> pendingConflicts
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        try (ExecutorService executor = Executors.newFixedThreadPool(clientProperties.maxConcurrentRequests())) {
            CompletionService<ExternalDataCollectionReport> completedRequests =
                    new ExecutorCompletionService<>(executor);
            List<CollectionRequest> pending = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                pending.add(new CollectionRequest(index, requests.get(index)));
            }
            Map<Future<ExternalDataCollectionReport>, CollectionRequest> running = new HashMap<>();
            Map<Integer, Throwable> failures = new TreeMap<>();
            boolean stopScheduling = false;
            while (!pending.isEmpty() || !running.isEmpty()) {
                while (!stopScheduling && running.size() < clientProperties.maxConcurrentRequests()) {
                    CollectionRequest next = takeNextEligible(pending, running.values());
                    if (next == null) {
                        break;
                    }
                    Future<ExternalDataCollectionReport> result = completedRequests.submit(
                            collectionTask(targetSource, next.candidates(), progress, pendingConflicts)
                    );
                    running.put(result, next);
                }
                if (running.isEmpty()) {
                    break;
                }
                Future<ExternalDataCollectionReport> completed = completedRequests.take();
                CollectionRequest finished = running.remove(completed);
                try {
                    report = report.plus(completed.get());
                    if (!progress.isFresh(finished.description())) {
                        IngestExecutionScope.workCompleted();
                    }
                    stopScheduling |= report.rateLimitedRequestCount() > 0;
                }
                catch (ExecutionException exception) {
                    failures.put(finished.index(), exception.getCause());
                    stopScheduling = true;
                }
            }
            throwCollectedFailures(failures);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LH 공고 수집 대기가 중단되었습니다.", exception);
        }
        return report;
    }

    private void throwCollectedFailures(Map<Integer, Throwable> failures) {
        Throwable failure = null;
        for (Throwable additionalFailure : failures.values()) {
            if (failure == null) {
                failure = additionalFailure;
                continue;
            }
            if (failure != additionalFailure) {
                failure.addSuppressed(additionalFailure);
            }
        }
        if (failure != null) {
            throw propagate(failure);
        }
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

    private CollectionRequest takeNextEligible(
            List<CollectionRequest> pending,
            Collection<CollectionRequest> running
    ) {
        Iterator<CollectionRequest> iterator = pending.iterator();
        while (iterator.hasNext()) {
            CollectionRequest next = iterator.next();
            if (running.stream().anyMatch(request -> request.panId().equals(next.panId()))) {
                continue;
            }
            iterator.remove();
            return next;
        }
        return null;
    }

    private record CollectionRequest(int index, List<Candidate> candidates) {

        String panId() {
            return candidates.getFirst().panId();
        }

        String description() {
            return candidates.getFirst().requestDescription();
        }
    }

    private Callable<ExternalDataCollectionReport> collectionTask(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates,
            BatchProgress progress,
            Set<String> pendingConflicts
    ) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return IngestExecutionScope.propagate(() -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                return collectSharedRequest(targetSource, requestCandidates, progress, pendingConflicts);
            }
            finally {
                MDC.clear();
            }
        });
    }

    private ExternalDataCollectionReport collectSharedRequest(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates,
            BatchProgress progress,
            Set<String> pendingConflicts
    ) {
        Candidate representativeCandidate = requestCandidates.getFirst();
        boolean requestFresh = progress.isFresh(representativeCandidate.requestDescription());
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        if (requestFresh) {
            linkIfNeeded(targetSource, representativeCandidate, progress);
        }
        if (!requestFresh) {
            report = candidateCollector.collect(targetSource, representativeCandidate);
        }
        if (report.failedRequestCount() > 0) {
            return report;
        }
        for (Candidate linkedCandidate : requestCandidates.subList(1, requestCandidates.size())) {
            linkIfNeeded(targetSource, linkedCandidate, progress);
        }
        for (Candidate linkedCandidate : requestCandidates) {
            String conflictRequest = sourceSelectionDescription(linkedCandidate.sourceAnnouncementKey());
            if (pendingConflicts.contains(conflictRequest)) {
                failureRecorder.resolve(targetSource, conflictRequest);
            }
        }
        return report;
    }

    private void linkIfNeeded(ExternalDataSource targetSource, Candidate candidate, BatchProgress progress) {
        if (!progress.isLinkedTo(candidate.sourceAnnouncementKey(), candidate.requestDescription())) {
            progressManager.link(targetSource, candidate);
        }
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
