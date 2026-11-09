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
 * 호출 제한에 걸렸거나, 요청이 연속으로 실패했거나, 한 번의 수집이 보낸 호출이 상한에 닿으면 멈춰야 한다고 알린다.
 * 차단되거나 장애인 기관에 남은 글마다 느린 실패를 되풀이하지 않기 위해서다.
 */
public final class BoardRequests {

    /** 재시도 세 번을 모두 쓴 요청이 이어서 이만큼 실패하면 기관이 막았거나 장애라고 본다. */
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 호출 간격 1초 기준 약 50분이다. 기본 조회 기간(90일)의 정상 수집은 이보다 훨씬 적게 부른다고 가정한 값이다. */
    static final int MAX_CALLS = 3_000;

    private final ExternalDataSource source;
    private final ExternalDataRetryExecutor retryExecutor;
    private final Duration interval;
    private final ExternalDataCallCounter calls = new ExternalDataCallCounter();
    private int rateLimitedCount;
    private int consecutiveFailures;

    BoardRequests(ExternalDataSource source, ExternalDataRetryExecutor retryExecutor, Duration interval) {
        this.source = source;
        this.retryExecutor = retryExecutor;
        this.interval = interval;
    }

    public <T> T send(String description, Supplier<T> action) {
        try {
            T response = retryExecutor.execute(source, description, () -> {
                pause();
                IngestExecutionScope.checkStopRequested();
                return action.get();
            }, calls);
            consecutiveFailures = 0;
            return response;
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            rateLimitedCount += ExternalDataRateLimit.count(exception);
            consecutiveFailures++;
            throw exception;
        }
    }

    /** 남은 요청을 보내지 말아야 하면 true다. 이미 보낸 요청의 결과는 그대로 둔다. */
    public boolean halted() {
        return haltReason() != null;
    }

    /** 멈춰야 하는 이유이고, 멈출 필요가 없으면 null이다. */
    String haltReason() {
        if (rateLimited()) {
            return "호출 제한(429)";
        }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            return "요청 연속 " + consecutiveFailures + "회 실패";
        }
        if (callLimitReached()) {
            return "호출 상한 " + MAX_CALLS + "회 도달";
        }
        return null;
    }

    boolean callLimitReached() {
        return calls.count() >= MAX_CALLS;
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
