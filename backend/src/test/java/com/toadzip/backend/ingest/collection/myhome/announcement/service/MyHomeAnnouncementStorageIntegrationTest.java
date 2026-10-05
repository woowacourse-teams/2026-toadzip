package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementCollectionRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementPageParser;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementRunRepository;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeResponseStatusValidator;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock.Lease;
import com.toadzip.backend.ingest.pipeline.repository.IngestExecutionOwnershipRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMonitor;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionStateService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = MyHomeAnnouncementStorageIntegrationTest.JpaConfiguration.class,
        initializers = MyHomeAnnouncementStorageIntegrationTest.SchemaInitializer.class)
@Import({MyHomeAnnouncementCollectionService.class,
        ExternalDataRetryExecutor.class,
        SourceCollectionRecordService.class, MyHomeAnnouncementStorageService.class,
        MyHomeAnnouncementLifecycleService.class,
        MyHomeAnnouncementRunRepository.class,
        IngestWriteOwnershipGuard.class,
        IngestExecutionOwnershipRepository.class, MyHomeAnnouncementCollector.class,
        MyHomeAnnouncementApiRepository.class, MyHomeAnnouncementPageParser.class, IngestOperationLock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyHomeAnnouncementStorageIntegrationTest {

    private static final String SCHEMA = "collection_storage_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant STARTED_AT = Instant.parse("2026-10-04T00:00:00Z");
    private static final Instant COLLECTED_AT = STARTED_AT.plusSeconds(1);
    private static final Instant FINISHED_AT = STARTED_AT.plusSeconds(30);
    private static final UUID EXECUTION_ID = UUID.fromString("bb0c73c1-1d56-4818-b44b-291d0e01dd31");

    @Autowired
    private MyHomeAnnouncementCollectionService batch;

    @MockitoBean
    private ExternalDataFailureRecorder failures;

    @Autowired
    private MyHomeAnnouncementLifecycleService lifecycle;

    @Autowired
    private MyHomeAnnouncementStorageService service;

    @Autowired
    private SourceCollectionRecordService history;

    @Autowired
    private MyHomeAnnouncementCollector collector;

    @Autowired
    private MockRestServiceServer httpServer;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IngestOperationLock executionLock;

    @Autowired
    private MyHomeAnnouncementCollectionRepository sourceRepository;

    @Autowired
    private SourceCollectionRecordRepository recordRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearStorage() {
        httpServer.reset();
        clock.set(FINISHED_AT);
        jdbc.update("DELETE FROM myhome_announcement_lifecycle_runs");
        jdbc.update("DELETE FROM myhome_announcement_source_rows");
        jdbc.update("DELETE FROM myhome_announcement_source_bundles");
        jdbc.update("DELETE FROM source_collection_record_parameters");
        jdbc.update("DELETE FROM source_collection_records");
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = NULL, generation = 0 WHERE id = 1");
    }

    @AfterEach
    void verifyHttpRequests() {
        httpServer.verify();
    }

    @AfterAll
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void administratorPartialFailureKeepsSuccessfulSourcesAndDoesNotCountMisses() {
        store(request("01", 500), response(COLLECTED_AT,
                row("old", 1, "기존", "서울특별시"), row("old", 2, "기존", "서울특별시")));
        UUID runId = UUID.randomUUID();
        for (String type : List.of("01", "02", "03", "05", "06", "10", "12")) {
            String url = batchUrl(type, 1);
            if (type.equals("02")) {
                httpServer.expect(requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
                continue;
            }
            String announcementId = "new-" + type;
            if (type.equals("01")) {
                announcementId = "old";
            }
            httpServer.expect(requestTo(url)).andRespond(withSuccess(
                    pageJson(1, row(announcementId, 1, "새 공고", "서울특별시")), MediaType.APPLICATION_JSON));
        }
        org.slf4j.MDC.put("executionId", runId.toString());
        try {
            var result = batch.collect(new com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionRequest(2, 10));
            assertThat(result.storedRowCount()).isEqualTo(6);
            assertThat(result.failedRequestCount()).isOne();
            assertThat(result.externalApiCallCount()).isEqualTo(7);
        } finally {
            org.slf4j.MDC.remove("executionId");
        }
        assertThat(recordRepository.findAllByExecutionIdAndSource(runId, CollectionSource.MYHOME_ANNOUNCEMENT))
                .hasSize(7).filteredOn(record -> record.getStatus() == CollectionStatus.FAILED).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows "
                + "WHERE pblanc_id = 'old' AND house_sn = 2", Integer.class)).isZero();
        assertThat(source("old").rows()).hasSize(2);
        assertThat(sourceRepository.count()).isEqualTo(6);
    }

    @Test
    void administratorRetriesHttpOnlyAndCompletesOneHistoryPerSupplyType() {
        httpServer.expect(requestTo(batchUrl("01", 1))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        httpServer.expect(requestTo(batchUrl("01", 1))).andRespond(withSuccess(pageJson(3,
                row("new", 1, "새 공고", "서울특별시"), row("new", 2, "새 공고", "서울특별시")),
                MediaType.APPLICATION_JSON));
        httpServer.expect(requestTo(batchUrl("01", 2))).andRespond(withSuccess(pageJson(3,
                row("new", 3, "새 공고", "서울특별시")), MediaType.APPLICATION_JSON));
        for (String type : List.of("02", "03", "05", "06", "10", "12")) {
            httpServer.expect(requestTo(batchUrl(type, 1)))
                    .andRespond(withSuccess(pageJson(0), MediaType.APPLICATION_JSON));
        }
        var result = batch.collect(new com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionRequest(2, 10));
        assertThat(result.externalApiCallCount()).isEqualTo(9);
        assertThat(result.storedRowCount()).isEqualTo(3);
        assertThat(result.failedRequestCount()).isZero();
        assertThat(records()).hasSize(7).allMatch(record -> record.status() == CollectionStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM myhome_announcement_lifecycle_runs", Long.class)).isOne();
    }

    private String batchUrl(String type, int page) {
        return "https://example.com/rsdtRcritNtcList?serviceKey=test-key&suplyTy=" + type
                + "&pageNo=" + page + "&numOfRows=2";
    }

    @Test
    void eachCompletedRunCountsOneMissAndTwoMissesDeactivateWithoutDeletingSource() {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        UUID first = completedEmptyRun();

        lifecycle.completeRun(first);
        lifecycle.completeRun(first);

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows", Boolean.class)).isTrue();
        lifecycle.completeRun(completedEmptyRun());

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows", Boolean.class)).isFalse();
        assertThat(sourceRepository.count()).isEqualTo(1);
    }

    @Test
    void completingAnOldRunAgainDoesNotMarkARecollectedRowMissed() {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        UUID oldRun = completedEmptyRun();
        lifecycle.completeRun(oldRun);
        store(request("01", 500), response(FINISHED_AT, row("20935", 1, "재조회", "서울특별시")));

        lifecycle.completeRun(oldRun);

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isZero();
    }

    @Test
    void anUnappliedOlderRunDoesNotMarkNewerCollectedRowsMissed() {
        UUID oldRun = completedEmptyRun();
        clock.set(FINISHED_AT.plusSeconds(1));
        store(request("01", 500), response(clock.instant(), row("20935", 1, "새 응답", "서울특별시")));

        lifecycle.completeRun(oldRun);

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isZero();
    }

    @Test
    void concurrentCompletedRunsCountBothMisses() throws Exception {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        UUID first = completedEmptyRun();
        UUID second = completedEmptyRun();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(() -> lifecycle.completeRun(first));
            var secondResult = executor.submit(() -> lifecycle.completeRun(second));
            firstResult.get(10, java.util.concurrent.TimeUnit.SECONDS);
            secondResult.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows", Boolean.class)).isFalse();
    }

    @Test
    void failedSupplyTypePreventsRunWideMissJudgment() {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        UUID runId = completedEmptyRun();
        jdbc.update("UPDATE source_collection_records SET status = 'FAILED', stored_row_count = 0, "
                + "error_type = 'test', failure_reason = '실패' WHERE execution_id = ? "
                + "AND id IN (SELECT record_id FROM source_collection_record_parameters "
                + "WHERE parameter_name = 'suplyTy' AND parameter_value = '12')", runId);

        assertThatThrownBy(() -> lifecycle.completeRun(runId)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isZero();
    }

    @Test
    void collectedInactiveAnnouncementReactivatesAndResetsMissCount() {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        lifecycle.completeRun(completedEmptyRun());
        lifecycle.completeRun(completedEmptyRun());

        store(request("01", 500), response(COLLECTED_AT.plusSeconds(1), row("20935", 1, "재조회", "서울특별시")));

        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows",
                Integer.class)).isZero();
    }

    @Test
    void importedUnknownActualTimeIsPreservedUntilARealResponseIsCollected() {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        jdbc.update("UPDATE myhome_announcement_source_rows SET collected_at = NULL");
        jdbc.update("UPDATE source_collection_records SET status = 'IMPORTED'");
        assertThat(source("20935").rows().getFirst().collectedAt()).isNull();
        assertThat(records()).singleElement().satisfies(record ->
                assertThat(record.status()).isEqualTo(CollectionStatus.IMPORTED));

        store(request("01", 500), response(COLLECTED_AT.plusSeconds(1), row("20935", 1, "새 응답", "서울특별시")));

        assertThat(source("20935").rows()).singleElement().satisfies(row ->
                assertThat(row.collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(1)));
    }

    private UUID completedEmptyRun() {
        return completedRun();
    }

    private UUID completedRun(MyHomeAnnouncementSourceSnapshot... observedRows) {
        UUID runId = UUID.randomUUID();
        for (String type : List.of("01", "02", "03", "05", "06", "10", "12")) {
            var request = new MyHomeAnnouncementCollectionRequest(runId, type, 500, 1_000, clock.instant());
            MyHomeAnnouncementCollectedResponse collected = response(clock.instant());
            if (type.equals("01")) {
                collected = response(clock.instant(), observedRows);
            }
            store(request, collected);
        }
        return runId;
    }

    @Test
    void missingHouseIsDeactivatedOnlyAfterTwoCompleteRunsAndReactivatedWhenSeen() {
        MyHomeAnnouncementSourceSnapshot first = row("20935", 1, "기존", "서울특별시");
        MyHomeAnnouncementSourceSnapshot missing = row("20935", 2, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, first, missing));

        lifecycle.completeRun(completedRun(first));

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows "
                + "WHERE house_sn = 2", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows "
                + "WHERE house_sn = 2", Boolean.class)).isTrue();
        clock.set(FINISHED_AT.plusSeconds(1));
        lifecycle.completeRun(completedRun(first));

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows "
                + "WHERE house_sn = 2", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows "
                + "WHERE house_sn = 2", Boolean.class)).isFalse();
        clock.set(FINISHED_AT.plusSeconds(2));
        lifecycle.completeRun(completedRun(first, missing));

        assertThat(jdbc.queryForObject("SELECT consecutive_miss_count FROM myhome_announcement_source_rows "
                + "WHERE house_sn = 2", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT active FROM myhome_announcement_source_rows "
                + "WHERE house_sn = 2", Boolean.class)).isTrue();
        assertThat(source("20935").rows()).hasSize(2);
    }

    @Test
    void storesAllResponseRowsAndRequestMetadata() {
        MyHomeAnnouncementSourceSnapshot first = row("20935", 0, "공고", "서울특별시");
        MyHomeAnnouncementSourceSnapshot second = row("20935", 0, "공고", "부산광역시");

        UUID recordId = store(request("01", 500), response(COLLECTED_AT, first, second));

        assertThat(sourceRepository.count()).isOne();
        SourceView source = source("20935");
        assertThat(source.rows()).extracting(RowView::snapshot).containsExactly(first, second);
        assertThat(source.rows()).allSatisfy(value -> {
            assertThat(value.collectedAt()).isEqualTo(COLLECTED_AT);
            assertThat(value.supplyTypeCode()).isEqualTo("01");
            assertThat(value.recordId()).isEqualTo(recordId);
            assertThat(value.parameters()).containsEntry("suplyTy", "01");
        });
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo(recordId);
            assertThat(record.executionId()).isEqualTo(EXECUTION_ID);
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isEqualTo(2);
            assertThat(record.startedAt()).isEqualTo(STARTED_AT);
            assertThat(record.finishedAt()).isEqualTo(FINISHED_AT);
            assertThat(record.parameters()).containsExactlyInAnyOrderEntriesOf(request("01", 500).parameters());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void replacesObservedHouseWithoutDeletingUnseenHouseOrItsCollectionMetadata(int observedHouse) {
        store(request("01", 500), response(COLLECTED_AT,
                row("20935", 1, "기존", "서울특별시"), row("20935", 2, "기존", "서울특별시")));
        SourceView original = source("20935");
        RowView unseen = original.rows().stream()
                .filter(value -> value.snapshot().houseSn() != observedHouse).findFirst().orElseThrow();
        MyHomeAnnouncementSourceSnapshot changed = row("20935", observedHouse, "변경", "서울특별시");

        store(request("01", 10), response(COLLECTED_AT.plusSeconds(1), changed));

        SourceView updated = source("20935");
        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.rows()).contains(unseen);
        assertThat(updated.rows()).extracting(RowView::snapshot).containsExactlyInAnyOrder(unseen.snapshot(), changed);
        assertThat(updated.rows()).filteredOn(value -> value.snapshot().equals(changed)).singleElement()
                .satisfies(value -> {
                    assertThat(value.collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(1));
                    assertThat(value.parameters()).containsEntry("numOfRows", "10");
                });
        assertThat(records()).hasSize(2).allMatch(record -> record.status() == CollectionStatus.SUCCESS);
    }

    @Test
    void retainsEveryObservedResponseRowForTheSameHouseWhilePreservingUnseenHouses() {
        MyHomeAnnouncementSourceSnapshot unseen = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT,
                row("20935", 0, "기존", "서울특별시"), row("20935", 0, "기존", "부산광역시"), unseen));
        MyHomeAnnouncementSourceSnapshot seoul = row("20935", 0, "변경", "서울특별시");
        MyHomeAnnouncementSourceSnapshot busan = row("20935", 0, "변경", "부산광역시");

        store(request("01", 500), response(COLLECTED_AT.plusSeconds(1), seoul, busan));

        assertThat(source("20935").rows()).extracting(RowView::snapshot)
                .containsExactly(unseen, seoul, busan);
    }

    @Test
    void preservesOtherSupplyTypeRowsAndTheirCollectionTimes() {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "A", "서울특별시")));
        MyHomeAnnouncementSourceSnapshot otherType = row("20935", 1, "B", "부산광역시");
        store(request("02", 500), response(COLLECTED_AT.plusSeconds(1), otherType));
        MyHomeAnnouncementSourceSnapshot changed = row("20935", 1, "A 변경", "서울특별시");

        store(request("01", 10), response(COLLECTED_AT.plusSeconds(2), changed));

        assertThat(source("20935").rows()).hasSize(2).anySatisfy(value -> {
            assertThat(value.snapshot()).isEqualTo(otherType);
            assertThat(value.collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(1));
            assertThat(value.parameters()).containsEntry("suplyTy", "02").containsEntry("numOfRows", "500");
        }).anySatisfy(value -> {
            assertThat(value.snapshot()).isEqualTo(changed);
            assertThat(value.collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(2));
        });
    }

    @Test
    void recordsValidationFailureWithoutChangingPreviousSource() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        MyHomeAnnouncementCollectedResponse incomplete = new MyHomeAnnouncementCollectedResponse(
                2, COLLECTED_AT.plusSeconds(1), List.of(row("20935", 1, "불완전", "서울특별시")));

        assertThatThrownBy(() -> store(request("01", 500), incomplete))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("전체 응답 건수");

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).hasSize(2).filteredOn(record -> record.status() == CollectionStatus.FAILED)
                .singleElement().satisfies(record -> {
                    assertThat(record.rowCount()).isZero();
                    assertThat(record.failureReason()).contains("전체 응답 건수");
                });
    }

    @Test
    void rollsBackReplacementAndNewParentsWhenRowPersistenceFails() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        jdbc.execute("ALTER TABLE myhome_announcement_source_rows ADD CONSTRAINT test_reject_row "
                + "CHECK (pblanc_nm <> 'DB_FAIL')");
        try {
            assertThatThrownBy(() -> store(request("01", 500), response(COLLECTED_AT.plusSeconds(1),
                    row("20935", 2, "DB_FAIL", "서울특별시"), row("21344", 1, "신규", "부산광역시"))))
                    .isInstanceOf(RuntimeException.class);

            assertThat(sourceRepository.count()).isOne();
            assertThat(source("20935").rows()).singleElement().satisfies(value -> {
                assertThat(value.snapshot()).isEqualTo(original);
                assertThat(value.collectedAt()).isEqualTo(COLLECTED_AT);
            });
            assertThat(records()).hasSize(2).filteredOn(record -> record.status() == CollectionStatus.FAILED)
                    .singleElement().extracting(RecordView::rowCount).isEqualTo(0);
        } finally {
            jdbc.execute("ALTER TABLE myhome_announcement_source_rows DROP CONSTRAINT test_reject_row");
        }
    }

    @Test
    void doesNotPromoteSourceWhenSuccessRecordCannotBeStored() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_success "
                + "CHECK (status <> 'SUCCESS' OR stored_row_count <> 2)");
        try {
            assertThatThrownBy(() -> store(request("01", 500), response(COLLECTED_AT.plusSeconds(1),
                    row("20935", 1, "변경", "서울특별시"), row("20935", 2, "변경", "서울특별시"))))
                    .isInstanceOf(RuntimeException.class);

            assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
            assertThat(records()).hasSize(2).filteredOn(record -> record.status() == CollectionStatus.SUCCESS)
                    .singleElement().extracting(RecordView::rowCount).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_success");
        }
    }

    @Test
    void acceptsVerifiedEmptyResponseWithoutDeletingPreviousAnnouncements() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));

        store(request("01", 500), response(COLLECTED_AT.plusSeconds(1)));

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).hasSize(2).anyMatch(record -> record.rowCount() == 0
                && record.status() == CollectionStatus.SUCCESS);
    }

    @Test
    void refreshesActualCollectionTimeEvenWhenResponseValuesAreUnchanged() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "동일", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));

        store(request("01", 500), response(COLLECTED_AT.plusSeconds(1), original));

        assertThat(source("20935").rows()).singleElement()
                .extracting(RowView::collectedAt).isEqualTo(COLLECTED_AT.plusSeconds(1));
        assertThat(records()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void rejectsOlderResponseWithoutOverwritingNewerSource(int houseSn) {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "최신", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT.plusSeconds(1), original));

        assertThatThrownBy(() -> store(request("01", 500), response(COLLECTED_AT,
                row("20935", houseSn, "과거", "서울특별시"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("오래된 수집 응답");

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).hasSize(2).anyMatch(record -> record.status() == CollectionStatus.FAILED);
    }

    @Test
    void storesSourceWhenExecutionOwnershipIsHeld() {
        Lease lease = lease(1);
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 1 WHERE id = 1", EXECUTION_ID);
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "공고", "서울특별시");

        try (var ignored = IngestExecutionScope.open(lease)) {
            store(request("01", 500), response(COLLECTED_AT, original));
        }

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @Test
    void rejectsPreviousExecutionGenerationWithoutUpdatingSource() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        Lease lease = lease(1);
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 2 WHERE id = 1", EXECUTION_ID);

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> store(request("01", 500), response(COLLECTED_AT.plusSeconds(1),
                    row("20935", 1, "변경", "서울특별시"))))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).hasSize(2).anyMatch(record -> record.status() == CollectionStatus.FAILED);
    }

    @Test
    void rollsBackSourceWhenOwnershipIsLostBeforeCommit() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        Lease lease = lease(1);
        doNothing().doThrow(new IngestOwnershipLostException()).when(lease).verifyHeld();
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 1 WHERE id = 1", EXECUTION_ID);

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> store(request("01", 500), response(COLLECTED_AT.plusSeconds(1),
                    row("20935", 1, "변경", "서울특별시"))))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).hasSize(2).filteredOn(record -> record.status() == CollectionStatus.SUCCESS)
                .singleElement().extracting(RecordView::rowCount).isEqualTo(1);
    }

    @Test
    void cannotOverwriteSuccessByInsertingFailureWithTheSameRecordId() {
        UUID recordId = store(request("01", 500), response(COLLECTED_AT,
                row("20935", 1, "공고", "서울특별시")));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            SourceCollectionRecord failure = SourceCollectionRecord.start(recordId, EXECUTION_ID,
                    CollectionSource.MYHOME_ANNOUNCEMENT, request("01", 500).parameters(), STARTED_AT);
            failure.fail(FINISHED_AT, "PersistenceFailure", "실패 기록 시도");
            recordRepository.saveAndFlush(failure);
        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @Test
    void commitsRunningRecordBeforeHttpAndCompletesSameRecordAfterAllPages() {
        AtomicReference<UUID> startedId = new AtomicReference<>();
        MyHomeAnnouncementSourceSnapshot first = row("20935", 0, "공고", "서울특별시");
        MyHomeAnnouncementSourceSnapshot second = row("20935", 0, "공고", "부산광역시");
        MyHomeAnnouncementSourceSnapshot third = row("21344", 1, "다른 공고", "서울특별시");
        httpServer.expect(requestTo(apiUrl(1))).andRespond(httpRequest -> {
            assertThat(records()).singleElement().satisfies(record -> {
                assertThat(record.status()).isEqualTo(CollectionStatus.RUNNING);
                assertThat(record.startedAt()).isEqualTo(FINISHED_AT);
                assertThat(record.finishedAt()).isNull();
                startedId.set(record.id());
            });
            clock.set(FINISHED_AT.plusSeconds(1));
            return withSuccess(pageJson(3, first, second), MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });
        httpServer.expect(requestTo(apiUrl(2))).andRespond(httpRequest -> {
            assertThat(sourceRepository.count()).isZero();
            clock.set(FINISHED_AT.plusSeconds(2));
            return withSuccess(pageJson(3, third), MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });

        UUID recordId = collector.collect(request("01", 2));

        assertThat(recordId).isEqualTo(startedId.get());
        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(first, second);
        assertThat(source("21344").rows()).singleElement().satisfies(value -> {
            assertThat(value.snapshot()).isEqualTo(third);
            assertThat(value.collectedAt()).isEqualTo(FINISHED_AT.plusSeconds(2));
        });
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isEqualTo(3);
            assertThat(record.finishedAt()).isEqualTo(FINISHED_AT.plusSeconds(2));
        });
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "TOO_MANY_REQUESTS"})
    void recordsHttpFailureWithoutStoringPartialPagesOrRetrying(HttpStatus status) {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        expectPage(1, pageJson(3, row("20935", 2, "변경", "서울특별시"),
                row("21344", 1, "신규", "부산광역시")));
        httpServer.expect(requestTo(apiUrl(2))).andRespond(withStatus(status));

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, failure ->
                        assertThat(failure.isRateLimited()).isEqualTo(status == HttpStatus.TOO_MANY_REQUESTS));

        assertThat(sourceRepository.count()).isOne();
        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertFailedAttempt("HTTP " + status.value());
        assertFailedAttempt("pageNo=2");
    }

    @Test
    void rejectsChangedTotalCountWithoutSavingTheBuffer() {
        expectPage(1, pageJson(3, row("20935", 1, "공고", "서울특별시"),
                row("20935", 2, "공고", "서울특별시")));
        expectPage(2, pageJson(4, row("20935", 3, "공고", "서울특별시")));

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 간 전체 건수");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("페이지 간 전체 건수");
    }

    @Test
    void rejectsEmptyPageBeforeTheAdvertisedTotalIsReached() {
        expectPage(1, pageJson(3, row("20935", 1, "공고", "서울특별시"),
                row("20935", 2, "공고", "서울특별시")));
        expectPage(2, pageJson(3));

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("빈 페이지");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("빈 페이지");
    }

    @Test
    void rejectsNoDataResponseAfterCollectingRows() {
        expectPage(1, pageJson(3, row("20935", 1, "공고", "서울특별시"),
                row("20935", 2, "공고", "서울특별시")));
        expectPage(2, "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}");

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("전체 건수");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("전체 건수");
    }

    @Test
    void rejectsIncompleteCollectionAtTheMaximumPage() {
        expectPage(1, pageJson(3, row("20935", 1, "공고", "서울특별시"),
                row("20935", 2, "공고", "서울특별시")));
        var limited = new MyHomeAnnouncementCollectionRequest(EXECUTION_ID, "01", 2, 1, STARTED_AT);

        assertThatThrownBy(() -> collector.collect(limited))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최대 페이지");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("최대 페이지");
    }

    @Test
    void continuesAfterAShortPageUntilAllRowsAreCollected() {
        expectPage(1, pageJson(2, row("20935", 1, "공고", "서울특별시")));
        expectPage(2, """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":"2",
                "item":{"pblancId":"20935","houseSn":2,"pblancNm":"공고"}}}}
                """);

        collector.collect(request("01", 2));

        assertThat(source("20935").rows()).hasSize(2);
        assertThat(records()).singleElement().extracting(RecordView::rowCount).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{invalid json",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"item\":[]}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,\"item\":[1]}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,\"item\":[{}]}}}"
    })
    void recordsMalformedResponsesAsFailedAttempts(String payload) {
        expectPage(1, payload);

        assertThatThrownBy(() -> collector.collect(request("01", 2))).isInstanceOf(RuntimeException.class);

        assertThat(sourceRepository.count()).isZero();
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"houseSn", "sumSuplyCo", "rentGtn", "enty", "surlus", "mtRntchrg"})
    void rejectsFractionalIntegerFieldsAndKeepsPreviousSource(String field) {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        SourceView previous = source("20935");
        String payload = pageJson(1, original).replaceAll(
                "\"" + field + "\":\\d+", "\"" + field + "\":100.9");
        expectPage(1, payload);

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("응답 행 형식");

        assertThat(source("20935")).isEqualTo(previous);
        assertFailedAttempt("응답 행 형식");
    }

    @Test
    void acceptsIntegerStringsWithoutChangingResponseValues() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "공고", "서울특별시");
        String payload = pageJson(1, original)
                .replace("\"houseSn\":1", "\"houseSn\":\"1\"")
                .replace("\"sumSuplyCo\":10", "\"sumSuplyCo\":\"10\"")
                .replace("\"rentGtn\":9000000", "\"rentGtn\":\"9000000\"");
        expectPage(1, payload);

        collector.collect(request("01", 2));

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":\"corrupt\"}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":[]}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":{\"totalCount\":1}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":{\"item\":{\"pblancId\":\"20935\"}}}}"
    })
    void rejectsMalformedNoDataResponsesAndKeepsPreviousSource(String payload) {
        store(request("01", 500), response(COLLECTED_AT, row("20935", 1, "기존", "서울특별시")));
        SourceView previous = source("20935");
        expectPage(1, payload);

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("구조");

        assertThat(source("20935")).isEqualTo(previous);
        assertFailedAttempt("pageNo=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":null}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":{\"totalCount\":\"0\",\"item\":[]}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0}}}"
    })
    void acceptsExplicitEmptyCollectionAndKeepsPreviousSource(String payload) {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        expectPage(1, payload);

        UUID recordId = collector.collect(request("01", 2));

        assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
        assertThat(records()).filteredOn(record -> record.id().equals(recordId)).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isZero();
        });
    }

    @Test
    void marksTheStartedRequestFailedWhenDatabaseStorageRollsBack() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        jdbc.execute("ALTER TABLE myhome_announcement_source_rows ADD CONSTRAINT test_http_reject_row "
                + "CHECK (pblanc_nm <> 'DB_FAIL')");
        try {
            expectPage(1, pageJson(1, row("20935", 2, "DB_FAIL", "서울특별시")));

            assertThatThrownBy(() -> collector.collect(request("01", 2))).isInstanceOf(RuntimeException.class);

            assertThat(source("20935").rows()).extracting(RowView::snapshot).containsExactly(original);
            assertFailedAttempt("test_http_reject_row");
        } finally {
            jdbc.execute("ALTER TABLE myhome_announcement_source_rows DROP CONSTRAINT test_http_reject_row");
        }
    }

    @Test
    void keepsOriginalHttpFailureWhenFailureRecordCannotBeStored() {
        MyHomeAnnouncementSourceSnapshot original = row("20935", 1, "기존", "서울특별시");
        store(request("01", 500), response(COLLECTED_AT, original));
        SourceView previous = source("20935");
        jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_failure "
                + "CHECK (status <> 'FAILED')");
        try {
            httpServer.expect(requestTo(apiUrl(1))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatThrownBy(() -> collector.collect(request("01", 2)))
                    .isInstanceOfSatisfying(ExternalDataRequestException.class, failure -> {
                        assertThat(failure.getMessage()).contains("HTTP 500");
                        assertThat(failure.isRetryable()).isTrue();
                        assertThat(failure.getSuppressed()).hasSize(1);
                    });

            assertThat(source("20935")).isEqualTo(previous);
            assertThat(records()).hasSize(2).filteredOn(record -> record.status() == CollectionStatus.RUNNING)
                    .hasSize(1);
        } finally {
            jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_failure");
        }
    }

    @Test
    void rejectsSuccessCompletionWithoutTheSourceTransaction() {
        var request = request("01", 500);
        UUID id = history.start(request);

        assertThatThrownBy(() -> history.complete(id, request, 0))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.RUNNING);
    }

    @Test
    void rejectsRunningRecordLookupWithoutTheSourceTransaction() {
        var request = request("01", 500);
        UUID id = history.start(request);

        assertThatThrownBy(() -> history.requireRunning(id, request))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.RUNNING);
    }

    @Test
    void differentRequestCannotMarkAnotherRequestsRecordFailed() {
        UUID id = history.start(request("01", 500));
        var originalFailure = new IllegalStateException("원래 수집 실패");

        history.fail(id, request("02", 500), originalFailure);

        assertThat(originalFailure.getSuppressed()).singleElement().isInstanceOf(IllegalArgumentException.class);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.RUNNING);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void stopDuringHttpFinishesCompleteResponseAndStopsBeforeNextCall(int totalCount) {
        AtomicBoolean stopRequested = new AtomicBoolean();
        DataPipelineExecutionStateService state = mock(DataPipelineExecutionStateService.class);
        when(state.isStopRequested(EXECUTION_ID)).thenAnswer(invocation -> stopRequested.get());
        var monitor = new DataPipelineExecutionMonitor(EXECUTION_ID, state, clock);
        httpServer.expect(requestTo(apiUrl(1))).andRespond(httpRequest -> {
            stopRequested.set(true);
            return withSuccess(pageJson(totalCount, row("20935", 1, "공고", "서울특별시"),
                    row("20935", 2, "공고", "서울특별시")), MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });

        try (var ignored = IngestExecutionScope.open(null, monitor)) {
            if (totalCount == 2) {
                UUID id = collector.collect(request("01", 2));
                assertThat(history.storedRowCount(id)).isEqualTo(2);
                assertThat(sourceRepository.count()).isOne();
                assertThat(records()).singleElement().extracting(RecordView::status)
                        .isEqualTo(CollectionStatus.SUCCESS);
                return;
            }
            assertThatThrownBy(() -> collector.collect(request("01", 2)))
                    .isInstanceOf(DataPipelineStoppedException.class);
        }
        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("중지");
    }

    @Test
    void marksTheRequestFailedAfterOwnershipIsLostDuringHttp() {
        Lease lease = lease(1);
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 1 WHERE id = 1", EXECUTION_ID);
        httpServer.expect(requestTo(apiUrl(1))).andRespond(httpRequest -> {
            jdbc.update("UPDATE ingest_execution_ownership SET generation = 2 WHERE id = 1");
            return withSuccess(pageJson(1, row("20935", 1, "공고", "서울특별시")),
                    MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> collector.collect(request("01", 2)))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("소유권");
    }

    @Test
    void keepsCompletedSuccessWhenFailureFinalizationIsAttemptedLater() {
        UUID recordId = store(request("01", 500), response(COLLECTED_AT,
                row("20935", 1, "공고", "서울특별시")));

        history.fail(recordId, request("01", 500), new IllegalStateException("뒤늦은 실패"));

        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.failureReason()).isNull();
        });
    }

    @Test
    void cannotCompleteAnotherRequestsRecord() {
        UUID recordId = history.start(request("01", 500));

        assertThatThrownBy(() -> service.complete(recordId, request("02", 500), response(COLLECTED_AT,
                row("20935", 1, "공고", "서울특별시"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("요청 조건");

        assertThat(sourceRepository.count()).isZero();
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.RUNNING);
    }

    @Test
    void completesRequestWithNanosecondClockAtPostgresPrecision() {
        Instant preciseTime = FINISHED_AT.plusNanos(123456789);
        clock.set(preciseTime);
        expectPage(1, pageJson(1, row("20935", 1, "공고", "서울특별시")));

        collector.collect(request("01", 2));

        Instant storedTime = preciseTime.truncatedTo(ChronoUnit.MICROS);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.startedAt()).isEqualTo(storedTime);
            assertThat(record.finishedAt()).isEqualTo(storedTime);
        });
        assertThat(source("20935").rows()).singleElement().extracting(RowView::collectedAt).isEqualTo(storedTime);
    }

    @Test
    void rejectsARepeatedPageEvenIfTheAccumulatedCountMatchesTheTotal() {
        String repeated = pageJson(4, row("20935", 1, "공고", "서울특별시"),
                row("20935", 2, "공고", "서울특별시"));
        expectPage(1, repeated);
        expectPage(2, repeated);

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("반복");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("반복");
    }

    @Test
    void marksNanosecondStartedRequestFailedAfterHttpError() {
        clock.set(FINISHED_AT.plusNanos(123456789));
        httpServer.expect(requestTo(apiUrl(1))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(ExternalDataRequestException.class);

        assertFailedAttempt("HTTP 500");
    }

    @Test
    void rejectsInvalidIdentifiersBeforeCallingTheNextPage() {
        expectPage(1, """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,
                "item":[{"pblancId":"20935"}]}}}
                """);

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("주택 순번");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("주택 순번");
    }

    @Test
    void rejectsRowsExceedingTheAdvertisedTotal() {
        expectPage(1, pageJson(1, row("20935", 1, "공고", "서울특별시"),
                row("20935", 2, "공고", "서울특별시")));

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("전체 건수를 초과");

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("전체 건수를 초과");
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void preservesGatewayRateLimitClassificationWithoutRetrying(String resultCode) {
        expectPage(1, """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"%s"}}}
                """.formatted(resultCode));

        assertThatThrownBy(() -> collector.collect(request("01", 2)))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, failure -> {
                    assertThat(failure.isRateLimited()).isTrue();
                    assertThat(failure.isRetryable()).isEqualTo("23".equals(resultCode));
                });

        assertThat(sourceRepository.count()).isZero();
        assertFailedAttempt("resultCode=" + resultCode);
    }

    @Test
    void rejectsConcurrentCollectionBeforeStartingHttpOrCreatingARecord() {
        executionLock.tryRun(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION, () -> {
            assertThatThrownBy(() -> collector.collect(request("01", 2)))
                    .isInstanceOf(IngestAlreadyRunningException.class);
            return true;
        }).orElseThrow();

        assertThat(sourceRepository.count()).isZero();
        assertThat(records()).isEmpty();
    }

    private String apiUrl(int page) {
        return "https://example.com/rsdtRcritNtcList?serviceKey=test-key&suplyTy=01&pageNo="
                + page + "&numOfRows=2";
    }

    private String pageJson(int totalCount, MyHomeAnnouncementSourceSnapshot... rows) {
        return """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":%d,"item":%s}}}
                """.formatted(totalCount, objectMapper.writeValueAsString(List.of(rows)));
    }

    private void expectPage(int page, String payload) {
        httpServer.expect(requestTo(apiUrl(page))).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
    }

    private void assertFailedAttempt(String reason) {
        assertThat(records()).filteredOn(record -> record.status() == CollectionStatus.FAILED)
                .singleElement().satisfies(record -> {
                    assertThat(record.rowCount()).isZero();
                    assertThat(record.failureReason()).contains(reason);
                    assertThat(record.finishedAt()).isNotNull();
                });
        assertThat(records()).noneMatch(record -> record.status() == CollectionStatus.RUNNING);
    }

    private Lease lease(long generation) {
        Lease lease = mock(Lease.class);
        when(lease.ownerId()).thenReturn(EXECUTION_ID);
        when(lease.generation()).thenReturn(generation);
        return lease;
    }

    private UUID store(MyHomeAnnouncementCollectionRequest request, MyHomeAnnouncementCollectedResponse response) {
        UUID id = history.start(request);
        try {
            service.complete(id, request, response);
        } catch (RuntimeException failure) {
            history.fail(id, request, failure);
            throw failure;
        }
        return id;
    }

    private MyHomeAnnouncementCollectionRequest request(String supplyTypeCode, int pageSize) {
        return new MyHomeAnnouncementCollectionRequest(EXECUTION_ID, supplyTypeCode, pageSize, 1000, STARTED_AT);
    }

    private MyHomeAnnouncementCollectedResponse response(
            Instant collectedAt, MyHomeAnnouncementSourceSnapshot... rows
    ) {
        return new MyHomeAnnouncementCollectedResponse(rows.length, collectedAt, List.of(rows));
    }

    private MyHomeAnnouncementSourceSnapshot row(String pblancId, int houseSn, String name, String region) {
        return new MyHomeAnnouncementSourceSnapshot(
                pblancId, houseSn, "일반공고", name, "LH", "아파트", "국민임대", "이전 공고",
                "20261001", "20261101", "20261005", "20261010", "안내 그대로 ", "https://example.com/notice",
                "https://example.com/pc", "https://example.com/mobile", "단지", region, "시군구", "주소",
                "도로명", "법정동", "PNU", "지역난방", "100", 10, 9000000L, 1000000L, 8000000L, 100000L
        );
    }

    private SourceView source(String pblancId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            MyHomeAnnouncementSource source = sourceRepository.findAllByPblancIdIn(List.of(pblancId)).getFirst();
            List<RowView> rows = source.getRows().stream().map(row -> new RowView(
                    row.getRequestSupplyTypeCode(), row.getCollectedAt(), row.getCollectionRecord().getId(),
                    row.getCollectionRecord().getRequestParameters(), row.snapshot())).toList();
            return new SourceView(source.getId(), rows);
        });
    }

    private List<RecordView> records() {
        return new TransactionTemplate(transactionManager).execute(status -> recordRepository.findAll().stream()
                .map(record -> new RecordView(record.getId(), record.getExecutionId(), record.getStatus(),
                        record.getStoredRowCount(), record.getStartedAt(), record.getFinishedAt(),
                        record.getRequestParameters(), record.getFailureReason())).toList());
    }

    private record SourceView(Long id, List<RowView> rows) {
    }

    private record RowView(String supplyTypeCode, Instant collectedAt, UUID recordId,
                           Map<String, String> parameters, MyHomeAnnouncementSourceSnapshot snapshot) {
    }

    private record RecordView(UUID id, UUID executionId, CollectionStatus status, int rowCount,
                              Instant startedAt, Instant finishedAt, Map<String, String> parameters,
                              String failureReason) {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {MyHomeAnnouncementSource.class, SourceCollectionRecord.class})
    @EnableJpaRepositories(basePackageClasses = {
            MyHomeAnnouncementCollectionRepository.class, SourceCollectionRecordRepository.class
    })
    static class JpaConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        MutableClock clock() {
            return new MutableClock();
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer httpServer(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean("myHomeAnnouncementOpenApiClient")
        DataGoKrOpenApiClient client(RestClient.Builder builder, MockRestServiceServer server, ObjectMapper mapper) {
            return new DataGoKrOpenApiClient(builder.build(), mapper, "https://example.com", "test-key",
                    "마이홈 공고", new MyHomeResponseStatusValidator());
        }
    }

    static class MutableClock extends Clock {

        private Instant instant = FINISHED_AT;

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static class SchemaInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var environment = context.getEnvironment();
            String url = environment.getRequiredProperty("spring.datasource.url");
            try (Connection connection = DriverManager.getConnection(url,
                    environment.getRequiredProperty("spring.datasource.username"),
                    environment.getRequiredProperty("spring.datasource.password"))) {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + SCHEMA);
                }
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("ingest/compatibility-source-tables.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20261004_01__myhome_announcement_collection_storage.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260926_01__ingest_execution_ownership.sql"));
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource(
                            "db/migration/V20261004_06__source_import_metadata_and_announcement_lifecycle.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception exception) {
                throw new IllegalStateException("수집 저장 테스트 스키마를 준비하지 못했습니다.", exception);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("collectionStorageSchema", Map.of(
                    "spring.datasource.url", url + "?currentSchema=" + SCHEMA,
                    "spring.jpa.properties.hibernate.default_schema", SCHEMA
            )));
        }
    }
}
