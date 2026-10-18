package com.toadzip.backend.ingest.collection.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.global.persistence.PostgresAdvisoryLock;
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
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMapper;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionStateService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineRunner;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

        var history = queryService(unlockedPipelineLock()).history(0, 100);
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
        when(lock.tryAcquire()).thenReturn(Optional.empty());
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
                assertThat(queryService(unlockedPipelineLock()).find(id).status())
                        .isEqualTo(DataPipelineExecutionStatus.FAILED);
                assertThat(records.findById(recordId).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
            } finally {
                jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_failed_record");
            }

            var service = queryService(unlockedPipelineLock());
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

    @ParameterizedTest
    @EnumSource(CollectionSource.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 파이프라인에_연결되지_않은_개별_수집의_중단_이력을_복구한다(CollectionSource source) {
        var withoutId = records.saveAndFlush(SourceCollectionRecord.start(
                UUID.randomUUID(), null, source, Map.of(), STARTED_AT));
        var withoutExecution = records.saveAndFlush(SourceCollectionRecord.start(
                UUID.randomUUID(), UUID.randomUUID(), source, Map.of(), STARTED_AT));
        try {
            queryService(new DataPipelineExecutionLock(dataSource)).history(0, 100);

            assertThat(records.findById(withoutId.getId()).orElseThrow().getStatus())
                    .isEqualTo(CollectionStatus.FAILED);
            assertThat(records.findById(withoutExecution.getId()).orElseThrow().getStatus())
                    .isEqualTo(CollectionStatus.FAILED);
        } finally {
            records.deleteById(withoutId.getId());
            records.deleteById(withoutExecution.getId());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "MYHOME_COMPLEX_COLLECTION, MYHOME_COMPLEX",
            "MYHOME_ANNOUNCEMENT_COLLECTION, MYHOME_ANNOUNCEMENT",
            "LH_LEASE_CATALOG_COLLECTION, LH_LEASE_CATALOG",
            "LH_ANNOUNCEMENT_COLLECTION, LH_ANNOUNCEMENT_CATALOG",
            "LH_ANNOUNCEMENT_COLLECTION, LH_ANNOUNCEMENT_SUPPLY",
            "LH_ANNOUNCEMENT_COLLECTION, LH_ANNOUNCEMENT_DETAIL"
    })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 작업별_잠금이_유지되는_동안_정상_수집은_보존하고_다른_작업만_복구한다(
            IngestOperationLock.Operation operation, CollectionSource source
    ) {
        var active = records.saveAndFlush(SourceCollectionRecord.start(UUID.randomUUID(), null,
                source, Map.of(), STARTED_AT));
        CollectionSource other = CollectionSource.MYHOME_COMPLEX;
        if (source == CollectionSource.MYHOME_COMPLEX) {
            other = CollectionSource.LH_ANNOUNCEMENT_DETAIL;
        }
        var interrupted = records.saveAndFlush(SourceCollectionRecord.start(UUID.randomUUID(), null,
                other, Map.of(), STARTED_AT));
        var operationLock = new IngestOperationLock(dataSource);
        var globalLock = new DataPipelineExecutionLock(dataSource);
        var service = queryService(globalLock);
        try {
            assertThat(operationLock.tryRun(operation, () -> {
                service.history(0, 100);
                assertThat(records.findById(active.getId()).orElseThrow().getStatus())
                        .isEqualTo(CollectionStatus.RUNNING);
                assertThat(records.findById(interrupted.getId()).orElseThrow().getStatus())
                        .isEqualTo(CollectionStatus.FAILED);
                return true;
            })).contains(true);

            service.history(0, 100);
            assertThat(records.findById(active.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        } finally {
            records.deleteById(active.getId());
            records.deleteById(interrupted.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 개별_수집_복구는_기존_성공_원천과_성공_실패_이력을_보존한다() {
        var orphan = record(null, STARTED_AT);
        var successful = record(null, STARTED_AT);
        successful.complete(STARTED_AT.plusSeconds(1), 1);
        records.saveAndFlush(successful);
        var failed = record(null, STARTED_AT);
        failed.fail(STARTED_AT.plusSeconds(1), "ExternalError", "기존 외부 응답 실패");
        records.saveAndFlush(failed);
        var query = new LhAnnouncementQuery("preserved-orphan-pan", "03", "06", "07", "062");
        var source = LhAnnouncementQuerySource.create(CollectionSource.LH_ANNOUNCEMENT_DETAIL, query);
        source.replace(CollectionSource.LH_ANNOUNCEMENT_DETAIL, query, "preserved-hash", "기존 성공 원천",
                Map.of("PAN_ID", "preserved-orphan-pan"), STARTED_AT.plusSeconds(1), false, successful);
        sources.saveAndFlush(source);
        jdbc.update("""
                INSERT INTO lh_announcement_detail_rows
                    (source_id, source_order, collected_at, dataset_type, complex_name, address)
                VALUES (?, 1, ?, 'COMPLEX', '기존 성공 단지', '서울특별시 강남구 테스트로 1')
                """, source.getId(), Timestamp.from(STARTED_AT.plusSeconds(1)));
        var previousRow = jdbc.queryForMap("SELECT * FROM lh_announcement_detail_rows WHERE source_id = ?",
                source.getId());
        try {
            var service = queryService(new DataPipelineExecutionLock(dataSource));
            service.history(0, 100);
            service.history(0, 100);

            assertThat(records.findById(orphan.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
            assertThat(records.findById(successful.getId()).orElseThrow()).satisfies(found -> {
                assertThat(found.getStatus()).isEqualTo(CollectionStatus.SUCCESS);
                assertThat(found.getStoredRowCount()).isEqualTo(1);
                assertThat(found.getFinishedAt()).isEqualTo(STARTED_AT.plusSeconds(1));
            });
            assertThat(records.findById(failed.getId()).orElseThrow()).satisfies(found -> {
                assertThat(found.getStatus()).isEqualTo(CollectionStatus.FAILED);
                assertThat(found.getFailureReason()).isEqualTo("기존 외부 응답 실패");
            });
            assertThat(sources.findById(source.getId()).orElseThrow()).satisfies(found -> {
                assertThat(found.getCollectedAt()).isEqualTo(STARTED_AT.plusSeconds(1));
                assertThat(found.getLastCollectionRecord().getId()).isEqualTo(successful.getId());
                assertThat(found.getRequestHash()).isEqualTo("preserved-hash");
                assertThat(found.isVerifiedEmpty()).isFalse();
            });
            assertThat(jdbc.queryForMap("SELECT * FROM lh_announcement_detail_rows WHERE source_id = ?",
                    source.getId())).isEqualTo(previousRow);
        } finally {
            jdbc.update("DELETE FROM lh_announcement_detail_rows WHERE source_id = ?", source.getId());
            sources.deleteById(source.getId());
            records.deleteById(orphan.getId());
            records.deleteById(successful.getId());
            records.deleteById(failed.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 파이프라인_실행_중에는_개별_수집_복구를_다음_조회로_미룬다() {
        var orphan = record(null, STARTED_AT);
        var globalLock = new DataPipelineExecutionLock(dataSource);
        try {
            try (var ignored = globalLock.tryAcquire().orElseThrow()) {
                queryService(globalLock).history(0, 100);

                assertThat(records.findById(orphan.getId()).orElseThrow().getStatus())
                        .isEqualTo(CollectionStatus.RUNNING);
            }
            queryService(globalLock).history(0, 100);
            assertThat(records.findById(orphan.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        } finally {
            records.deleteById(orphan.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 파이프라인에_연결된_진행_중_수집은_개별_수집_복구에서_제외한다() {
        UUID id = execution(RECOVERED_AT);
        var linked = record(id, RECOVERED_AT);
        var orphan = record(null, STARTED_AT);
        try {
            queryService(new DataPipelineExecutionLock(dataSource)).history(0, 100);

            assertThat(records.findById(orphan.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
            assertThat(records.findById(linked.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
        } finally {
            records.deleteById(linked.getId());
            records.deleteById(orphan.getId());
            executions.delete(executions.findByExecutionId(id).orElseThrow());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 이력을_조회하지_않아도_새_파이프라인_시작_전에_개별_수집을_복구한다() {
        var orphan = record(null, STARTED_AT);
        UUID id = null;
        try {
            var result = queryService(new DataPipelineExecutionLock(dataSource))
                    .start(DataPipelineType.COMPLEX_REFINEMENT);
            id = result.executionId();

            assertThat(records.findById(orphan.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        } finally {
            records.deleteById(orphan.getId());
            if (id != null) {
                executions.delete(executions.findByExecutionId(id).orElseThrow());
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 개별_수집_마감_DB_오류는_같은_작업의_복구를_롤백하고_다음_조회에서_재시도한다() {
        var first = record(null, STARTED_AT);
        var second = record(null, STARTED_AT);
        try {
            jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_orphan_recovery "
                    + "CHECK (id <> '" + second.getId() + "'::uuid OR status <> 'FAILED')");
            try {
                queryService(new DataPipelineExecutionLock(dataSource)).history(0, 100);

                assertThat(records.findById(first.getId()).orElseThrow().getStatus())
                        .isEqualTo(CollectionStatus.RUNNING);
                assertThat(records.findById(second.getId()).orElseThrow().getStatus())
                        .isEqualTo(CollectionStatus.RUNNING);
            } finally {
                jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_orphan_recovery");
            }
            queryService(new DataPipelineExecutionLock(dataSource)).history(0, 100);
            assertThat(records.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
            assertThat(records.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.FAILED);
        } finally {
            records.deleteById(first.getId());
            records.deleteById(second.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 커밋_직전에_작업별_잠금_소유권을_잃으면_개별_수집_복구를_롤백한다() throws Exception {
        var orphan = records.saveAndFlush(SourceCollectionRecord.start(UUID.randomUUID(), null,
                CollectionSource.MYHOME_COMPLEX, Map.of(), STARTED_AT));
        var operationLock = mock(IngestOperationLock.class);
        var lease = mock(PostgresAdvisoryLock.Lease.class);
        doNothing().doThrow(new SQLException("잠금 세션 종료")).when(lease).verifyHeld();
        doAnswer(invocation -> {
            Function<PostgresAdvisoryLock.Lease, Integer> action = invocation.getArgument(1);
            return java.util.Optional.of(action.apply(lease));
        }).when(operationLock).tryRunWithLease(eq(IngestOperationLock.Operation.MYHOME_COMPLEX_COLLECTION), any());
        var recovery = new SourceCollectionRecoveryService(operationLock, records,
                transactionManager, Clock.fixed(RECOVERED_AT, ZoneOffset.UTC));
        try {
            recovery.recoverUnlinkedCollections();

            assertThat(records.findById(orphan.getId()).orElseThrow().getStatus()).isEqualTo(CollectionStatus.RUNNING);
        } finally {
            records.deleteById(orphan.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 실행_중인_파이프라인의_상태_조회는_정상_수집의_작업_잠금을_선점하지_않는다() throws Exception {
        var globalLock = new DataPipelineExecutionLock(dataSource);
        var operationLock = new IngestOperationLock(dataSource);
        var recoveryStarted = new CompletableFuture<Void>();
        var releaseRecovery = new CountDownLatch(1);
        var recovery = blockingRecovery(operationLock, recoveryStarted, releaseRecovery);
        var service = queryService(globalLock, recovery);
        try (var executor = Executors.newSingleThreadExecutor(); var ignored = globalLock.tryAcquire().orElseThrow()) {
            var history = CompletableFuture.supplyAsync(() -> service.history(0, 100), executor);
            try {
                CompletableFuture.anyOf(history, recoveryStarted).get(5, TimeUnit.SECONDS);

                assertThat(operationLock.tryRun(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION,
                        () -> "collected")).contains("collected");
            } finally {
                releaseRecovery.countDown();
                history.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 조회_복구_중에는_다른_인스턴스의_새_파이프라인이_실행_잠금을_얻지_못한다() throws Exception {
        var globalLock = new DataPipelineExecutionLock(dataSource);
        var recoveryStarted = new CompletableFuture<Void>();
        var releaseRecovery = new CountDownLatch(1);
        var recovery = blockingRecovery(new IngestOperationLock(dataSource), recoveryStarted, releaseRecovery);
        var service = queryService(globalLock, recovery);
        Optional<DataPipelineExecutionLock.Lease> competingLease = Optional.empty();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var history = CompletableFuture.supplyAsync(() -> service.history(0, 100), executor);
            try {
                recoveryStarted.get(5, TimeUnit.SECONDS);
                competingLease = new DataPipelineExecutionLock(dataSource).tryAcquire();

                assertThat(competingLease).isEmpty();
            } finally {
                competingLease.ifPresent(DataPipelineExecutionLock.Lease::close);
                releaseRecovery.countDown();
                history.get(5, TimeUnit.SECONDS);
            }
        }
        try (var ignored = globalLock.tryAcquire().orElseThrow()) {
            assertThat(globalLock.isHeld()).isTrue();
        }
    }

    private SourceCollectionRecoveryService blockingRecovery(
            IngestOperationLock operationLock, CompletableFuture<Void> started, CountDownLatch release
    ) {
        var recovery = mock(SourceCollectionRecoveryService.class);
        doAnswer(invocation -> {
            operationLock.tryRun(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION, () -> {
                started.complete(null);
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("복구 종료 신호가 도착하지 않았습니다.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            });
            return null;
        }).when(recovery).recoverUnlinkedCollections();
        return recovery;
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

    private DataPipelineExecutionLock unlockedPipelineLock() {
        var lock = mock(DataPipelineExecutionLock.class);
        when(lock.tryAcquire()).thenReturn(Optional.of(mock(DataPipelineExecutionLock.Lease.class)));
        return lock;
    }

    private DataPipelineExecutionService queryService(DataPipelineExecutionLock lock) {
        return queryService(lock, new SourceCollectionRecoveryService(new IngestOperationLock(dataSource), records,
                transactionManager, Clock.fixed(RECOVERED_AT, ZoneOffset.UTC)));
    }

    private DataPipelineExecutionService queryService(
            DataPipelineExecutionLock lock, SourceCollectionRecoveryService recovery
    ) {
        var heartbeatExecutor = mock(ScheduledExecutorService.class);
        when(heartbeatExecutor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));
        return new DataPipelineExecutionService(mock(DataPipelineRunner.class), lock, executions, states,
                recovery, Runnable::run, heartbeatExecutor, Clock.fixed(RECOVERED_AT, ZoneOffset.UTC),
                new DataPipelineExecutionMapper(JsonMapper.builder().build()));
    }
}
