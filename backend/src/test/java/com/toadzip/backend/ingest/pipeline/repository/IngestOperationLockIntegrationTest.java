package com.toadzip.backend.ingest.pipeline.repository;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.LH_ANNOUNCEMENT_COLLECTION;
import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.LH_ANNOUNCEMENT_ENRICHMENT;
import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION;
import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_MAPPING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IngestOperationLockIntegrationTest {

    @Autowired
    private IngestOperationLock executionLock;

    @Test
    void 같은_작업은_거절하고_독립된_작업은_같은_빈에서도_동시에_실행한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> executionLock.tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> {
                started.countDown();
                await(release);
                return "lh";
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executionLock.tryRun(LH_ANNOUNCEMENT_COLLECTION, () -> "duplicate")).isEmpty();
                assertThat(executionLock.tryRun(MYHOME_ANNOUNCEMENT_COLLECTION, () -> "myhome"))
                        .contains("myhome");
            }
            finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS)).contains("lh");
        }
    }

    @Test
    void 같은_스레드에서도_같은_DB_키의_중첩_작업은_거절하고_종료_후_재획득한다() {
        var result = executionLock.tryRun(MYHOME_ANNOUNCEMENT_MAPPING, () -> {
            assertThat(executionLock.tryRun(MYHOME_ANNOUNCEMENT_MAPPING, () -> "nested mapping")).isEmpty();
            assertThat(executionLock.tryRun(LH_ANNOUNCEMENT_ENRICHMENT, () -> "nested enrichment")).isEmpty();
            assertThat(executionLock.tryRun(MYHOME_ANNOUNCEMENT_COLLECTION, () -> "collection"))
                    .contains("collection");
            return "mapped";
        });

        assertThat(result).contains("mapped");
        assertThat(executionLock.tryRun(LH_ANNOUNCEMENT_ENRICHMENT, () -> "enriched")).contains("enriched");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("실행 잠금이 해제되지 않았습니다.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
