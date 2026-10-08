package com.toadzip.backend.ingest.collection.lh.service;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.LH_ANNOUNCEMENT_COLLECTION;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.configuration.LhAnnouncementClientProperties;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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

    private final MyHomeAnnouncementSourceReader myHomeAnnouncementRepository;
    private final IngestOperationLock executionLock;
    private final LhAnnouncementCollectionProgressManager progressManager;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCandidateCollector candidateCollector;
    private final LhAnnouncementCollectionPolicy collectionPolicy;
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
        return refresh(targetSource, pblancId, null);
    }

    public ExternalDataCollectionReport refresh(
            ExternalDataSource targetSource, String pblancId, UUID sourceExecutionId
    ) {
        validateTargetSource(targetSource);
        validatePblancId(pblancId);
        log.info("{} 강제 갱신을 시작합니다: pblancId={}", targetSource.operation(), pblancId);
        ExternalDataCollectionReport report = executionLock
                .tryRun(LH_ANNOUNCEMENT_COLLECTION,
                        () -> refreshAnnouncement(targetSource, pblancId.strip(), sourceExecutionId))
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
        Map<String, Boolean> completedRequests = new HashMap<>();
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
                    completedRequests,
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
            String pblancId,
            UUID sourceExecutionId
    ) {
        List<MyHomeAnnouncementSource> sources = readSources(targetSource, true,
                () -> myHomeAnnouncementRepository.findAllByPblancIdOrderByIdAsc(pblancId).stream()
                        .filter(source -> sourceExecutionId == null
                                || sourceExecutionId.toString().equals(source.getLastSeenRunId())).toList());
        if (sources.isEmpty()) {
            throw new InvalidIngestRequestException("마이홈 공고 원천을 찾을 수 없습니다: pblancId=" + pblancId);
        }
        return collectBatch(targetSource, sources, new HashSet<>(), new HashMap<>(), true);
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
            Map<String, Boolean> completedRequests,
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
                targetSource, selection.candidates(), completedRequests, forceRefresh
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
        int policyExcludedCount = 0;
        int duplicateCount = 0;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            Resolution resolution = resolutions.get(sourceIndex);
            if (resolution instanceof Skipped skipped) {
                skippedSources.add(skipped);
                continue;
            }
            Candidate candidate = (Candidate) resolution;
            if (!forceRefresh && !collectionPolicy.isCollectionTarget(sources.get(sourceIndex))) {
                policyExcludedCount++;
                continue;
            }
            sourceKeysWithoutEligibleCandidates.remove(candidate.sourceAnnouncementKey());
            if (conflictingKeys.contains(candidate.sourceAnnouncementKey())) {
                addConflict(candidate, handledAnnouncementKeys, conflicts);
                continue;
            }
            if (!handledAnnouncementKeys.add(candidate.sourceAnnouncementKey())) {
                duplicateCount++;
                continue;
            }
            candidates.add(candidate);
        }
        return new CandidateSelection(
                candidates, skippedSources, conflicts, sourceKeysWithoutEligibleCandidates,
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
            Map<String, Boolean> completedRequests,
            boolean forceRefresh
    ) {
        if (candidates.isEmpty()) {
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        Map<String, List<Candidate>> candidatesByRequest = candidates.stream()
                .collect(Collectors.groupingBy(
                        Candidate::requestDescription, LinkedHashMap::new, Collectors.toList()
                ));
        Set<String> pendingConflicts = measurePreparation(targetSource, forceRefresh, "failure_read",
                () -> failureRecorder.findPendingRequestDescriptions(targetSource, candidates.stream()
                        .map(candidate -> sourceSelectionDescription(candidate.sourceAnnouncementKey())).toList()));
        List<List<Candidate>> pending = new ArrayList<>();
        for (List<Candidate> requestCandidates : candidatesByRequest.values()) {
            IngestExecutionScope.verifyHeld();
            IngestExecutionScope.checkStopRequested();
            Boolean successful = completedRequests.get(requestCandidates.getFirst().requestDescription());
            if (successful == null) {
                pending.add(requestCandidates);
                continue;
            }
            if (successful) {
                linkCandidates(targetSource, requestCandidates, pendingConflicts);
            }
        }
        int pendingCandidateCount = pending.stream().mapToInt(List::size).sum();
        recordCount(targetSource, forceRefresh, "candidates", "refresh", pendingCandidateCount);
        recordCount(targetSource, forceRefresh, "requests", "refresh", pending.size());
        recordCount(targetSource, forceRefresh, "requests", "execution_reused",
                candidatesByRequest.size() - pending.size());
        IngestExecutionScope.beginWork(
                targetSource.operation() + " · 현재 묶음 (최대 500개 원천 행)", "요청", pending.size()
        );
        return collectRequests(targetSource, pending, completedRequests, pendingConflicts);
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
            Map<String, Boolean> completedRequests,
            Set<String> pendingConflicts
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        try (ExecutorService executor = Executors.newFixedThreadPool(clientProperties.maxConcurrentRequests())) {
            CompletionService<ExternalDataCollectionReport> completions =
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
                    Future<ExternalDataCollectionReport> result = completions.submit(
                            collectionTask(targetSource, next.candidates(), pendingConflicts)
                    );
                    running.put(result, next);
                }
                if (running.isEmpty()) {
                    break;
                }
                Future<ExternalDataCollectionReport> completed = completions.take();
                CollectionRequest finished = running.remove(completed);
                try {
                    ExternalDataCollectionReport result = completed.get();
                    report = report.plus(result);
                    completedRequests.put(finished.description(), result.failedRequestCount() == 0);
                    IngestExecutionScope.workCompleted();
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
            Set<String> pendingConflicts
    ) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return IngestExecutionScope.propagate(() -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                return collectSharedRequest(targetSource, requestCandidates, pendingConflicts);
            }
            finally {
                MDC.clear();
            }
        });
    }

    private ExternalDataCollectionReport collectSharedRequest(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates,
            Set<String> pendingConflicts
    ) {
        ExternalDataCollectionReport report = candidateCollector.collect(targetSource, requestCandidates.getFirst());
        if (report.failedRequestCount() > 0) {
            return report;
        }
        linkCandidates(targetSource, requestCandidates.subList(1, requestCandidates.size()), pendingConflicts);
        resolveConflict(targetSource, requestCandidates.getFirst(), pendingConflicts);
        return report;
    }

    private void linkCandidates(
            ExternalDataSource targetSource, List<Candidate> candidates, Set<String> pendingConflicts
    ) {
        for (Candidate candidate : candidates) {
            progressManager.link(targetSource, candidate);
            resolveConflict(targetSource, candidate, pendingConflicts);
        }
    }

    private void resolveConflict(ExternalDataSource targetSource, Candidate candidate, Set<String> pendingConflicts) {
        String conflictRequest = sourceSelectionDescription(candidate.sourceAnnouncementKey());
        if (pendingConflicts.contains(conflictRequest)) {
            failureRecorder.resolve(targetSource, conflictRequest);
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
