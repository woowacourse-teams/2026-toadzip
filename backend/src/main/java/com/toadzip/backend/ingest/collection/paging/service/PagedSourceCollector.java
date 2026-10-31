package com.toadzip.backend.ingest.collection.paging.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.dto.PagedSourceRequest;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.SourceCollectionAttempt;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 페이지로 나눠 받는 원천을 끝까지 모아 한 번에 저장한다. 실행을 확인한 뒤 요청을 만들고, 수집 이력을 열어 페이지마다
 * 실행 확인·재시도 호출·검증·진행 보고를 반복한 뒤 저장한다. 페이지 검증과 저장의 실패는 재시도하지 않고, 수집 이력에는
 * 원래 실패를 남긴 채 마지막으로 받은 페이지의 호출 실패로 바꿔 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PagedSourceCollector {

    private final SourceCollectionRecordService records;
    private final ExternalDataRetryExecutor retry;
    private final ExternalDataFailureRecorder failureRecorder;
    private final Clock clock;

    /** 원천을 저장하고 저장한 행 수를 돌려준다. 실패는 기록하지 않고 그대로 던진다. */
    public <A extends PagedSourceRequest, P, C> int collect(
            PagedSource<A, P, C> source, Function<Instant, A> newRequest, ExternalDataCallCounter counter
    ) {
        return collect(source, prepare(newRequest), counter, RateLimitSignal.NONE);
    }

    public <A extends PagedSourceRequest, P, C> ExternalDataCollectionReport collectAndReport(
            PagedSource<A, P, C> source, Function<Instant, A> newRequest
    ) {
        return collectAndReport(source, newRequest, new ExternalDataCallCounter(), RateLimitSignal.NONE);
    }

    /**
     * 원천을 저장하고 보고한다. 외부 호출 실패는 실패 기록으로 남겨 실패 보고로 돌려주고, 성공하면 이 요청 범위의 이전 실패
     * 기록을 해소한다. 호출 제한에 걸리면 실패를 기록하기 전에 함께 수집하는 단위에 알린다.
     */
    public <A extends PagedSourceRequest, P, C> ExternalDataCollectionReport collectAndReport(
            PagedSource<A, P, C> source, Function<Instant, A> newRequest, ExternalDataCallCounter counter,
            RateLimitSignal rateLimit
    ) {
        SourceCollectionAttempt<A> attempt = prepare(newRequest);
        A request = attempt.request();
        int stored;
        try {
            stored = collect(source, attempt, counter, rateLimit);
        } catch (ExternalDataCallFailureException | ExternalDataRequestException failure) {
            int rateLimitedRequestCount = ExternalDataRateLimit.count(failure);
            if (rateLimitedRequestCount > 0) {
                rateLimit.reached();
            }
            failureRecorder.record(source.source(), request.failureDescription(), failure, log,
                    source.failureLogMessage());
            return new ExternalDataCollectionReport(source.source().operation(), 0, 1, counter.count(), 0,
                    rateLimitedRequestCount);
        }
        failureRecorder.resolveStartingWith(source.source(), request.resolvedFailurePrefix());
        return new ExternalDataCollectionReport(source.source().operation(), stored, 0, counter.count());
    }

    private <A extends PagedSourceRequest> SourceCollectionAttempt<A> prepare(Function<Instant, A> newRequest) {
        return SourceCollectionAttempt.prepare(() -> newRequest.apply(clock.instant()));
    }

    private <A extends PagedSourceRequest, P, C> int collect(
            PagedSource<A, P, C> source, SourceCollectionAttempt<A> attempt, ExternalDataCallCounter counter,
            RateLimitSignal rateLimit
    ) {
        var calls = new PageCalls<>(source, attempt.request(), counter, rateLimit);
        UUID recordId;
        try {
            recordId = attempt.run(records, request -> collectPages(source, request, calls), source::store);
        } catch (IllegalArgumentException failure) {
            throw calls.failure(failure);
        }
        return records.storedRowCount(recordId);
    }

    private <A extends PagedSourceRequest, P, C> C collectPages(
            PagedSource<A, P, C> source, A request, PageCalls<A, P> calls
    ) {
        PageBuffer<P, C> buffer = source.newBuffer(request);
        for (int page = 1; page <= request.maxPages() && !buffer.isComplete(); page++) {
            SourceCollectionAttempt.verifyCanContinue();
            buffer.add(calls.fetch(page));
            if (source.reportsPageProgress()) {
                IngestExecutionScope.pageCompleted(page, buffer.totalCount(), request.pageSize());
            }
        }
        return buffer.finish(clock.instant());
    }

    /** 페이지 호출을 재시도 실행기로 보내고, 실패를 바꿀 때 쓸 마지막 페이지와 그 호출 횟수를 기억한다. */
    private final class PageCalls<A extends PagedSourceRequest, P> {

        private final PagedSource<A, P, ?> source;
        private final A request;
        private final ExternalDataCallCounter counter;
        private final RateLimitSignal rateLimit;
        private int lastPage = 1;
        private int lastAttemptCount;

        private PageCalls(
                PagedSource<A, P, ?> source, A request, ExternalDataCallCounter counter, RateLimitSignal rateLimit
        ) {
            this.source = source;
            this.request = request;
            this.counter = counter;
            this.rateLimit = rateLimit;
        }

        private P fetch(int page) {
            lastPage = page;
            int before = counter.count();
            try {
                return retry.execute(source.source(), request.pageDescription(page),
                        () -> source.fetch(request, page), counter, rateLimit::checkBeforeAttempt);
            } finally {
                lastAttemptCount = counter.count() - before;
            }
        }

        private ExternalDataCallFailureException failure(IllegalArgumentException cause) {
            return new ExternalDataCallFailureException(source.source(), request.pageDescription(lastPage),
                    lastAttemptCount, new ExternalDataRequestException(cause.getMessage()));
        }
    }
}
