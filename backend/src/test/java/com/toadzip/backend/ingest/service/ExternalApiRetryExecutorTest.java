package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.repository.external.ExternalApiRequestException;

class ExternalApiRetryExecutorTest {

    private final ExternalApiRetryExecutor executor = new ExternalApiRetryExecutor(Duration.ZERO);

    @Test
    @DisplayName("재시도 가능한 실패는 최대 횟수 안에서 다시 실행한다")
    void retriesRetryableFailure() {
        AtomicInteger executions = new AtomicInteger();
        ExternalApiCallCounter callCounter = new ExternalApiCallCounter();

        String result = executor.execute(
                ExternalApi.MYHOME_COMPLEX,
                "pageNo=1",
                () -> responseAfterTwoFailures(executions),
                callCounter
        );

        assertThat(result).isEqualTo("success");
        assertThat(executions).hasValue(3);
        assertThat(callCounter.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("재시도할 수 없는 실패는 한 번만 실행한다")
    void doesNotRetryPermanentFailure() {
        AtomicInteger executions = new AtomicInteger();
        ExternalApiCallCounter callCounter = new ExternalApiCallCounter();
        ExternalApiRequestException failure = new ExternalApiRequestException("일일 요청 한도 초과");

        assertThatThrownBy(() -> executor.execute(
                ExternalApi.MYHOME_COMPLEX,
                "pageNo=1",
                () -> {
                    executions.incrementAndGet();
                    throw failure;
                },
                callCounter
        )).isSameAs(failure);
        assertThat(executions).hasValue(1);
        assertThat(callCounter.count()).isOne();
    }

    private String responseAfterTwoFailures(AtomicInteger executions) {
        if (executions.incrementAndGet() < 3) {
            throw ExternalApiRequestException.retryable("일시적 실패", new IllegalStateException("504"));
        }
        return "success";
    }
}
