package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class IngestExecutionScopeTest {

    @AfterEach
    void clearLog() {
        MDC.clear();
    }

    @Test
    void 실행_소유권_없이_진입점을_부르면_거절한다() {
        assertThatThrownBy(IngestExecutionScope::requireHeld).isInstanceOf(IllegalStateException.class)
                .hasMessage("수집·정제는 실행 소유권 안에서만 실행할 수 있습니다.");

        try (var ignored = IngestExecutionScope.open(null)) {
            assertThatThrownBy(IngestExecutionScope::requireHeld).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 실행_소유권을_잡았으면_아직_잡고_있는지_확인한다() {
        var lost = new IllegalStateException("실행 소유권을 잃었습니다.");
        Lease lease = mock(Lease.class);
        doThrow(lost).when(lease).verifyHeld();

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(IngestExecutionScope::requireHeld).isSameAs(lost);
        }
    }

    @Test
    void 작업_스레드에_실행_ID와_로그_맥락을_전달하고_재사용_스레드에서_제거한다() throws Exception {
        UUID executionId = UUID.randomUUID();
        Callable<String> task = captureInScope(executionId, () -> {
            assertThat(IngestExecutionScope.currentExecutionId()).contains(executionId);
            return MDC.get("traceId");
        });

        try (var workers = Executors.newSingleThreadExecutor()) {
            assertThat(workers.submit(task).get()).isEqualTo("request-trace");
            assertWorkerIsClean(workers);
        }
    }

    @Test
    void 예외로_끝난_작업도_재사용_스레드의_실행_ID와_로그_맥락을_제거한다() throws Exception {
        Callable<String> task = captureInScope(UUID.randomUUID(), () -> {
            throw new IllegalStateException("수집 실패");
        });

        try (var workers = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> workers.submit(task).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertWorkerIsClean(workers);
        }
    }

    @Test
    void 호출_스레드에서_실행해도_이전_실행_ID와_로그_맥락을_복원한다() throws Exception {
        UUID captured = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        Callable<String> task = captureInScope(captured, () -> MDC.get("traceId"));

        MDC.put("traceId", "worker-trace");
        try (var ignored = IngestExecutionScope.open(current, null, null)) {
            assertThat(task.call()).isEqualTo("request-trace");
            assertThat(IngestExecutionScope.currentExecutionId()).contains(current);
            assertThat(MDC.get("traceId")).isEqualTo("worker-trace");
        }
        assertThat(IngestExecutionScope.currentExecutionId()).isEmpty();
    }

    private Callable<String> captureInScope(UUID executionId, Callable<String> work) {
        MDC.put("traceId", "request-trace");
        try (var ignored = IngestExecutionScope.open(executionId, null, null)) {
            return IngestExecutionScope.propagate(work);
        }
        finally {
            MDC.clear();
        }
    }

    private void assertWorkerIsClean(ExecutorService workers) throws Exception {
        assertThat(workers.submit(IngestExecutionScope::currentExecutionId).get()).isEmpty();
        assertThat(workers.submit(() -> MDC.get("traceId")).get()).isNull();
    }
}
