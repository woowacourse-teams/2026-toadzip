package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.configuration.LhAnnouncementClientProperties;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 조회 조건을 공유하는 LH 공고 요청을 정해진 동시성으로 실행하고, 성공한 요청의 나머지 공고를 연결한다.
 * 같은 LH 공고의 요청은 동시에 실행하지 않고, 호출 제한이나 실패가 나면 새 요청을 시작하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class LhAnnouncementSharedRequestCollector {

    private final LhAnnouncementCandidateCollector candidateCollector;
    private final LhAnnouncementCollectionProgressManager progressManager;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementClientProperties clientProperties;

    static String sourceSelectionDescription(String sourceAnnouncementKey) {
        return "myhomeAnnouncementCurrentSource=" + sourceAnnouncementKey;
    }

    /** 끝난 요청의 성공 여부를 {@code completedRequests}에 기록한다. 호출자는 다음 배치에서 같은 요청을 다시 실행하지 않고 그 결과로 연결한다. */
    ExternalDataCollectionReport collect(
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
        return IngestExecutionScope.propagate(
                () -> collectSharedRequest(targetSource, requestCandidates, pendingConflicts));
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
        link(targetSource, requestCandidates.subList(1, requestCandidates.size()), pendingConflicts);
        resolveConflict(targetSource, requestCandidates.getFirst(), pendingConflicts);
        return report;
    }

    void link(
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
}
