package com.toadzip.backend.ingest.collection.board.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryInterruptedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 게시판 수집 한 번의 외부 요청을 보낸다. 요청마다 간격을 두고 중지 요청을 확인한 뒤 재시도 실행기로 보내며, 호출 수와
 * 호출 제한에 걸린 요청 수를 센다. 게시판이 잡아 넘긴 호출 제한도 세므로 수집기는 다음 요청을 멈출 수 있다.
 */
public final class BoardRequests {

    private final ExternalDataSource source;
    private final ExternalDataRetryExecutor retryExecutor;
    private final Duration interval;
    private final ExternalDataCallCounter calls = new ExternalDataCallCounter();
    private int rateLimitedCount;

    BoardRequests(ExternalDataSource source, ExternalDataRetryExecutor retryExecutor, Duration interval) {
        this.source = source;
        this.retryExecutor = retryExecutor;
        this.interval = interval;
    }

    public <T> T send(String description, Supplier<T> action) {
        try {
            return retryExecutor.execute(source, description, () -> {
                pause();
                IngestExecutionScope.checkStopRequested();
                return action.get();
            }, calls);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            rateLimitedCount += ExternalDataRateLimit.count(exception);
            throw exception;
        }
    }

    boolean rateLimited() {
        return rateLimitedCount > 0;
    }

    int rateLimitedCount() {
        return rateLimitedCount;
    }

    int callCount() {
        return calls.count();
    }

    private void pause() {
        try {
            Thread.sleep(interval);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalDataRetryInterruptedException(exception);
        }
    }
}
