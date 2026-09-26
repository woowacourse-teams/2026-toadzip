package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionStateService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineProgressListener;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineRunner;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.time.Instant;
import java.time.Clock;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMonitor;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class DataPipelineStopIntegrationTest {

    @Autowired private DataPipelineExecutionService service;
    @Autowired private DataPipelineExecutionStateService stateService;
    @Autowired private DataPipelineExecutionLock executionLock;
    @Autowired private ExternalDataRetryExecutor retryExecutor;
    @MockitoBean private DataPipelineRunner runner;
    @MockitoBean(name = "dataPipelineExecutor") private Executor executor;

    @Test
    void 중지는_진행_중인_요청을_기다리고_다음_호출을_차단한_뒤_잠금을_해제한다() throws Exception {
        AtomicReference<Runnable> task = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstFinished = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());
        doAnswer(invocation -> {
            DataPipelineProgressListener listener = invocation.getArgument(1);
            listener.started(DataPipelineStep.COLLECT_MYHOME_COMPLEXES);
            retryExecutor.execute(ExternalDataSource.MYHOME_COMPLEX, "pageNo=1", () -> {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                firstFinished.set(true);
                return "completed";
            }, new ExternalDataCallCounter());
            retryExecutor.execute(ExternalDataSource.MYHOME_COMPLEX, "pageNo=2", () -> {
                calls.incrementAndGet();
                return "must not run";
            }, new ExternalDataCallCounter());
            return null;
        }).when(runner).run(any(), any());
        var accepted = service.start(DataPipelineType.COMPLEX_COLLECTION);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(task.get());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var stopping = service.requestStop(accepted.executionId());
                assertThat(stopping.stopRequested()).isTrue();
                assertThat(stopping.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
                assertThat(executionLock.tryAcquire()).isEmpty();
                assertThat(service.requestStop(accepted.executionId()).stopRequested()).isTrue();
            }
            finally {
                release.countDown();
            }
            result.get(10, TimeUnit.SECONDS);
        }
        var stopped = service.find(accepted.executionId());
        assertThat(firstFinished).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(stopped.status()).isEqualTo(DataPipelineExecutionStatus.STOPPED);
        assertThat(stopped.failure()).isNull();
        assertThat(stopped.finishedAt()).isNotNull();
        assertThat(stopped.externalRequestCount()).isEqualTo(1);
        assertThat(stopped.lastRequestDescription()).contains("pageNo=1");
        try (var lease = executionLock.tryAcquire().orElseThrow()) {
            assertThat(lease).isNotNull();
        }
    }

    @Test
    void 병렬_요청의_진행_횟수와_중지_요청은_서로_덮어쓰지_않는다() throws Exception {
        UUID id = UUID.randomUUID();
        stateService.create(id, DataPipelineType.COMPLEX_COLLECTION, Instant.now());
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 20).mapToObj(index -> workers.submit(() -> {
                stateService.recordRequestStarted(id, "page=" + index, Instant.now());
                stateService.recordRequestFinished(id, Instant.now());
            })).toList();
            stateService.requestStop(id);
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        stateService.startStep(id, DataPipelineStep.COLLECT_MYHOME_COMPLEXES);
        stateService.completeStep(id, DataPipelineStep.COLLECT_MYHOME_COMPLEXES, "{}");
        stateService.stop(id, Instant.now());
        var stopped = service.find(id);
        assertThat(stopped.externalRequestCount()).isEqualTo(20);
        assertThat(stopped.stopRequested()).isTrue();
        assertThat(stopped.completedSteps()).containsExactly("마이홈 단지 수집");
    }

    @Test
    void 완료된_실행의_중지_요청은_완료_결과를_변경하지_않는다() {
        UUID id = UUID.randomUUID();
        stateService.create(id, DataPipelineType.COMPLEX_COLLECTION, Instant.now());
        for (var step : DataPipelineType.COMPLEX_COLLECTION.steps()) {
            stateService.startStep(id, step);
            stateService.completeStep(id, step, "{}");
        }
        stateService.complete(id, Instant.now());
        var result = service.requestStop(id);
        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(result.stopRequested()).isFalse();
    }

    @Test
    void 실제_작업_완료는_재시도_횟수와_독립적이며_다음_단계에서_초기화된다() throws Exception {
        UUID id = UUID.randomUUID();
        stateService.create(id, DataPipelineType.COMPLEX_COLLECTION, Instant.now());
        stateService.startStep(id, DataPipelineStep.COLLECT_MYHOME_COMPLEXES);
        var monitor = new DataPipelineExecutionMonitor(id, stateService, Clock.systemUTC());
        monitor.beginWork("전체 지역", "지역", 40);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 20).mapToObj(index -> workers.submit(() -> {
                monitor.requestFinished();
                monitor.requestFinished();
                monitor.workCompleted();
            })).toList();
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        var progress = service.find(id);
        assertThat(progress.externalRequestCount()).isEqualTo(40);
        assertThat(progress.workProgress().completedCount()).isEqualTo(20);
        assertThat(progress.workProgress().totalCount()).isEqualTo(40);
        assertThat(progress.workProgress().updatedAt()).isAfterOrEqualTo(progress.workProgress().startedAt());
        stateService.completeStep(id, DataPipelineStep.COLLECT_MYHOME_COMPLEXES, "{}");
        stateService.startStep(id, DataPipelineStep.COLLECT_LH_LEASE_CATALOG);
        assertThat(service.find(id).workProgress()).isNull();
        monitor.beginWork("카탈로그", "페이지", -1);
        assertThat(service.find(id).workProgress().totalCount()).isEqualTo(-1);
        monitor.pageCompleted(1, 250, 100);
        assertThat(service.find(id).workProgress().totalCount()).isEqualTo(3);
        assertThat(service.find(id).workProgress().completedCount()).isEqualTo(1);
        stateService.requestStop(id);
        stateService.stop(id, Instant.now());
        monitor.workCompleted();
        assertThat(service.find(id).workProgress().completedCount()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 대기 시간 초과");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
