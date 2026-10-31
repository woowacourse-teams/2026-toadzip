package com.toadzip.backend.ingest.failure.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.domain.IngestFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureReconciler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 정제·보강 실패 Store의 공통 저장 흐름이다. 어떤 실패를 대상으로 맞출지는 각 Store가 고른다. 외부 수집 실패는 요청 단위로 따로 관리한다. */
public abstract class IngestFailureStore<T extends IngestFailure<T>> {

    private final IngestFailureRepository<T> repository;
    private final Clock clock;

    protected IngestFailureStore(IngestFailureRepository<T> repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 관찰한 실패와 같은 원천 키의 지난 실패까지 함께 맞춰, 해결된 실패가 다시 나타나면 새로 만들지 않고 재발로 센다. */
    protected void reconcileWithHistory(List<T> stored, List<T> observed, UUID executionId) {
        Instant reconciledAt = clock.instant();
        List<T> history = List.of();
        if (!observed.isEmpty()) {
            history = repository.findAllBySourceKeyIn(
                    observed.stream().map(IngestFailure::getSourceKey).distinct().toList()
            );
        }
        save(IngestFailureReconciler.reconcile(stored, history, observed, reconciledAt, executionId));
    }

    /** 대상 실패만 맞춘다. 대상 밖의 지난 실패는 보지 않는다. */
    protected void reconcile(List<T> stored, List<T> observed, UUID executionId) {
        save(IngestFailureReconciler.reconcile(stored, List.of(), observed, clock.instant(), executionId));
    }

    /** 관리자가 공고를 보정해 미해결 실패를 모두 해결로 바꾼다. 원인이 남으면 다음 정제가 다시 연다. */
    protected void resolvePendingByAdmin(List<T> failures, String actor) {
        Instant resolvedAt = clock.instant();
        failures.stream()
                .filter(failure -> failure.getStatus() == PENDING)
                .forEach(failure -> failure.resolveByAdmin(resolvedAt, actor));
    }

    private void save(List<T> created) {
        created.forEach(repository::save);
    }
}
