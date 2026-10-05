package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogEntry;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogApiRepository;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogEntryRepository;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogPageParser;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogWriteLock;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhResponseStatusValidator;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.pipeline.repository.IngestExecutionOwnershipRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.client.MockRestServiceServer;
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
@ContextConfiguration(classes = LhAnnouncementCatalogStorageIntegrationTest.JpaConfiguration.class,
        initializers = LhAnnouncementCatalogStorageIntegrationTest.SchemaInitializer.class)
@Import({LhAnnouncementCatalogCollector.class, LhAnnouncementCatalogStorageService.class,
        SourceCollectionRecordService.class,
        LhAnnouncementCatalogWriteLock.class,
        LhAnnouncementCatalogApiRepository.class,
        LhAnnouncementCircuitBreaker.class, LhAnnouncementCatalogPageParser.class, IngestOperationLock.class,
        IngestWriteOwnershipGuard.class, IngestExecutionOwnershipRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LhAnnouncementCatalogStorageIntegrationTest {

    private static final String SCHEMA = "announcement_catalog_" + UUID.randomUUID().toString().replace("-", "");

    private static final Instant STARTED_AT = Instant.parse("2026-10-04T00:00:00Z");

    private static final Instant COLLECTED_AT = STARTED_AT.plusSeconds(1);

    private static final Instant NOW = STARTED_AT.plusSeconds(30);

    private static final UUID EXECUTION_ID = UUID.fromString("3c8c7eb0-e980-4c53-9c84-9c79b8d161d7");

    @Autowired
    private LhAnnouncementCatalogCollector collector;

    @Autowired
    private LhAnnouncementCatalogStorageService storage;

    @Autowired
    private SourceCollectionRecordService history;

    @Autowired
    private LhAnnouncementCatalogEntryRepository sources;

    @Autowired
    private SourceCollectionRecordRepository records;

    @Autowired
    private MockRestServiceServer http;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearStorage() {
        http.reset();
        jdbc.update("DELETE FROM lh_announcement_catalog_entries");
        jdbc.update("DELETE FROM source_collection_record_parameters");
        jdbc.update("DELETE FROM source_collection_records");
        jdbc.update("UPDATE ingest_execution_ownership SET owner_id = NULL, generation = 0 WHERE id = 1");
    }

    @AfterEach
    void verifyHttp() {
        http.verify();
    }

    @AfterAll
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void completesEveryPageBeforeSavingRawCatalogFieldsAndRows() {
        LhAnnouncementCatalogSnapshot first = row("PAN-A", " 원문 이름 ");
        LhAnnouncementCatalogSnapshot last = row("PAN-B", "이름 B");
        expectPage(1, page(1, 3, first, row("PAN-C", "이름 C")));
        http.expect(requestTo(apiUrl(2))).andRespond(request -> {
            assertThat(sources.count()).isZero();
            assertThat(records.findAll()).singleElement().satisfies(record ->
                    assertThat(record.getStatus()).isEqualTo(CollectionStatus.RUNNING));
            return withSuccess(page(2, 3, last), MediaType.APPLICATION_JSON).createResponse(request);
        });

        UUID id = collector.collect(request(2));

        assertThat(view()).hasSize(3).anySatisfy(source -> {
            assertThat(source.snapshot()).isEqualTo(first);
            assertThat(source.rawPayload()).contains(" 원문 이름 ");
            assertThat(source.collectedAt()).isEqualTo(NOW);
            assertThat(source.recordId()).isEqualTo(id);
        });
        assertThat(records.findAll()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.getStoredRowCount()).isEqualTo(3);
        });
    }

    @Test
    void unchangedCatalogRefreshDoesNotAdvanceChangedAt() {
        seed(row("PAN-A", "이름 A"));
        expectPage(1, page(1, 1, row("PAN-A", " 이름 A ")));

        collector.collect(request(2));

        assertThat(view()).singleElement().satisfies(source -> {
            assertThat(source.changedAt()).isEqualTo(COLLECTED_AT);
            assertThat(source.collectedAt()).isEqualTo(NOW);
            assertThat(source.snapshot().announcementName()).isEqualTo(" 이름 A ");
        });
    }

    @Test
    void absentEntriesArePreservedAndReappearingEntryGetsNewChangeTime() {
        seed(row("PAN-A", "이름 A"));
        expectPage(1, page(1, 1, row("PAN-B", "이름 B")));
        collector.collect(request(2));
        assertThat(view()).hasSize(2).anySatisfy(source -> {
            assertThat(source.snapshot().panId()).isEqualTo("PAN-A");
            assertThat(source.present()).isFalse();
            assertThat(source.collectedAt()).isEqualTo(COLLECTED_AT);
        });
        http.reset();
        expectPage(1, page(1, 1, row("PAN-A", "이름 A")));

        collector.collect(request(2));

        assertThat(view()).anySatisfy(source -> {
            assertThat(source.snapshot().panId()).isEqualTo("PAN-A");
            assertThat(source.present()).isTrue();
            assertThat(source.changedAt()).isEqualTo(NOW);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"period", "duplicate", "count", "empty", "ordinal", "object", "missing", "header"})
    void invalidCatalogCannotChangePreviousEntriesOrLatestFlags(String invalidPart) {
        seed(row("PAN-A", "기존 이름"));
        List<SourceView> previous = view();
        String first = page(1, 3, row("PAN-B", "이름 B"), row("PAN-C", "이름 C"));
        String next = page(2, 3, row("PAN-D", "이름 D"));
        switch (invalidPart) {
            case "period" -> next = next.replace("\"20260804\"", "\"20260805\"");
            case "duplicate" -> next = page(2, 3, row("PAN-B", "충돌 이름"));
            case "count" -> next = page(2, 4, row("PAN-D", "이름 D"));
            case "empty" -> next = page(2, 3);
            case "ordinal" -> next = next.replace("\"RNUM\":\"3\"", "\"RNUM\":\"4\"");
            case "object" -> next = next.replace("\"PAN_NM\":\"이름 D\"", "\"PAN_NM\":{}");
            case "missing" -> next = next.replace("\"PAN_ID\":\"PAN-D\"", "\"PAN_ID\":\" \"");
            case "header" -> next = next.replace("\"SS_CODE\":\"Y\"", "\"SS_CODE\":\"N\"");
            default -> throw new IllegalArgumentException(invalidPart);
        }
        expectPage(1, first);
        expectPage(2, next);

        assertThatThrownBy(() -> collector.collect(request(2))).isInstanceOf(RuntimeException.class);

        assertThat(view()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void maximumPageLimitCannotPromoteIncompleteCatalog() {
        seed(row("PAN-A", "기존 이름"));
        List<SourceView> previous = view();
        expectPage(1, page(1, 3, row("PAN-B", "B"), row("PAN-C", "C")));

        assertThatThrownBy(() -> collector.collect(request(1))).hasMessageContaining("최대 페이지");

        assertThat(view()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void concurrentCompletedCatalogsCannotBothRemainLatest() throws Exception {
        LhAnnouncementCatalogCollectionRequest request = request(2);
        UUID firstRecord = history.start(request);
        UUID secondRecord = history.start(request);
        var begin = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                begin.await();
                return storage.complete(firstRecord, request, response(row("PAN-A", "A")));
            });
            var second = executor.submit(() -> {
                begin.await();
                return storage.complete(secondRecord, request, response(row("PAN-B", "B")));
            });
            begin.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).storedRowCount()).isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS).storedRowCount()).isEqualTo(1);
        }

        assertThat(view()).hasSize(2).filteredOn(SourceView::present).hasSize(1);
        assertThat(records.findAll()).allSatisfy(record ->
                assertThat(record.getStatus()).isEqualTo(CollectionStatus.SUCCESS));
    }

    private LhAnnouncementCatalogCollectedResponse response(LhAnnouncementCatalogSnapshot snapshot) {
        return new LhAnnouncementCatalogCollectedResponse(1, "20260804", "20261004", NOW,
                List.of(new LhAnnouncementCatalogRow(snapshot, "{}")));
    }

    @Test
    void successRecordFailureRollsBackEntriesAndLatestFlags() {
        seed(row("PAN-A", "기존 이름"));
        List<SourceView> previous = view();
        jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_success "
                + "CHECK (status <> 'SUCCESS' OR stored_row_count <> 2)");
        try {
            expectPage(1, page(1, 2, row("PAN-B", "B"), row("PAN-C", "C")));

            assertThatThrownBy(() -> collector.collect(request(2))).isInstanceOf(RuntimeException.class);

            assertThat(view()).isEqualTo(previous);
            assertFailed();
        } finally {
            jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_success");
        }
    }

    private LhAnnouncementCatalogCollectionRequest request(int maxPages) {
        return new LhAnnouncementCatalogCollectionRequest(EXECUTION_ID, 2, maxPages, STARTED_AT);
    }

    private void seed(LhAnnouncementCatalogSnapshot snapshot) {
        LhAnnouncementCatalogCollectionRequest request = request(2);
        UUID id = history.start(request);
        storage.complete(id, request, new LhAnnouncementCatalogCollectedResponse(1, "20260804", "20261004",
                COLLECTED_AT, List.of(new LhAnnouncementCatalogRow(snapshot, "{}"))));
    }

    private List<SourceView> view() {
        return new TransactionTemplate(transactionManager).execute(status -> sources.findAll().stream()
                .sorted(Comparator.comparing(LhAnnouncementCatalogEntry::getSourceKey))
                .map(source -> new SourceView(source.snapshot(), source.getRawPayload(), source.getCollectedAt(),
                        source.getChangedAt(), source.isPresentInLatestCatalog(),
                        source.getLastCollectionRecord().getId())).toList());
    }

    private void assertFailed() {
        assertThat(records.findAll()).filteredOn(record -> record.getStatus() == CollectionStatus.FAILED).hasSize(1);
        assertThat(records.findAll()).noneMatch(record -> record.getStatus() == CollectionStatus.RUNNING);
    }

    private String apiUrl(int page) {
        return "https://example.com/lhLeaseNoticeInfo1/lhLeaseNoticeInfo1?serviceKey=test-key&PG_SZ=2&PAGE=" + page;
    }

    private void expectPage(int page, String payload) {
        http.expect(requestTo(apiUrl(page))).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
    }

    private String page(int page, int total, LhAnnouncementCatalogSnapshot... snapshots) {
        var rows = new ArrayList<Map<String, String>>();
        for (int index = 0; index < snapshots.length; index++) {
            LhAnnouncementCatalogSnapshot row = snapshots[index];
            rows.add(Map.ofEntries(Map.entry("PAN_ID", row.panId()),
                    Map.entry("CCR_CNNT_SYS_DS_CD", row.connectionSystemDivisionCode()),
                    Map.entry("UPP_AIS_TP_CD", row.upperAnnouncementTypeCode()),
                    Map.entry("AIS_TP_CD", row.announcementTypeCode()),
                    Map.entry("SPL_INF_TP_CD", row.supplyInfoTypeCode()),
                    Map.entry("PAN_NM", row.announcementName()), Map.entry("PAN_SS", row.status()),
                    Map.entry("PAN_NT_ST_DT", row.noticeDate()), Map.entry("PAN_DT", row.publicationDate()),
                    Map.entry("CLSG_DT", row.closingDate()), Map.entry("DTL_URL", row.detailUrl()),
                    Map.entry("DTL_URL_MOB", row.mobileDetailUrl()), Map.entry("ALL_CNT", Integer.toString(total)),
                    Map.entry("RNUM", Integer.toString((page - 1) * 2 + index + 1))));
        }
        return "[{\"dsSch\":[{\"PAGE\":\"%d\",\"PG_SZ\":\"2\","
                .formatted(page) + "\"PAN_ST_DT\":\"20260804\",\"PAN_ED_DT\":\"20261004\"}]},{\"dsList\":"
                + mapper.writeValueAsString(rows) + "},{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]";
    }

    private LhAnnouncementCatalogSnapshot row(String panId, String name) {
        return new LhAnnouncementCatalogSnapshot(panId, "01", "06", "10", "050", name,
                "공고중", "20261001", "20261001", "20261031", "https://apply.lh.or.kr/a", "https://apply.lh.or.kr/m");
    }

    private record SourceView(
            LhAnnouncementCatalogSnapshot snapshot, String rawPayload, Instant collectedAt,
            Instant changedAt, boolean present, UUID recordId
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {LhAnnouncementCatalogEntry.class, SourceCollectionRecord.class})
    @EnableJpaRepositories(basePackageClasses = {
            LhAnnouncementCatalogEntryRepository.class, SourceCollectionRecordRepository.class})
    static class JpaConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        RestClient.Builder builder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer http(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean("lhAnnouncementOpenApiClient")
        DataGoKrOpenApiClient client(RestClient.Builder builder, MockRestServiceServer server, ObjectMapper mapper) {
            return new DataGoKrOpenApiClient(builder.build(), mapper, "https://example.com", "test-key",
                    "LH", new LhResponseStatusValidator());
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
                for (String script : List.of("V20261004_01__myhome_announcement_collection_storage.sql",
                        "V20261004_04__lh_announcement_catalog_collection_storage.sql",
                        "V20260926_01__ingest_execution_ownership.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + script));
                }
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource(
                            "db/migration/V20261004_06__source_import_metadata_and_announcement_lifecycle.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                throw new IllegalStateException("LH 공고 목록 저장 테스트 스키마를 준비하지 못했습니다.", failure);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("announcementCatalogSchema", Map.of(
                    "spring.datasource.url", url + "?currentSchema=" + SCHEMA,
                    "spring.jpa.properties.hibernate.default_schema", SCHEMA)));
        }
    }
}
