package com.toadzip.backend.ingest.enrichment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingExecutionLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AnnouncementRefinementExecutionLockTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 마이홈_공고_매핑_중에는_LH_보강을_동시에_실행하지_않는다() throws Exception {
        MyHomeAnnouncementMappingExecutionLock mappingLock =
                new MyHomeAnnouncementMappingExecutionLock(dataSource);
        LhAnnouncementEnrichmentExecutionLock enrichmentLock =
                new LhAnnouncementEnrichmentExecutionLock(dataSource);
        CountDownLatch mappingStarted = new CountDownLatch(1);
        CountDownLatch releaseMapping = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var mapping = executor.submit(() -> mappingLock.tryRun(() -> {
                mappingStarted.countDown();
                try {
                    if (!releaseMapping.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("매핑 실행 잠금 대기가 끝나지 않았습니다.");
                    }
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("매핑 실행 잠금 대기가 중단됐습니다.", exception);
                }
                return "mapped";
            }));
            try {
                assertThat(mappingStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(enrichmentLock.tryRun(() -> "enriched")).isEmpty();
            }
            finally {
                releaseMapping.countDown();
            }
            assertThat(mapping.get(5, TimeUnit.SECONDS)).contains("mapped");
        }
    }
}
