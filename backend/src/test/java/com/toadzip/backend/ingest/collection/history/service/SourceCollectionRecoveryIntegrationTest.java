package com.toadzip.backend.ingest.collection.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuerySource;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMapper;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionStateService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineRunner;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DataPipelineExecutionStateService.class, JacksonAutoConfiguration.class})
class SourceCollectionRecoveryIntegrationTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-21T03:00:00Z");
    private static final Instant RECOVERED_AT = STARTED_AT.plusSeconds(300);
    private static final String RECOVERY_REASON = "프로세스 종료로 실행이 중단되었습니다.";

    @Autowired
    private SourceCollectionRecordRepository records;

    @Autowired
    private LhAnnouncementQuerySourceRepository sources;

    @Autowired
    private DataPipelineExecutionRepository executions;

    @Autowired
    private DataPipelineExecutionStateService states;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 실제_실행_잠금이_풀린_뒤에만_이력_조회가_만료_기록을_복구한다() {
        UUID id = execution(STARTED_AT);
        var running = record(id, STARTED_AT);
        var lock = new DataPipelineExecutionLock(dataSource);
        var service = queryService(lock);
        try {
            try (var lease = lock.tryAcquire(id).orElseThrow()) {
                assertThat(service.history(0, 100)).filteredOn(item -> item.executionId().equals(id)).singleElement()
                        .satisfies(item -> assertThat(item.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING));
                assertThat(records.findById(running.getId()).orElseThrow().getStatus())
                        .isEqualTo(CollectionStatus.RUNNING);
            }
            assertThat(service.history(0, 100)).filteredOn(item -> item.executionId().equals(id)).singleElement()
                    .satisfies(item -> assertThat(item.status()).isEqualTo(DataPipelineExecutionStatus.FAILED));
        } finally {
            records.deleteById(running.getId());
            executions.delete(executions.findByExecutionId(id).orElseThrow());
        }
    }

    @Test
    void 이력_조회는_빠른_재시작으로_최신_실행에_가려진_만료_실행도_복구한다() {
        UUID staleId = execution(STARTED_AT);
        var stale = record(staleId, STARTED_AT);
        Instant restartedAt = STARTED_AT.plusSeconds(60);
        states.recoverInterruptedBefore(restartedAt.minusSeconds(120), restartedAt, RECOVERY_REASON);
        UUID latestId = execution(restartedAt);
        states.fail(latestId, null, "최신 실행 실패", "{}", restartedAt.plusSeconds(1));
        entityManager.clear();

        var history = queryService(mock(DataPipelineExecutionLock.class)).history(0, 100);
        entityManager.clear();

        assertThat(history).filteredOn(item -> item.executionId().equals(staleId)).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(DataPipelineExecutionStatus.FAILED));
        assertThat(records.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        assertThat(executions.findByExecutionId(latestId).orElseThrow().getFailureMessage()).isEqualTo("최신 실행 실패");
    }

    @Test
    void 실행_잠금이_유지되는_동안_이력_조회는_만료_실행과_수집_기록을_보존한다() {
        UUID id = execution(STARTED_AT);
        var running = record(id, STARTED_AT);
        var lock = mock(DataPipelineExecutionLock.class);
        when(lock.isHeld()).thenReturn(true);
        entityManager.clear();

        var history = queryService(lock).history(0, 100);
        entityManager.clear();

        assertThat(history).filteredOn(item -> item.executionId().equals(id)).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING));
        assertThat(records.findById(running.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 실패_기록_DB_오류_뒤_종료된_실행의_잔여_기록을_재조회로_마감한다() {
        UUID id = execution(STARTED_AT);
        var request = new LhAnnouncementCollectionRequest(id, CollectionSource.LH_ANNOUNCEMENT_DETAIL,
                new LhAnnouncementQuery("preserved-pan", "03", "06", "07", "062"), 6, STARTED_AT);
        var recordService = new SourceCollectionRecordService(records,
                Clock.fixed(RECOVERED_AT, ZoneOffset.UTC), transactionManager);
        UUID recordId = recordService.start(request);
        var successful = record(id, STARTED_AT);
        successful.complete(STARTED_AT.plusSeconds(1), 0);
        records.saveAndFlush(successful);
        var source = LhAnnouncementQuerySource.create(request.source(), request.query());
        source.replace(request.source(), request.query(), request.requestHash(), request.description(),
                request.parameters(), STARTED_AT.plusSeconds(1), true, successful);
        sources.saveAndFlush(source);
        var failure = new ExternalDataRequestException("HTTP 500");
        try {
            jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_failed_record "
                    + "CHECK (id <> '" + recordId + "'::uuid OR status <> 'FAILED')");
            try {
                recordService.fail(recordId, request, failure);
                assertThat(failure.getSuppressed()).hasSize(1);
                states.fail(id, null, "원래 실행 실패", "{\"marker\":1}", STARTED_AT.plusSeconds(60));
                assertThat(queryService(mock(DataPipelineExecutionLock.class)).find(id).status())
                        .isEqualTo(DataPipelineExecutionStatus.FAILED);
                assertThat(records.findById(recordId).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
            } finally {
                jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_failed_record");
            }

            var service = queryService(mock(DataPipelineExecutionLock.class));
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> service.history(0, 100)),
                    CompletableFuture.runAsync(() -> service.history(0, 100))).join();
            assertThat(records.findById(recordId).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
            assertThat(records.findById(successful.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(sources.findById(source.getId()).orElseThrow().getCollectedAt())
                    .isEqualTo(STARTED_AT.plusSeconds(1));
            assertThat(executions.findByExecutionId(id).orElseThrow()).satisfies(found -> {
                assertThat(found.getFailureMessage()).isEqualTo("원래 실행 실패");
                assertThat(found.getFailureServerResponse()).isEqualTo("{\"marker\":1}");
                assertThat(found.getFinishedAt()).isEqualTo(STARTED_AT.plusSeconds(60));
            });
        } finally {
            sources.deleteById(source.getId());
            records.deleteById(recordId);
            records.deleteById(successful.getId());
            executions.delete(executions.findByExecutionId(id).orElseThrow());
        }
    }

    @Test
    void 중단된_실행의_진행_기록만_실패로_마감하고_성공_원천과_다른_기록은_보존한다() {
        UUID executionId = execution(STARTED_AT);
        var running = record(executionId, STARTED_AT);
        var successful = record(executionId, STARTED_AT);
        successful.complete(STARTED_AT.plusSeconds(1), 0);
        var failed = record(executionId, STARTED_AT);
        failed.fail(STARTED_AT.plusSeconds(1), "ExternalError", "외부 응답 실패");
        var imported = record(executionId, STARTED_AT);
        imported.complete(STARTED_AT.plusSeconds(1), 0);
        records.flush();
        entityManager.createNativeQuery("UPDATE source_collection_records SET status = 'IMPORTED' WHERE id = :id")
                .setParameter("id", imported.getId()).executeUpdate();
        var other = record(execution(RECOVERED_AT), RECOVERED_AT);
        var request = new LhAnnouncementCollectionRequest(executionId, CollectionSource.LH_ANNOUNCEMENT_DETAIL,
                new LhAnnouncementQuery("preserved-pan", "03", "06", "07", "062"), 6, STARTED_AT);
        var source = LhAnnouncementQuerySource.create(request.source(), request.query());
        source.replace(request.source(), request.query(), request.requestHash(), request.description(),
                request.parameters(), STARTED_AT.plusSeconds(1), true, successful);
        sources.saveAndFlush(source);
        Long sourceId = source.getId();
        entityManager.clear();

        assertThat(states.recoverInterrupted(executionId, cutoff(), RECOVERED_AT, RECOVERY_REASON)).isTrue();
        entityManager.clear();

        assertThat(records.findById(running.getId()).orElseThrow()).satisfies(found -> {
            assertThat(found.getStatus()).isEqualTo(CollectionStatus.FAILED);
            assertThat(found.getFinishedAt()).isEqualTo(RECOVERED_AT);
            assertThat(found.getErrorType()).isEqualTo("InterruptedExecution");
            assertThat(found.getFailureReason()).isEqualTo(RECOVERY_REASON);
        });
        assertThat(records.findById(successful.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.SUCCESS);
        assertThat(records.findById(failed.getId()).orElseThrow().getFailureReason()).isEqualTo("외부 응답 실패");
        assertThat(records.findById(imported.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.IMPORTED);
        assertThat(records.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
        assertThat(sources.findById(sourceId).orElseThrow()).satisfies(found -> {
            assertThat(found.getCollectedAt()).isEqualTo(STARTED_AT.plusSeconds(1));
            assertThat(found.getLastCollectionRecord().getId()).isEqualTo(successful.getId());
            assertThat(found.getRequestHash()).isEqualTo(request.requestHash());
        });
    }

    @Test
    void 일괄_복구도_오래된_실행만_마감하고_중지_요청과_최신_실행의_기록은_유지한다() {
        UUID firstId = execution(STARTED_AT);
        UUID secondId = execution(STARTED_AT.plusSeconds(1));
        UUID freshId = execution(RECOVERED_AT.minusSeconds(30));
        var first = record(firstId, STARTED_AT);
        var second = record(secondId, STARTED_AT.plusSeconds(1));
        var fresh = record(freshId, RECOVERED_AT.minusSeconds(30));
        states.requestStop(freshId);
        entityManager.clear();
        assertThat(records.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);

        assertThat(states.recoverInterruptedBefore(cutoff(), RECOVERED_AT, RECOVERY_REASON)).isGreaterThanOrEqualTo(2);
        entityManager.clear();

        assertThat(records.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        assertThat(records.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        assertThat(records.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
        assertThat(states.recoverInterrupted(freshId, cutoff(), RECOVERED_AT, RECOVERY_REASON)).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 복구_전의_오래된_기록은_뒤늦은_성공으로_덮어쓸_수_없다() {
        UUID executionId = execution(STARTED_AT);
        var stale = record(executionId, STARTED_AT);
        try {
            assertThat(states.recoverInterrupted(executionId, cutoff(), RECOVERED_AT, RECOVERY_REASON)).isTrue();
            stale.complete(RECOVERED_AT.plusSeconds(1), 1);

            assertThatThrownBy(() -> records.saveAndFlush(stale))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(records.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        } finally {
            records.deleteById(stale.getId());
            executions.delete(executions.findByExecutionId(executionId).orElseThrow());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 수집_기록의_마감이_실패하면_실행_복구도_함께_롤백한다() {
        UUID executionId = execution(STARTED_AT);
        var running = record(executionId, RECOVERED_AT.plusSeconds(1));
        try {
            assertThatThrownBy(() -> states.recoverInterrupted(
                    executionId, cutoff(), RECOVERED_AT, RECOVERY_REASON)).isInstanceOf(IllegalArgumentException.class);
            assertThat(executions.findByExecutionId(executionId).orElseThrow().getStatus())
                    .isEqualTo(DataPipelineExecutionStatus.RUNNING);
            assertThat(records.findById(running.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
        } finally {
            records.deleteById(running.getId());
            executions.delete(executions.findByExecutionId(executionId).orElseThrow());
        }
    }

    private UUID execution(Instant startedAt) {
        UUID id = UUID.randomUUID();
        states.create(id, DataPipelineType.ANNOUNCEMENT_COLLECTION, startedAt);
        return id;
    }

    private SourceCollectionRecord record(UUID executionId, Instant startedAt) {
        return records.saveAndFlush(SourceCollectionRecord.start(UUID.randomUUID(), executionId,
                CollectionSource.LH_ANNOUNCEMENT_DETAIL, Map.of("PAN_ID", "preserved-pan"), startedAt));
    }

    private Instant cutoff() {
        return RECOVERED_AT.minusSeconds(120);
    }

    private DataPipelineExecutionService queryService(DataPipelineExecutionLock lock) {
        return new DataPipelineExecutionService(mock(DataPipelineRunner.class), lock, executions, states,
                Runnable::run, mock(ScheduledExecutorService.class), Clock.fixed(RECOVERED_AT, ZoneOffset.UTC),
                new DataPipelineExecutionMapper(JsonMapper.builder().build()));
    }
}
