package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;

class ExternalDataRetryExecutorTest {

    private final ExternalDataRetryExecutor executor = new ExternalDataRetryExecutor(Duration.ZERO);

    @Test
    @DisplayName("재시도 가능한 실패는 최대 횟수 안에서 다시 실행한다")
    void retriesRetryableFailure() {
        AtomicInteger executions = new AtomicInteger();
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();

        String result = executor.execute(
                ExternalDataSource.MYHOME_COMPLEX,
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
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        ExternalDataRequestException failure = new ExternalDataRequestException("일일 요청 한도 초과");

        assertThatThrownBy(() -> executor.execute(
                ExternalDataSource.MYHOME_COMPLEX,
                "pageNo=1",
                () -> {
                    executions.incrementAndGet();
                    throw failure;
                },
                callCounter
        )).isInstanceOfSatisfying(ExternalDataCallFailureException.class, exception -> {
            assertThat(exception.getRequestDescription()).isEqualTo("pageNo=1");
            assertThat(exception.getAttemptCount()).isOne();
            assertThat(exception.getCause()).isSameAs(failure);
        });
        assertThat(executions).hasValue(1);
        assertThat(callCounter.count()).isOne();
    }

    private String responseAfterTwoFailures(AtomicInteger executions) {
        if (executions.incrementAndGet() < 3) {
            throw ExternalDataRequestException.retryable("일시적 실패", new IllegalStateException("504"));
        }
        return "success";
    }
}
