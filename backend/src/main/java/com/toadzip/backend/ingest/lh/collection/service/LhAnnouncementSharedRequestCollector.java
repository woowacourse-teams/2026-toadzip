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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
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
        AtomicReference<ExternalDataCollectionReport> report = new AtomicReference<>(
                ExternalDataCollectionReport.empty(targetSource.operation()));
        schedule(requests, candidates -> collectSharedRequest(targetSource, candidates), (candidates, result) -> {
            report.accumulateAndGet(result, ExternalDataCollectionReport::plus);
            IngestExecutionScope.workCompleted();
        }, result -> result.rateLimitedRequestCount() > 0);
        return report.get();
    }

    /**
     * 묶음마다 공급을 받고 성공하면 상세를 받는다. 외부 호출은 병렬로 하고, 묶음이 끝날 때마다 호출한 스레드에서 listener가
     * 그 묶음을 이어받는다. 공급이나 상세를 다 받지 못한 묶음은 listener에 넘기지 않고 대기 상태로 남긴다.
     */
    BundleReport collectBundles(List<List<Candidate>> requests, Consumer<List<Candidate>> listener) {
        AtomicReference<ExternalDataCollectionReport> supply = new AtomicReference<>(
                ExternalDataCollectionReport.empty(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY.operation()));
        AtomicReference<ExternalDataCollectionReport> detail = new AtomicReference<>(
                ExternalDataCollectionReport.empty(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL.operation()));
        schedule(requests, this::collectBundle, (candidates, result) -> {
            supply.accumulateAndGet(result.supply(), ExternalDataCollectionReport::plus);
            if (result.detail() != null) {
                detail.accumulateAndGet(result.detail(), ExternalDataCollectionReport::plus);
            }
            IngestExecutionScope.workCompleted();
            if (result.complete()) {
                listener.accept(candidates);
            }
        }, BundleResult::rateLimited);
        return new BundleReport(supply.get(), detail.get());
    }

    private BundleResult collectBundle(List<Candidate> candidates) {
        ExternalDataCollectionReport supply = collectSharedRequest(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidates);
        if (supply.failedRequestCount() > 0) {
            return new BundleResult(supply, null);
        }
        return new BundleResult(supply, collectSharedRequest(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidates));
    }

    /** selectedPanIds는 이번 실행이 묶음으로 수집하려고 고른 공고 ID 전부다. 받지 못한 묶음의 공고도 들어 있다. */
    public record BundleReport(ExternalDataCollectionReport supply, ExternalDataCollectionReport detail,
            Set<String> selectedPanIds) {

        public BundleReport(ExternalDataCollectionReport supply, ExternalDataCollectionReport detail) {
            this(supply, detail, java.util.Set.of());
        }

        BundleReport withSelectedPanIds(Set<String> panIds) {
            return new BundleReport(supply, detail, panIds);
        }
    }

    private record BundleResult(ExternalDataCollectionReport supply, ExternalDataCollectionReport detail) {

        boolean complete() {
            return detail != null && detail.failedRequestCount() == 0;
        }

        boolean rateLimited() {
            return supply.rateLimitedRequestCount() > 0 || (detail != null && detail.rateLimitedRequestCount() > 0);
        }
    }

    /**
     * 같은 LH 공고의 요청은 동시에 실행하지 않고, 호출 제한이나 실패가 나면 새 요청을 시작하지 않는다. 끝난 요청은 완료
     * 순서대로 {@code completed}가 이 메서드를 부른 스레드에서 처리한다.
     */
    private <R> void schedule(List<List<Candidate>> requests, Function<List<Candidate>, R> task,
            BiConsumer<List<Candidate>, R> completed, Predicate<R> stopsScheduling) {
        try (ExecutorService executor = Executors.newFixedThreadPool(clientProperties.maxConcurrentRequests())) {
            CompletionService<R> completions = new ExecutorCompletionService<>(executor);
            List<CollectionRequest> pending = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                pending.add(new CollectionRequest(index, requests.get(index)));
            }
            Map<Future<R>, CollectionRequest> running = new HashMap<>();
            Map<Integer, Throwable> failures = new TreeMap<>();
            boolean stopScheduling = false;
            while (!pending.isEmpty() || !running.isEmpty()) {
                while (!stopScheduling && running.size() < clientProperties.maxConcurrentRequests()) {
                    CollectionRequest next = takeNextEligible(pending, running.values());
                    if (next == null) {
                        break;
                    }
                    Future<R> result = completions.submit(
                            IngestExecutionScope.propagate(() -> task.apply(next.candidates())));
                    running.put(result, next);
                }
                if (running.isEmpty()) {
                    break;
                }
                Future<R> done = completions.take();
                CollectionRequest finished = running.remove(done);
                try {
                    R result = done.get();
                    completed.accept(finished.candidates(), result);
                    stopScheduling |= stopsScheduling.test(result);
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
