package com.toadzip.backend.ingest.lh.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.lh.collection.configuration.LhAnnouncementClientProperties;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private final LhAnnouncementClientProperties clientProperties;

    /** {@code requests}의 원소 하나는 같은 조회 조건을 공유하는 후보 묶음이다. 묶음마다 한 번만 조회한다. */
    ExternalDataCollectionReport collect(ExternalDataSource targetSource, List<List<Candidate>> requests) {
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
                            collectionTask(targetSource, next.candidates())
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
    }

    private Callable<ExternalDataCollectionReport> collectionTask(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates
    ) {
        return IngestExecutionScope.propagate(() -> collectSharedRequest(targetSource, requestCandidates));
    }

    private ExternalDataCollectionReport collectSharedRequest(
            ExternalDataSource targetSource,
            List<Candidate> requestCandidates
    ) {
        ExternalDataCollectionReport report = candidateCollector.collect(targetSource, requestCandidates.getFirst());
        if (report.failedRequestCount() > 0) {
            return report;
        }
        for (Candidate candidate : requestCandidates.subList(1, requestCandidates.size())) {
            progressManager.recordLinked(targetSource, candidate);
        }
        return report;
    }
}
