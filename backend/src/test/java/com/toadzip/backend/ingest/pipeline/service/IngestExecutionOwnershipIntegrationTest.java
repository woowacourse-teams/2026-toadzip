package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(IngestExecutionOwnershipIntegrationTest.ProbeConfiguration.class)
class IngestExecutionOwnershipIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private DataPipelineExecutionService executionService;
    @Autowired private DataPipelineExecutionStateService executionStateService;
    @Autowired private DataPipelineExecutionRepository executionRepository;
    @Autowired private MyHomeAnnouncementSourceRepository sourceRepository;
    @Autowired private MyHomeSourceStore sourceStore;
    @Autowired private PausingWriter pausingWriter;
    @MockitoBean private DataPipelineRunner runner;
    @MockitoBean(name = "dataPipelineExecutor") private Executor executor;

    @Test
    void 잠금_세션을_잃은_실행의_원천_변경을_거절하고_실패로_남긴다() {
        MyHomeAnnouncementSource stored = sourceRepository.saveAndFlush(MyHomeAnnouncementSource.from(0,
                new MyHomeAnnouncementSourceSnapshot("OWNERSHIP", 1, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null)));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        doAnswer(invocation -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer ownerPid = jdbc.queryForObject("""
                    select pid from pg_locks
                    where locktype = 'advisory' and granted
                      and classid = (8432026090100001::bigint >> 32)::oid
                      and objid = (8432026090100001::bigint & 4294967295)::oid
                      and objsubid = 1
                      and database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            assertThat(jdbc.queryForObject("select pg_terminate_backend(?)", Boolean.class, ownerPid)).isTrue();
            DataPipelineExecutionLock second = new DataPipelineExecutionLock(dataSource);
            try (var ignored = second.tryAcquire().orElseThrow()) {
                sourceStore.completeAnnouncementCollection("stale-owner");
            }
            return null;
        }).when(runner).run(any(), any());

        var accepted = executionService.start(DataPipelineType.ANNOUNCEMENT_COLLECTION);

        assertThat(sourceRepository.findById(stored.getId()).orElseThrow().getConsecutiveMissCount()).isZero();
        assertThat(executionRepository.findByExecutionId(accepted.executionId()).orElseThrow().getStatus())
                .isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(executionRepository.findByExecutionId(accepted.executionId()).orElseThrow().getFailureMessage())
                .contains("소유권");
    }

    @Test
    void 세션이_살아_있어도_세대값이_다르면_쓰기_트랜잭션을_거절한다() {
        var lock = new DataPipelineExecutionLock(dataSource);
        try (var lease = lock.tryAcquire().orElseThrow(); var ignored = IngestExecutionScope.open(lease)) {
            lease.verifyHeld();
            new JdbcTemplate(dataSource).update(
                    "update ingest_execution_ownership set generation = generation + 1 where id = 1");

            assertThatThrownBy(() -> sourceStore.completeAnnouncementCollection("old-generation"))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }
    }

    @Test
    void 같은_소유자의_쓰기_트랜잭션은_병렬로_진행한다() throws Exception {
        var lock = new DataPipelineExecutionLock(dataSource);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var lease = lock.tryAcquire().orElseThrow();
                var ignored = IngestExecutionScope.open(lease);
                var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(IngestExecutionScope.propagate(() -> {
                pausingWriter.write(entered, release);
                return null;
            }));
            var second = workers.submit(IngestExecutionScope.propagate(() -> {
                pausingWriter.write(entered, release);
                return null;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            }
            finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 소유권_이전은_기존_쓰기의_롤백까지_대기한다() throws Exception {
        var firstLock = new DataPipelineExecutionLock(dataSource);
        var secondLock = new DataPipelineExecutionLock(dataSource);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long before = sourceRepository.count();
        try (var lease = firstLock.tryAcquire().orElseThrow();
                var ignored = IngestExecutionScope.open(lease);
                var workers = Executors.newFixedThreadPool(2)) {
            var writing = workers.submit(IngestExecutionScope.propagate(() -> {
                pausingWriter.write(entered, release);
                return null;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                terminateOwner();
                var takeover = workers.submit(() -> secondLock.tryAcquire().orElseThrow());
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from pg_stat_activity
                          where datname = current_database() and wait_event_type = 'Lock'
                            and query like 'UPDATE ingest_execution_ownership%')
                        """, Boolean.class)) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(jdbc.queryForObject("""
                        select exists(select 1 from pg_stat_activity
                          where datname = current_database() and wait_event_type = 'Lock'
                            and query like 'UPDATE ingest_execution_ownership%')
                        """, Boolean.class)).isTrue();
                assertThat(takeover).isNotDone();
                release.countDown();
                assertThatThrownBy(() -> writing.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IngestOwnershipLostException.class);
                try (var next = takeover.get(5, TimeUnit.SECONDS)) {
                    assertThat(next.generation()).isGreaterThan(lease.generation());
                    next.verifyHeld();
                }
                assertThat(sourceRepository.count()).isEqualTo(before);
            }
            finally {
                release.countDown();
            }
        }
    }

    @Test
    void heartbeat_쓰기_트랜잭션도_소유권을_확인한다() {
        UUID executionId = UUID.randomUUID();
        var lock = new DataPipelineExecutionLock(dataSource);
        try (var lease = lock.tryAcquire(executionId).orElseThrow(); var ignored = IngestExecutionScope.open(lease)) {
            assertThat(executionStateService.heartbeat(-1L, Instant.now())).isZero();
            new JdbcTemplate(dataSource).update(
                    "update ingest_execution_ownership set generation = generation + 1 where id = 1");
            assertThatThrownBy(() -> executionStateService.heartbeat(-1L, Instant.now()))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }
    }

    @Test
    void 대기중인_작업은_새_실행의_소유권을_물려받지_않고_스레드_범위를_복구한다() throws Exception {
        var firstLock = new DataPipelineExecutionLock(dataSource);
        var secondLock = new DataPipelineExecutionLock(dataSource);
        try (var first = firstLock.tryAcquire().orElseThrow(); var ignored = IngestExecutionScope.open(first)) {
            var pending = IngestExecutionScope.propagate(() -> {
                sourceStore.completeAnnouncementCollection("stale-queued-owner");
                return null;
            });
            terminateOwner();
            try (var second = secondLock.tryAcquire().orElseThrow(); var next = IngestExecutionScope.open(second)) {
                assertThatThrownBy(pending::call).isInstanceOf(IngestOwnershipLostException.class);
                assertThat(IngestExecutionScope.current()).contains(second);
            }
            assertThat(IngestExecutionScope.current()).contains(first);
        }
        assertThat(IngestExecutionScope.current()).isEmpty();
    }

    private void terminateOwner() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer pid = jdbc.queryForObject("""
                select pid from pg_locks where locktype = 'advisory' and granted
                  and classid = (8432026090100001::bigint >> 32)::oid
                  and objid = (8432026090100001::bigint & 4294967295)::oid and objsubid = 1
                  and database = (select oid from pg_database where datname = current_database())
                """, Integer.class);
        assertThat(jdbc.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        PausingWriter pausingWriter(MyHomeAnnouncementSourceRepository repository) {
            return new PausingWriter(repository);
        }
    }

    static class PausingWriter {
        private final MyHomeAnnouncementSourceRepository repository;

        PausingWriter(MyHomeAnnouncementSourceRepository repository) {
            this.repository = repository;
        }

        @Transactional
        public void write(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
            repository.saveAndFlush(MyHomeAnnouncementSource.from(0,
                    new MyHomeAnnouncementSourceSnapshot(UUID.randomUUID().toString(), 1, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null)));
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 쓰기 트랜잭션 종료 대기 시간 초과");
            }
        }
    }
}
