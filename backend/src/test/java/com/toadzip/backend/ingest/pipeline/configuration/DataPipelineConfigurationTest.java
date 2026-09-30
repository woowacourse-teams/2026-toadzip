package com.toadzip.backend.ingest.pipeline.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.pipeline.service.DataPipelineScheduleOrchestrator;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class DataPipelineConfigurationTest {

    @Test
    void 정기_실행은_heartbeat와_분리된_스케줄러를_사용한다() throws NoSuchMethodException {
        Method scheduledCycle = DataPipelineScheduleOrchestrator.class
                .getMethod("runScheduledCycle");
        Scheduled scheduled = scheduledCycle.getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler()).isEqualTo("dataPipelineScheduleTaskScheduler");
    }

    @Test
    void 스케줄_작업이_대기해도_heartbeat는_별도_스레드에서_실행된다() throws Exception {
        DataPipelineConfiguration configuration = new DataPipelineConfiguration();
        ThreadPoolTaskScheduler scheduler = configuration.dataPipelineScheduleTaskScheduler();
        ScheduledExecutorService heartbeat = configuration.dataPipelineHeartbeatExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        scheduler.initialize();
        try {
            scheduler.execute(() -> {
                started.countDown();
                try {
                    release.await();
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeat.submit(() -> Thread.currentThread().getName())
                    .get(2, TimeUnit.SECONDS)).startsWith("data-pipeline-heartbeat-");
        }
        finally {
            release.countDown();
            scheduler.shutdown();
            heartbeat.shutdownNow();
        }
    }
}
