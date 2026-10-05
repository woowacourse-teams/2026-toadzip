package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.repository.external.IngestServiceKeyContext;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class IngestExecutionScopeServiceKeyTest {

    @Test
    void 중첩_실행키는_닫을_때_이전_키를_복원하고_마지막에_제거한다() {
        try (var outer = IngestExecutionScope.open(null, null, "outer-test-key")) {
            assertThatThrownBy(() -> {
                try (var inner = IngestExecutionScope.open(null, null, "inner-test-key")) {
                    assertThat(IngestServiceKeyContext.current()).contains("inner-test-key");
                    throw new IllegalStateException("실행 실패");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(IngestServiceKeyContext.current()).contains("outer-test-key");
        }
        assertThat(IngestServiceKeyContext.current()).isEmpty();
    }

    @Test
    void 병렬_수집_작업은_캡처된_키를_전달하고_재사용_스레드에서_제거한다() throws Exception {
        Callable<String> first;
        Callable<String> second;
        try (var ignored = IngestExecutionScope.open(null, null, "first-test-key")) {
            first = IngestExecutionScope.propagate(() -> IngestServiceKeyContext.current().orElseThrow());
        }
        try (var ignored = IngestExecutionScope.open(null, null, "second-test-key")) {
            second = IngestExecutionScope.propagate(() -> IngestServiceKeyContext.current().orElseThrow());
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstResult = workers.submit(first);
            var secondResult = workers.submit(second);
            assertThat(firstResult.get()).isEqualTo("first-test-key");
            assertThat(secondResult.get()).isEqualTo("second-test-key");
            assertThat(workers.submit(() -> IngestServiceKeyContext.current().isEmpty()).get()).isTrue();
        }
        assertThat(IngestServiceKeyContext.current()).isEmpty();
    }

    @Test
    void 키가_없는_캡처는_작업_스레드의_다른_키를_쓰지_않고_닫은_뒤_복원한다() throws Exception {
        Callable<Boolean> captured = IngestExecutionScope.propagate(() -> IngestServiceKeyContext.current().isEmpty());
        try (var ignored = IngestExecutionScope.open(null, null, "worker-test-key")) {
            assertThat(captured.call()).isTrue();
            assertThat(IngestServiceKeyContext.current()).contains("worker-test-key");
        }
        assertThat(IngestServiceKeyContext.current()).isEmpty();
    }
}
