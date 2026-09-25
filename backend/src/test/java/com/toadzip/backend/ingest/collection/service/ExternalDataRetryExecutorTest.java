package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExternalDataRetryExecutorTest {

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ExternalDataRetryExecutor executor = new ExternalDataRetryExecutor(Duration.ZERO, meterRegistry);

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
        assertThat(meterRegistry.find("ingest.external.request")
                .tags("source", "MYHOME_COMPLEX", "result", "failed").timer()).isNotNull();
        assertThat(meterRegistry.get("ingest.external.request")
                .tags("source", "MYHOME_COMPLEX", "result", "failed").timer().count()).isEqualTo(2);
        assertThat(meterRegistry.get("ingest.external.request")
                .tags("source", "MYHOME_COMPLEX", "result", "completed").timer().count()).isOne();
        assertThat(meterRegistry.get("ingest.external.retry").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.get("ingest.external.retry.wait").timer().count()).isEqualTo(2);
        assertThat(meterRegistry.get("ingest.external.request").tag("result", "failed")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200);
        assertThat(meterRegistry.get("ingest.external.request").tag("result", "completed")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100);
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

    @Test
    @DisplayName("호출 제한은 재시도 가능 응답이어도 즉시 중단한다")
    void doesNotRetryRateLimitFailure() {
        AtomicInteger executions = new AtomicInteger();
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        ExternalDataRequestException failure = ExternalDataRequestException.rateLimited(
                "초당 요청 한도 초과",
                null,
                true
        );

        assertThatThrownBy(() -> executor.execute(
                ExternalDataSource.MYHOME_COMPLEX,
                "pageNo=1",
                () -> {
                    executions.incrementAndGet();
                    throw failure;
                },
                callCounter
        )).isInstanceOfSatisfying(ExternalDataCallFailureException.class, exception -> {
            assertThat(exception.getAttemptCount()).isOne();
            assertThat(exception.isRateLimited()).isTrue();
        });
        assertThat(executions).hasValue(1);
        assertThat(callCounter.count()).isOne();
        assertThat(meterRegistry.find("ingest.external.request")
                .tag("result", "rate_limited").timer()).isNotNull();
        assertThat(meterRegistry.find("ingest.external.retry").counter()).isNull();
    }

    @Test
    void 회로_차단으로_실제_요청이_없으면_API_호출수에_포함하지_않는다() {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();

        assertThatThrownBy(() -> executor.execute(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                "PAN_ID=100",
                () -> {
                    throw new LhAnnouncementUnavailableException("LH 호출 제한 차단", true);
                },
                callCounter
        )).isInstanceOf(LhAnnouncementUnavailableException.class);

        assertThat(callCounter.count()).isZero();
    }

    private String responseAfterTwoFailures(AtomicInteger executions) {
        clock.add(Duration.ofMillis(100));
        if (executions.incrementAndGet() < 3) {
            throw ExternalDataRequestException.retryable("일시적 실패", new IllegalStateException("504"));
        }
        return "success";
    }
}
