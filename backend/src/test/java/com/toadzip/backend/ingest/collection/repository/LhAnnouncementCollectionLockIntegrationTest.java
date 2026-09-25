package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
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
class LhAnnouncementCollectionLockIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void 다른_인스턴스의_상세_수집_중에는_공급과_목록도_실행하지_않는다() throws Exception {
        var first = new LhAnnouncementCollectionExecutionLock(dataSource);
        var second = new LhAnnouncementCollectionExecutionLock(dataSource);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> first.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, () -> {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("실행 잠금이 해제되지 않았습니다.");
                    }
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "detail";
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, () -> "supply")).isEmpty();
                assertThat(second.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_CATALOG, () -> "catalog")).isEmpty();
            }
            finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS)).contains("detail");
            assertThat(second.tryRun(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, () -> "supply")).contains("supply");
        }
    }
}
