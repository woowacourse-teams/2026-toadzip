package com.toadzip.backend.ingest.collection.myhome.complex.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
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
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexRegionSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexApiRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexCollectionRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexPageParser;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexRegionSourceRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeResponseStatusValidator;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.IngestOwnershipLostException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = MyHomeComplexStorageIntegrationTest.JpaConfiguration.class,
        initializers = MyHomeComplexStorageIntegrationTest.SchemaInitializer.class)
@Import({MyHomeComplexCollectionService.class, MyHomeComplexRegionCollectionService.class,
        ExternalDataRetryExecutor.class,
        SourceCollectionRecordService.class, MyHomeComplexStorageService.class, IngestWriteOwnershipGuard.class, IngestExecutionOwnershipRepository.class,
        MyHomeComplexCollector.class, MyHomeComplexApiRepository.class, MyHomeComplexPageParser.class,
        MyHomeRegionCatalog.class, IngestOperationLock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyHomeComplexStorageIntegrationTest {

    private static final String SCHEMA = "complex_storage_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant STARTED_AT = Instant.parse("2026-10-04T00:00:00Z");
    private static final Instant COLLECTED_AT = STARTED_AT.plusSeconds(1);
    private static final Instant FINISHED_AT = STARTED_AT.plusSeconds(30);
    private static final UUID EXECUTION_ID = UUID.fromString("bb0c73c1-1d56-4818-b44b-291d0e01dd31");

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private MyHomeRegionCatalog regions;

    @Autowired
    private MyHomeComplexCollectionService batch;

    @MockitoBean
    private ExternalDataFailureRecorder failures;

    @Autowired
    private MyHomeComplexStorageService service;

    @Autowired
    private SourceCollectionRecordService history;

    @Autowired
    private MyHomeComplexCollector collector;

    @Autowired
    private MockRestServiceServer httpServer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private IngestOperationLock executionLock;

    @Autowired
    private MyHomeComplexCollectionRepository sourceRepository;

    @MockitoSpyBean
    private MyHomeComplexRegionSourceRepository regionRepository;

    @Autowired
    private SourceCollectionRecordRepository recordRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void clearStorage() {
        httpServer.reset();
        clock.set(FINISHED_AT);
        jdbc.update("DELETE FROM myhome_complex_source_rows");
        jdbc.update("DELETE FROM myhome_complex_source_bundles");
        jdbc.update("DELETE FROM myhome_complex_source_regions");
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
    void administratorRegionExecutionUsesOneOperationLockAndPropagatesExecutionId() {
        httpServer.expect(requestTo(apiUrl(1))).andRespond(withSuccess(
                pageJson(1, row(100L, "11", "680", "59A", 100000L)), MediaType.APPLICATION_JSON));
        org.slf4j.MDC.put("executionId", EXECUTION_ID.toString());
        try {
            var result = batch.collect(new com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionRequest(
                    "11", "680", 2, 10));
            assertThat(result.storedRowCount()).isOne();
            assertThat(result.failedRequestCount()).isZero();
            assertThat(result.externalApiCallCount()).isOne();
        } finally {
            org.slf4j.MDC.remove("executionId");
        }
        assertThat(recordRepository.findAllByExecutionIdAndSource(EXECUTION_ID, CollectionSource.MYHOME_COMPLEX))
                .singleElement().extracting(record -> record.getStatus()).isEqualTo(CollectionStatus.SUCCESS);
    }

    @Test
    void administratorParallelRegionsStoreIndependentlyWithOneSharedExecutionId() {
        var seoul = regions.find("11", "680");
        var busan = regions.find("26", "110");
        org.mockito.Mockito.doReturn(List.of(seoul, busan)).when(regions).findAll();
        String busanUrl = apiUrl(1).replace("brtcCode=11&signguCode=680", "brtcCode=26&signguCode=110");
        httpServer.expect(ExpectedCount.times(2), request ->
                assertThat(request.getURI().toString()).isIn(apiUrl(1), busanUrl)).andRespond(request -> {
                    if (request.getURI().toString().equals(busanUrl)) {
                        return withSuccess(pageJson(1, row(200L, "26", "110", "59A", 200000L)),
                                MediaType.APPLICATION_JSON).createResponse(request);
                    }
                    return withSuccess(pageJson(1, row(100L, "11", "680", "59A", 100000L)),
                            MediaType.APPLICATION_JSON).createResponse(request);
                });
        org.slf4j.MDC.put("executionId", EXECUTION_ID.toString());
        try {
            var result = batch.collect(com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionRequest.allRegions(2, 10));
            assertThat(result.storedRowCount()).isEqualTo(2);
            assertThat(result.failedRequestCount()).isZero();
            assertThat(result.externalApiCallCount()).isEqualTo(2);
        } finally {
            org.slf4j.MDC.remove("executionId");
        }
        assertThat(recordRepository.findAllByExecutionIdAndSource(EXECUTION_ID, CollectionSource.MYHOME_COMPLEX))
                .hasSize(2).allMatch(record -> record.getStatus() == CollectionStatus.SUCCESS);
        assertThat(sourceRepository.count()).isEqualTo(2);
    }

    @Test
    void storesAllResponseRowsAndRegionalRequestMetadata() {
        MyHomeComplexSourceSnapshot first = row(100L, "11", "680", "59A", 100000L);
        MyHomeComplexSourceSnapshot second = row(100L, "11", "680", "84B", 200000L);

        UUID recordId = store(request("11", "680"), response(COLLECTED_AT, first, second));

        assertThat(sourceRepository.count()).isOne();
        assertThat(source(100L).rows()).containsExactly(first, second);
        RegionView region = region("11", "680");
        assertThat(region.collectedAt()).isEqualTo(COLLECTED_AT);
        assertThat(region.recordId()).isEqualTo(recordId);
        assertThat(region.parameters()).containsExactlyInAnyOrderEntriesOf(request("11", "680").parameters());
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo(recordId);
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isEqualTo(2);
            assertThat(record.startedAt()).isEqualTo(STARTED_AT);
            assertThat(record.finishedAt()).isEqualTo(FINISHED_AT);
        });
    }

    @Test
    void replacesWholeRegionRetainingSurvivingParentIdentityAndOtherRegions() {
        store(request("11", "680"), response(COLLECTED_AT,
                row(100L, "11", "680", "59A", 100000L), row(101L, "11", "680", "84B", 200000L)));
        Long originalId = source(100L).id();
        MyHomeComplexSourceSnapshot otherRegion = row(200L, "26", "110", "59A", 300000L);
        store(request("26", "110"), response(COLLECTED_AT, otherRegion));
        RegionView otherMetadata = region("26", "110");
        MyHomeComplexSourceSnapshot changed = row(100L, "11", "680", "84C", 400000L);
        MyHomeComplexSourceSnapshot added = row(102L, "11", "680", "59D", 500000L);

        store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1), changed, added));

        assertThat(source(100L).id()).isEqualTo(originalId);
        assertThat(source(100L).rows()).containsExactly(changed);
        assertThat(source(102L).rows()).containsExactly(added);
        assertThat(sourceRepository.findAllByHsmpSnIn(List.of(101L))).isEmpty();
        assertThat(source(200L).rows()).containsExactly(otherRegion);
        assertThat(region("26", "110")).isEqualTo(otherMetadata);
        assertThat(records()).hasSize(3).allMatch(record -> record.status() == CollectionStatus.SUCCESS);
    }

    @Test
    void verifiedEmptyResponseRemovesOnlyTargetRegionAndRetainsCollectionTime() {
        store(request("11", "680"), response(COLLECTED_AT, row(100L, "11", "680", "59A", 100000L)));
        MyHomeComplexSourceSnapshot otherRegion = row(200L, "26", "110", "59A", 300000L);
        store(request("26", "110"), response(COLLECTED_AT, otherRegion));

        UUID recordId = store(request("11", "680"), response(COLLECTED_AT.plusSeconds(2)));

        assertThat(sourceRepository.count()).isOne();
        assertThat(source(200L).rows()).containsExactly(otherRegion);
        assertThat(region("11", "680").collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(2));
        assertThat(region("11", "680").recordId()).isEqualTo(recordId);
        assertThat(records()).filteredOn(record -> record.id().equals(recordId)).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM myhome_complex_source_rows", Long.class)).isOne();
    }

    @Test
    void olderResponseCannotResurrectARegionAfterNewerEmptySuccess() {
        store(request("11", "680"), response(COLLECTED_AT.plusSeconds(2)));
        RegionView original = region("11", "680");

        assertThatThrownBy(() -> store(request("11", "680"),
                response(COLLECTED_AT, row(100L, "11", "680", "59A", 100000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("오래된 수집 응답");

        assertThat(sourceRepository.count()).isZero();
        assertThat(region("11", "680")).isEqualTo(original);
        assertFailedRecord("오래된 수집 응답");
    }

    @Test
    void unchangedResponseAdvancesActualCollectionTimeWithoutChangingParentIdentity() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        Long originalId = source(100L).id();

        store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1), original));

        assertThat(source(100L).id()).isEqualTo(originalId);
        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680").collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(1));
        assertThat(records()).hasSize(2);
    }

    @Test
    void incompleteResponsePreservesAllPreviousRegionDataAndRecordsFailure() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        MyHomeComplexCollectedResponse incomplete = new MyHomeComplexCollectedResponse(
                2, COLLECTED_AT.plusSeconds(1), List.of(row(100L, "11", "680", "59B", 200000L)));

        assertThatThrownBy(() -> store(request("11", "680"), incomplete))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("전체 응답 건수");

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("전체 응답 건수");
    }

    @Test
    void foreignRegionRowRejectsTheEntireReplacement() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");

        assertThatThrownBy(() -> store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                row(100L, "11", "680", "59B", 200000L), row(200L, "26", "110", "59A", 300000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("요청한 지역");

        assertThat(sourceRepository.count()).isOne();
        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("요청한 지역");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidComplexIdentifierIsRecordedAsFailure(Long identifier) {
        assertThatThrownBy(() -> store(request("11", "680"),
                response(COLLECTED_AT, row(identifier, "11", "680", "59A", 100000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("단지 식별자");

        assertThat(sourceRepository.count()).isZero();
        assertThat(regionRepository.count()).isZero();
        assertFailedRecord("단지 식별자");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 31})
    void invalidCollectionTimeCannotReplaceCurrentRegion(long secondsAfterStart) {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");

        assertThatThrownBy(() -> store(request("11", "680"),
                response(STARTED_AT.plusSeconds(secondsAfterStart), row(101L, "11", "680", "59B", 200000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("수집 시각");

        assertThat(sourceRepository.count()).isOne();
        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("수집 시각");
    }

    @Test
    void totalBeyondRequestedPageCapacityCannotPromoteRegion() {
        MyHomeComplexCollectionRequest request = new MyHomeComplexCollectionRequest(
                EXECUTION_ID, "11", "680", 1, 1, STARTED_AT);

        assertThatThrownBy(() -> store(request, response(COLLECTED_AT,
                row(100L, "11", "680", "59A", 100000L), row(101L, "11", "680", "59B", 200000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최대 페이지 범위");

        assertThat(sourceRepository.count()).isZero();
        assertThat(regionRepository.count()).isZero();
        assertFailedRecord("최대 페이지 범위");
    }

    @Test
    void sameRowIdentifierWithConflictingContentsRejectsTheEntireRegion() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");

        assertThatThrownBy(() -> store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                original, row(100L, "11", "680", "59A", 200000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("응답 내용이 충돌");

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("응답 내용이 충돌");
    }

    @Test
    void retainsDuplicateRowsAndOriginalWhitespaceAndDecimalPrecision() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        MyHomeComplexSourceSnapshot equivalent = new MyHomeComplexSourceSnapshot(
                100L, "LH", "11", "시도", "680", "시군구", "단지", "도로명 주소",
                "1234567890123456789", "20200101", 200, "국민임대", "59A",
                new BigDecimal("59.9012340"), new BigDecimal("20.12"), "아파트", "지역난방",
                "복도식", "설치", 180, 100000L, 20000L, 300000L);

        store(request("11", "680"), response(COLLECTED_AT, original, original, equivalent));

        assertThat(source(100L).rows()).containsExactly(original, original, equivalent);
        assertThat(records()).singleElement().extracting(RecordView::rowCount).isEqualTo(3);
    }

    @Test
    void retainsLegacyDistinctionBetweenMissingAndEmptyRowKeyFields() {
        MyHomeComplexSourceSnapshot missingPnu = withPnu(row(100L, "11", "680", "59A", 100000L), null);
        MyHomeComplexSourceSnapshot emptyPnu = withPnu(row(100L, "11", "680", "59A", 200000L), "");

        store(request("11", "680"), response(COLLECTED_AT, missingPnu, emptyPnu));

        assertThat(source(100L).rows()).containsExactly(missingPnu, emptyPnu);
        assertThat(records()).singleElement().extracting(RecordView::rowCount).isEqualTo(2);
    }

    @Test
    void conflictingComplexRegionPreservesBothExistingRegionsAndRejectsNewRows() {
        MyHomeComplexSourceSnapshot seoul = row(100L, "11", "680", "59A", 100000L);
        MyHomeComplexSourceSnapshot busan = row(200L, "26", "110", "59A", 300000L);
        store(request("11", "680"), response(COLLECTED_AT, seoul));
        store(request("26", "110"), response(COLLECTED_AT, busan));
        RegionView previous = region("26", "110");

        assertThatThrownBy(() -> store(request("26", "110"), response(COLLECTED_AT.plusSeconds(1),
                row(100L, "26", "110", "59A", 200000L), row(201L, "26", "110", "84B", 400000L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("기존 단지 식별자의 지역과 충돌");

        assertThat(sourceRepository.count()).isEqualTo(2);
        assertThat(source(100L).rows()).containsExactly(seoul);
        assertThat(source(200L).rows()).containsExactly(busan);
        assertThat(region("26", "110")).isEqualTo(previous);
        assertFailedRecord("지역과 충돌");
    }

    @Test
    void rollsBackDeletedParentsRowsAndRegionMetadataWhenRowInsertFails() {
        MyHomeComplexSourceSnapshot first = row(100L, "11", "680", "59A", 100000L);
        MyHomeComplexSourceSnapshot second = row(101L, "11", "680", "84B", 200000L);
        store(request("11", "680"), response(COLLECTED_AT, first, second));
        RegionView previous = region("11", "680");
        jdbc.execute("ALTER TABLE myhome_complex_source_rows ADD CONSTRAINT test_reject_row "
                + "CHECK (bass_rent_gtn <> 999)");
        try {
            assertThatThrownBy(() -> store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                    row(100L, "11", "680", "59C", 999L), row(102L, "11", "680", "84D", 300000L))))
                    .isInstanceOf(RuntimeException.class);

            assertThat(sourceRepository.count()).isEqualTo(2);
            assertThat(source(100L).rows()).containsExactly(first);
            assertThat(source(101L).rows()).containsExactly(second);
            assertThat(region("11", "680")).isEqualTo(previous);
            assertThat(records()).filteredOn(record -> record.status() == CollectionStatus.FAILED)
                    .singleElement().extracting(RecordView::rowCount).isEqualTo(0);
        } finally {
            jdbc.execute("ALTER TABLE myhome_complex_source_rows DROP CONSTRAINT test_reject_row");
        }
    }

    @Test
    void sourceAndRegionalMetadataRollbackWhenSuccessRecordCannotBeStored() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_success "
                + "CHECK (status <> 'SUCCESS' OR stored_row_count <> 2)");
        try {
            assertThatThrownBy(() -> store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                    row(101L, "11", "680", "59B", 200000L), row(102L, "11", "680", "84C", 300000L))))
                    .isInstanceOf(RuntimeException.class);

            assertThat(sourceRepository.count()).isOne();
            assertThat(source(100L).rows()).containsExactly(original);
            assertThat(region("11", "680")).isEqualTo(previous);
            assertThat(records()).filteredOn(record -> record.status() == CollectionStatus.FAILED)
                    .singleElement().extracting(RecordView::rowCount).isEqualTo(0);
        } finally {
            jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_success");
        }
    }

    @Test
    void heldOwnershipAllowsSourceAndSuccessRecordPromotion() {
        Lease lease = lease(1);
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 1 WHERE id = 1", EXECUTION_ID);
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);

        try (var ignored = IngestExecutionScope.open(lease)) {
            store(request("11", "680"), response(COLLECTED_AT, original));
        }

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @Test
    void previousOwnershipGenerationCannotPromoteSourceButItsFailureIsRecorded() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        Lease lease = lease(1);
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 2 WHERE id = 1", EXECUTION_ID);

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                    row(100L, "11", "680", "59B", 200000L))))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertThat(records()).hasSize(2).anyMatch(record -> record.status() == CollectionStatus.FAILED);
    }

    @Test
    void ownershipLossBeforeCommitRollsBackSourceRegionAndSuccessRecord() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        Lease lease = lease(1);
        doNothing().doThrow(new IngestOwnershipLostException()).when(lease).verifyHeld();
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 1 WHERE id = 1", EXECUTION_ID);

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                    row(101L, "11", "680", "59B", 200000L))))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        assertThat(sourceRepository.count()).isOne();
        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertThat(records()).filteredOn(record -> record.status() == CollectionStatus.FAILED).hasSize(1);
    }

    @Test
    void requestRecordAndSourceTimestampsUsePostgresMicrosecondPrecision() {
        MyHomeComplexCollectionRequest request = new MyHomeComplexCollectionRequest(
                EXECUTION_ID, "11", "680", 500, 1000, STARTED_AT.plusNanos(123456789));
        Instant collectedAt = COLLECTED_AT.plusNanos(987654321);
        UUID recordId = history.start(request);

        service.complete(recordId, request, response(collectedAt, row(100L, "11", "680", "59A", 100000L)));

        assertThat(records()).singleElement().extracting(RecordView::startedAt)
                .isEqualTo(STARTED_AT.plusNanos(123456000));
        assertThat(region("11", "680").collectedAt()).isEqualTo(COLLECTED_AT.plusNanos(987654000));
    }

    @Test
    void differentRequestCannotFinalizeAnotherRequestsRecord() {
        UUID recordId = history.start(request("11", "680"));

        assertThatThrownBy(() -> service.complete(recordId, request("26", "110"),
                response(COLLECTED_AT, row(100L, "26", "110", "59A", 100000L))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(sourceRepository.count()).isZero();
        assertThat(regionRepository.count()).isZero();
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.RUNNING);
        history.fail(recordId, request("11", "680"), new IllegalArgumentException("요청 중단"));
    }

    @Test
    void terminalSuccessCannotBeOverwrittenByCompletionOrFailure() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        UUID recordId = store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");

        assertThatThrownBy(() -> service.complete(
                recordId, request("11", "680"), response(COLLECTED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalStateException.class);
        history.fail(recordId, request("11", "680"), new IllegalArgumentException("뒤늦은 실패"));

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @Test
    void failureReasonRedactsServiceKeyAndKeepsRequestHistory() {
        UUID recordId = history.start(request("11", "680"));

        history.fail(recordId, request("11", "680"),
                new IllegalArgumentException("serviceKey=secret-value\n원천 조회 실패"));

        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.FAILED);
            assertThat(record.failureReason()).contains("serviceKey=[REDACTED]").doesNotContain("secret-value", "\n");
        });
        assertThat(sourceRepository.count()).isZero();
    }

    @Test
    void concurrentOldReplacementCannotResurrectNewerEmptyRegion() throws Exception {
        store(request("11", "680"), response(COLLECTED_AT, row(100L, "11", "680", "59A", 100000L)));
        CountDownLatch oldRegionRead = new CountDownLatch(1);
        CountDownLatch releaseOldRequest = new CountDownLatch(1);
        AtomicBoolean blockNextRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            // Spy의 파생 쿼리는 abstract이므로 실제 JPA 조회로 버전이 같은 두 트랜잭션을 준비한다.
            Object result = entityManager.createQuery(
                    "select r from MyHomeComplexRegionSource r "
                            + "where r.provinceCode = :province and r.districtCode = :district",
                    MyHomeComplexRegionSource.class)
                    .setParameter("province", "11").setParameter("district", "680").getResultStream().findFirst();
            if (blockNextRead.compareAndSet(true, false)) {
                oldRegionRead.countDown();
                if (!releaseOldRequest.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("오래된 요청 해제 대기 시간 초과");
                }
            }
            return result;
        }).when(regionRepository).findByProvinceCodeAndDistrictCode("11", "680");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRequest = executor.submit(() -> {
                try {
                    store(request("11", "680"), response(COLLECTED_AT.plusSeconds(1),
                            row(101L, "11", "680", "59B", 200000L)));
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            assertThat(oldRegionRead.await(10, TimeUnit.SECONDS)).isTrue();
            store(request("11", "680"), response(COLLECTED_AT.plusSeconds(2)));
            releaseOldRequest.countDown();

            assertThat(oldRequest.get(10, TimeUnit.SECONDS)).isInstanceOf(OptimisticLockingFailureException.class);
            assertThat(sourceRepository.count()).isZero();
            assertThat(region("11", "680").collectedAt()).isEqualTo(COLLECTED_AT.plusSeconds(2));
            assertThat(records()).hasSize(3)
                    .filteredOn(record -> record.status() == CollectionStatus.FAILED).hasSize(1);
        } finally {
            releaseOldRequest.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void commitsRunningRecordBeforeHttpAndPromotesRegionOnlyAfterAllPages() {
        AtomicReference<UUID> startedId = new AtomicReference<>();
        MyHomeComplexSourceSnapshot first = row(100L, "11", "680", "59A", 100000L);
        MyHomeComplexSourceSnapshot second = row(100L, "11", "680", "84B", 200000L);
        MyHomeComplexSourceSnapshot third = row(101L, "11", "680", "59C", 300000L);
        httpServer.expect(requestTo(apiUrl(1))).andRespond(httpRequest -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
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
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(sourceRepository.count()).isZero();
            assertThat(regionRepository.count()).isZero();
            clock.set(FINISHED_AT.plusSeconds(2));
            return withSuccess(pageJson(3, third), MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });

        UUID recordId = collector.collect(apiRequest(1000));

        assertThat(recordId).isEqualTo(startedId.get());
        assertThat(source(100L).rows()).containsExactly(first, second);
        assertThat(source(101L).rows()).containsExactly(third);
        assertThat(region("11", "680").collectedAt()).isEqualTo(FINISHED_AT.plusSeconds(2));
        assertThat(region("11", "680").recordId()).isEqualTo(recordId);
        assertThat(region("11", "680").parameters()).containsEntry("numOfRows", "2");
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isEqualTo(3);
            assertThat(record.finishedAt()).isEqualTo(FINISHED_AT.plusSeconds(2));
        });
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "TOO_MANY_REQUESTS"})
    void httpFailurePreservesCurrentRegionAndClosesStartedRecordWithoutRetry(HttpStatus status) {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        expectPage(1, pageJson(3, row(101L, "11", "680", "59B", 200000L)));
        httpServer.expect(requestTo(apiUrl(2))).andRespond(withStatus(status));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(ExternalDataRequestException.class).satisfies(failure -> {
                    ExternalDataRequestException external = (ExternalDataRequestException) failure;
                    assertThat(external.isRateLimited()).isEqualTo(status == HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(external.isRetryable()).isTrue();
                });

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertThat(records()).hasSize(2);
        assertFailedRecord("pageNo=2");
    }

    @Test
    void changedPageTotalCountRejectsTheEntireBuffer() {
        expectPage(1, pageJson(3, row(100L, "11", "680", "59A", 100000L)));
        expectPage(2, pageJson(4, row(101L, "11", "680", "59B", 200000L)));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 간 전체 건수");

        assertThat(sourceRepository.count()).isZero();
        assertThat(regionRepository.count()).isZero();
        assertFailedRecord("페이지 간 전체 건수");
    }

    @Test
    void emptyMiddlePageCannotReplacePreviousRegion() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        expectPage(1, pageJson(3, row(101L, "11", "680", "59B", 200000L)));
        expectPage(2, pageJson(3));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("빈 페이지");

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("빈 페이지");
    }

    @Test
    void noDataOnALaterPageIsACollectionFailure() {
        expectPage(1, pageJson(3, row(100L, "11", "680", "59A", 100000L)));
        expectPage(2, """
                {"response":{"header":{"resultCode":"03"}}}
                """);

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 간 전체 건수");

        assertThat(sourceRepository.count()).isZero();
        assertThat(regionRepository.count()).isZero();
        assertFailedRecord("페이지 간 전체 건수");
    }

    @Test
    void maximumPageExhaustionIsFailureAndCannotStorePartialRows() {
        expectPage(1, pageJson(3, row(100L, "11", "680", "59A", 100000L)));

        assertThatThrownBy(() -> collector.collect(apiRequest(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최대 페이지");

        assertThat(sourceRepository.count()).isZero();
        assertThat(regionRepository.count()).isZero();
        assertFailedRecord("최대 페이지");
    }

    @Test
    void shortPagesContinueUntilTheAdvertisedTotalIsReached() {
        expectPage(1, pageJson(2, row(100L, "11", "680", "59A", 100000L)));
        expectPage(2, pageJson(2, row(101L, "11", "680", "59B", 200000L)));

        collector.collect(apiRequest(1000));

        assertThat(sourceRepository.count()).isEqualTo(2);
        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hsmpSn", "hshldCo", "parkngCo", "bassRentGtn", "bassMtRntchrg", "bassCnvrsGtnLmt"
    })
    void fractionalIntegerFieldsAreRejectedWithoutChangingCurrentRegion(String field) {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        String payload = pageJson(1, original).replaceAll(
                "\"" + field + "\":\\d+", "\"" + field + "\":100.9");
        expectPage(1, payload);

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("응답 행 형식");

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("응답 행 형식");
    }

    @Test
    void acceptsIntegerStringFieldsWithoutLosingValues() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        String payload = pageJson(1, original)
                .replace("\"hsmpSn\":100", "\"hsmpSn\":\"100\"")
                .replace("\"hshldCo\":200", "\"hshldCo\":\"200\"")
                .replace("\"bassRentGtn\":100000", "\"bassRentGtn\":\"100000\"");
        expectPage(1, payload);

        collector.collect(apiRequest(1000));

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{invalid json",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":[]}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"item\":[]}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":-1}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":2147483648}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":\"1.5\"}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,\"item\":[1]}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,\"item\":\"bad\"}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,"
                    + "\"item\":{\"hsmpSn\":\"bad\"}}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":{\"totalCount\":1}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":{\"item\":{\"hsmpSn\":100}}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":\"corrupt\"}}",
            "{\"response\":{\"header\":{\"resultCode\":\"03\"},\"body\":[]}}"
    })
    void malformedResponsesAreFailedAndCannotDeletePreviousRegion(String payload) {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        expectPage(1, payload);

        assertThatThrownBy(() -> collector.collect(apiRequest(1000))).isInstanceOf(ExternalDataRequestException.class);

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("pageNo=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0}}}"
    })
    void explicitlyEmptyFirstPageReplacesOnlyTargetRegion(String payload) {
        store(request("11", "680"), response(COLLECTED_AT, row(100L, "11", "680", "59A", 100000L)));
        MyHomeComplexSourceSnapshot busan = row(200L, "26", "110", "59A", 200000L);
        store(request("26", "110"), response(COLLECTED_AT, busan));
        expectPage(1, payload);

        UUID recordId = collector.collect(apiRequest(1000));

        assertThat(sourceRepository.count()).isOne();
        assertThat(source(200L).rows()).containsExactly(busan);
        assertThat(region("11", "680").recordId()).isEqualTo(recordId);
        assertThat(region("11", "680").collectedAt()).isEqualTo(FINISHED_AT);
        assertThat(records()).filteredOn(record -> record.id().equals(recordId)).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.rowCount()).isZero();
        });
    }

    @Test
    void acceptsSingleItemObjectAndTextualTotalCount() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        expectPage(1, """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":"1","item":%s}}}
                """.formatted(objectMapper.writeValueAsString(original)));

        collector.collect(apiRequest(1000));

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(records()).singleElement().extracting(RecordView::status).isEqualTo(CollectionStatus.SUCCESS);
    }

    @Test
    void duplicateRowsWithinOnePageArePreserved() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        expectPage(1, pageJson(2, original, original));

        collector.collect(apiRequest(1000));

        assertThat(source(100L).rows()).containsExactly(original, original);
        assertThat(records()).singleElement().extracting(RecordView::rowCount).isEqualTo(2);
    }

    @Test
    void repeatedEntirePageIsRejectedEvenWhenItsRowsWouldReachTheTotalCount() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        String repeated = pageJson(4, row(101L, "11", "680", "59B", 200000L),
                row(102L, "11", "680", "84C", 300000L));
        expectPage(1, repeated);
        expectPage(2, repeated);

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("응답 페이지가 반복");

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("응답 페이지가 반복");
    }

    @Test
    void mixedRegionOnTheFirstPageFailsBeforeAnotherHttpCall() {
        expectPage(1, pageJson(3, row(100L, "26", "110", "59A", 100000L)));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("요청한 지역");

        assertThat(sourceRepository.count()).isZero();
        assertFailedRecord("요청한 지역");
    }

    @Test
    void missingIdentifierOnTheFirstPageFailsBeforeAnotherHttpCall() {
        expectPage(1, pageJson(3, row(null, "11", "680", "59A", 100000L)));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("단지 식별자");

        assertThat(sourceRepository.count()).isZero();
        assertFailedRecord("단지 식별자");
    }

    @Test
    void conflictingRowContentsAcrossPagesRejectTheEntireBuffer() {
        expectPage(1, pageJson(2, row(100L, "11", "680", "59A", 100000L)));
        expectPage(2, pageJson(2, row(100L, "11", "680", "59A", 200000L)));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("응답 내용이 충돌");

        assertThat(sourceRepository.count()).isZero();
        assertFailedRecord("응답 내용이 충돌");
    }

    @Test
    void tooManyResponseRowsCannotBePromoted() {
        expectPage(1, pageJson(1, row(100L, "11", "680", "59A", 100000L),
                row(101L, "11", "680", "59B", 200000L)));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("전체 건수를 초과");

        assertThat(sourceRepository.count()).isZero();
        assertFailedRecord("전체 건수를 초과");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void stopDuringHttpFinishesCompleteResponseAndStopsBeforeNextCall(int totalCount) {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        AtomicBoolean stopped = new AtomicBoolean();
        DataPipelineExecutionStateService state = mock(DataPipelineExecutionStateService.class);
        when(state.isStopRequested(EXECUTION_ID)).thenAnswer(invocation -> stopped.get());
        var monitor = new DataPipelineExecutionMonitor(EXECUTION_ID, state, clock);
        httpServer.expect(requestTo(apiUrl(1))).andRespond(httpRequest -> {
            stopped.set(true);
            return withSuccess(pageJson(totalCount, row(101L, "11", "680", "59B", 200000L),
                    row(102L, "11", "680", "84C", 300000L)), MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });

        try (var ignored = IngestExecutionScope.open(null, monitor)) {
            if (totalCount == 2) {
                UUID id = collector.collect(apiRequest(1000));
                assertThat(history.storedRowCount(id)).isEqualTo(2);
                assertThat(sourceRepository.findAllByHsmpSnIn(List.of(100L))).isEmpty();
                assertThat(sourceRepository.count()).isEqualTo(2);
                assertThat(records()).noneMatch(record -> record.status() == CollectionStatus.FAILED);
                return;
            }
            assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                    .isInstanceOf(DataPipelineStoppedException.class);
        }
        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("중지");
    }

    @Test
    void lostOwnershipDuringHttpPreventsSourcePromotionAndClosesFailedRecord() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        Lease lease = lease(1);
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = ?, generation = 1 WHERE id = 1", EXECUTION_ID);
        httpServer.expect(requestTo(apiUrl(1))).andRespond(httpRequest -> {
            jdbc.update("UPDATE ingest_execution_ownership SET generation = 2 WHERE id = 1");
            return withSuccess(pageJson(1, row(101L, "11", "680", "59B", 200000L)),
                    MediaType.APPLICATION_JSON).createResponse(httpRequest);
        });

        try (var ignored = IngestExecutionScope.open(lease)) {
            assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                    .isInstanceOf(IngestOwnershipLostException.class);
        }

        assertThat(source(100L).rows()).containsExactly(original);
        assertThat(region("11", "680")).isEqualTo(previous);
        assertFailedRecord("소유권");
    }

    @Test
    void secondConcurrentCollectionIsRejectedBeforeHttpOrRequestRecordCreation() {
        executionLock.tryRun(IngestOperationLock.Operation.MYHOME_COMPLEX_COLLECTION, () -> {
            assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                    .isInstanceOf(IngestAlreadyRunningException.class);
            return true;
        }).orElseThrow();

        assertThat(sourceRepository.count()).isZero();
        assertThat(records()).isEmpty();
    }

    @Test
    void unknownRegionIsRejectedBeforeHttpOrRequestRecordCreation() {
        var unknown = new MyHomeComplexCollectionRequest(EXECUTION_ID, "99", "999", 2, 1000, STARTED_AT);

        assertThatThrownBy(() -> collector.collect(unknown)).isInstanceOf(InvalidIngestRequestException.class);

        assertThat(sourceRepository.count()).isZero();
        assertThat(records()).isEmpty();
    }

    @Test
    void databaseFailureAfterHttpRollsBackRegionAndFinalizesTheSameAttemptAsFailed() {
        MyHomeComplexSourceSnapshot original = row(100L, "11", "680", "59A", 100000L);
        store(request("11", "680"), response(COLLECTED_AT, original));
        RegionView previous = region("11", "680");
        jdbc.execute("ALTER TABLE myhome_complex_source_rows ADD CONSTRAINT test_http_reject_row "
                + "CHECK (bass_rent_gtn <> 999)");
        expectPage(1, pageJson(1, row(101L, "11", "680", "59B", 999L)));
        try {
            assertThatThrownBy(() -> collector.collect(apiRequest(1000))).isInstanceOf(RuntimeException.class);

            assertThat(sourceRepository.count()).isOne();
            assertThat(source(100L).rows()).containsExactly(original);
            assertThat(region("11", "680")).isEqualTo(previous);
            assertThat(records()).hasSize(2)
                    .filteredOn(record -> record.status() == CollectionStatus.FAILED).hasSize(1);
        } finally {
            jdbc.execute("ALTER TABLE myhome_complex_source_rows DROP CONSTRAINT test_http_reject_row");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void gatewayRateLimitClassificationSurvivesRequestContextWithoutRetry(String resultCode) {
        expectPage(1, """
                {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"%s","returnAuthMsg":"LIMIT"}}}
                """.formatted(resultCode));

        assertThatThrownBy(() -> collector.collect(apiRequest(1000)))
                .isInstanceOf(ExternalDataRequestException.class).satisfies(failure -> {
                    ExternalDataRequestException external = (ExternalDataRequestException) failure;
                    assertThat(external.isRateLimited()).isTrue();
                    assertThat(external.isRetryable()).isEqualTo("23".equals(resultCode));
                });

        assertThat(sourceRepository.count()).isZero();
        assertFailedRecord("brtcCode=11&signguCode=680&pageNo=1");
    }

    @Test
    void nanosecondClockIsNormalizedWhenTheHttpAttemptSucceeds() {
        clock.set(FINISHED_AT.plusNanos(123456789));
        expectPage(1, pageJson(1, row(100L, "11", "680", "59A", 100000L)));

        collector.collect(apiRequest(1000));

        assertThat(records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.startedAt()).isEqualTo(FINISHED_AT.plusNanos(123456000));
        });
        assertThat(region("11", "680").collectedAt()).isEqualTo(FINISHED_AT.plusNanos(123456000));
    }

    @Test
    void highPrecisionNumericAreaIsPreservedThroughHttpParsingAndDatabaseStorage() {
        String precise = "59.901234567890123456789000";
        expectPage(1, pageJson(1, row(100L, "11", "680", "59A", 100000L)).replace("59.901234", precise));

        collector.collect(apiRequest(1000));

        assertThat(source(100L).rows().getFirst().suplyPrvuseAr()).isEqualTo(new BigDecimal(precise));
        assertThat(source(100L).rows().getFirst().suplyCmnuseAr()).isEqualTo(new BigDecimal("20.1200"));
    }

    private MyHomeComplexCollectionRequest apiRequest(int maxPages) {
        return new MyHomeComplexCollectionRequest(EXECUTION_ID, "11", "680", 2, maxPages, STARTED_AT);
    }

    private String apiUrl(int page) {
        return "https://example.com/rentalHouseGwList?serviceKey=test-key&brtcCode=11&signguCode=680&pageNo="
                + page + "&numOfRows=2";
    }

    private String pageJson(int totalCount, MyHomeComplexSourceSnapshot... rows) {
        return """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":%d,"item":%s}}}
                """.formatted(totalCount, objectMapper.writeValueAsString(List.of(rows)));
    }

    private void expectPage(int page, String payload) {
        httpServer.expect(requestTo(apiUrl(page))).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
    }

    private void assertFailedRecord(String reason) {
        assertThat(records()).filteredOn(record -> record.status() == CollectionStatus.FAILED)
                .singleElement().satisfies(record -> {
                    assertThat(record.rowCount()).isZero();
                    assertThat(record.failureReason()).contains(reason);
                });
    }

    private UUID store(MyHomeComplexCollectionRequest request, MyHomeComplexCollectedResponse response) {
        UUID id = history.start(request);
        try {
            service.complete(id, request, response);
        } catch (RuntimeException failure) {
            history.fail(id, request, failure);
            throw failure;
        }
        return id;
    }

    private MyHomeComplexCollectionRequest request(String provinceCode, String districtCode) {
        return new MyHomeComplexCollectionRequest(EXECUTION_ID, provinceCode, districtCode, 500, 1000, STARTED_AT);
    }

    private MyHomeComplexCollectedResponse response(Instant collectedAt, MyHomeComplexSourceSnapshot... rows) {
        return new MyHomeComplexCollectedResponse(rows.length, collectedAt, List.of(rows));
    }

    private MyHomeComplexSourceSnapshot row(
            Long hsmpSn, String provinceCode, String districtCode, String style, Long deposit
    ) {
        return new MyHomeComplexSourceSnapshot(
                hsmpSn, " LH ", provinceCode, "시도", districtCode, "시군구", " 단지 ", "도로명 주소",
                "1234567890123456789", "20200101", 200, "국민임대", style,
                new BigDecimal("59.901234"), new BigDecimal("20.1200"), "아파트", "지역난방",
                "복도식", "설치", 180, deposit, 20000L, 300000L
        );
    }

    private MyHomeComplexSourceSnapshot withPnu(MyHomeComplexSourceSnapshot row, String pnu) {
        return new MyHomeComplexSourceSnapshot(
                row.hsmpSn(), row.insttNm(), row.brtcCode(), row.brtcNm(), row.signguCode(), row.signguNm(),
                row.hsmpNm(), row.rnAdres(), pnu, row.competDe(), row.hshldCo(), row.suplyTyNm(), row.styleNm(),
                row.suplyPrvuseAr(), row.suplyCmnuseAr(), row.houseTyNm(), row.heatMthdDetailNm(), row.buldStleNm(),
                row.elvtrInstlAtNm(), row.parkngCo(), row.bassRentGtn(), row.bassMtRntchrg(), row.bassCnvrsGtnLmt()
        );
    }

    private SourceView source(Long hsmpSn) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            MyHomeComplexSource source = sourceRepository.findAllByHsmpSnIn(List.of(hsmpSn)).getFirst();
            return new SourceView(source.getId(), source.getRegion().getProvinceCode(),
                    source.getRegion().getDistrictCode(),
                    source.getRows().stream().map(row -> row.snapshot()).toList());
        });
    }

    private RegionView region(String provinceCode, String districtCode) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            MyHomeComplexRegionSource region = regionRepository.findByProvinceCodeAndDistrictCode(
                    provinceCode, districtCode).orElseThrow();
            return new RegionView(region.getCollectedAt(), region.getLastCollectionRecord().getId(),
                    region.getLastCollectionRecord().getRequestParameters());
        });
    }

    private List<RecordView> records() {
        return new TransactionTemplate(transactionManager).execute(status -> recordRepository.findAll().stream()
                .map(record -> new RecordView(record.getId(), record.getStatus(), record.getStoredRowCount(),
                        record.getStartedAt(), record.getFinishedAt(), record.getFailureReason())).toList());
    }

    private Lease lease(long generation) {
        Lease lease = mock(Lease.class);
        when(lease.ownerId()).thenReturn(EXECUTION_ID);
        when(lease.generation()).thenReturn(generation);
        return lease;
    }

    private record SourceView(Long id, String provinceCode, String districtCode,
                              List<MyHomeComplexSourceSnapshot> rows) {
    }

    private record RegionView(Instant collectedAt, UUID recordId, Map<String, String> parameters) {
    }

    private record RecordView(UUID id, CollectionStatus status, int rowCount, Instant startedAt,
                              Instant finishedAt, String failureReason) {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {MyHomeComplexSource.class, SourceCollectionRecord.class})
    @EnableJpaRepositories(basePackageClasses = {
            MyHomeComplexCollectionRepository.class, SourceCollectionRecordRepository.class
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

        @Bean("myHomeComplexOpenApiClient")
        DataGoKrOpenApiClient client(RestClient.Builder builder, MockRestServiceServer server, ObjectMapper mapper) {
            return new DataGoKrOpenApiClient(builder.build(), mapper, "https://example.com", "test-key",
                    "마이홈 단지", new MyHomeResponseStatusValidator());
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
                        "db/migration/V20261004_02__myhome_complex_collection_storage.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/migration/V20260926_01__ingest_execution_ownership.sql"));
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource(
                            "db/migration/V20261004_06__source_import_metadata_and_announcement_lifecycle.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception exception) {
                throw new IllegalStateException("단지 원천 저장 테스트 스키마를 준비하지 못했습니다.", exception);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("complexStorageSchema", Map.of(
                    "spring.datasource.url", url + "?currentSchema=" + SCHEMA,
                    "spring.jpa.properties.hibernate.default_schema", SCHEMA
            )));
        }
    }
}
