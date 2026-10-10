package com.toadzip.backend.ingest.collection.lh.leasecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhLeaseCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectedResponse;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogPageParser;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.repository.LhLeaseCatalogSourceRepository;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhResponseStatusValidator;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.pipeline.repository.IngestExecutionOwnershipRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@ContextConfiguration(classes = LhLeaseCatalogStorageIntegrationTest.JpaConfiguration.class,
        initializers = LhLeaseCatalogStorageIntegrationTest.SchemaInitializer.class)
@Import({LhLeaseCatalogCollector.class, LhLeaseCatalogStorageService.class, SourceCollectionRecordService.class,
        LhLeaseCatalogApiRepository.class, LhLeaseCatalogPageParser.class, IngestOperationLock.class,
        IngestWriteOwnershipGuard.class, IngestExecutionOwnershipRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LhLeaseCatalogStorageIntegrationTest {

    private static final String SCHEMA = "lease_catalog_" + UUID.randomUUID().toString().replace("-", "");

    private static final Instant STARTED_AT = Instant.parse("2026-10-04T00:00:00Z");

    private static final Instant COLLECTED_AT = STARTED_AT.plusSeconds(1);

    private static final Instant NOW = STARTED_AT.plusSeconds(30);

    private static final UUID EXECUTION_ID = UUID.fromString("3c8c7eb0-e980-4c53-9c84-9c79b8d161d7");

    @Autowired
    private LhLeaseCatalogCollector collector;

    @Autowired
    private LhLeaseCatalogStorageService storage;

    @Autowired
    private SourceCollectionRecordService history;

    @Autowired
    private LhLeaseCatalogSourceRepository sources;

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
        jdbc.update("DELETE FROM lh_lease_catalog_source_rows");
        jdbc.update("DELETE FROM lh_lease_catalog_source_bundles");
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
    void waitsForEveryPageAndPreservesDuplicateRowsAndOriginalValues() {
        LhCatalogSourceSnapshot first = row(" 단지 A ");
        LhCatalogSourceSnapshot last = row("단지 B");
        http.expect(requestTo(apiUrl(1))).andRespond(request -> {
            assertThat(records.findAll()).singleElement().satisfies(record -> {
                assertThat(record.getStatus()).isEqualTo(CollectionStatus.RUNNING);
                assertThat(record.getStartedAt()).isEqualTo(NOW);
            });
            assertThat(sources.count()).isZero();
            return withSuccess(page(1, 3, first, first), MediaType.APPLICATION_JSON).createResponse(request);
        });
        http.expect(requestTo(apiUrl(2))).andRespond(request -> {
            assertThat(sources.count()).isZero();
            return withSuccess(page(2, 3, last), MediaType.APPLICATION_JSON).createResponse(request);
        });

        UUID id = collector.collect(request(2));

        assertThat(source().rows()).containsExactly(first, first, last);
        assertThat(source().collectedAt()).isEqualTo(NOW);
        assertThat(source().recordId()).isEqualTo(id);
        assertThat(records.findAll()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.getStoredRowCount()).isEqualTo(3);
        });
    }

    @Test
    void incompleteShortPageCannotReplacePreviousCatalog() {
        seed(row("기존 단지"));
        SourceView previous = source();
        expectPage(1, page(1, 3, row("새 단지")));

        assertThatThrownBy(() -> collector.collect(request(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("최대 페이지");

        assertThat(source()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void changedTotalCountCannotReplacePreviousCatalog() {
        seed(row("기존 단지"));
        SourceView previous = source();
        expectPage(1, page(1, 3, row("A"), row("B")));
        expectPage(2, page(2, 4, row("C")));

        assertThatThrownBy(() -> collector.collect(request(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("페이지 간 전체 건수");

        assertThat(source()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void repeatedResponseRowsCannotMasqueradeAsACompleteCatalog() {
        seed(row("기존 단지"));
        SourceView previous = source();
        expectPage(1, page(1, 4, row("A"), row("B")));
        expectPage(2, page(2, 4, row("A"), row("B")));

        assertThatThrownBy(() -> collector.collect(request(2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("같은 응답 페이지");

        assertThat(source()).isEqualTo(previous);
        assertFailed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "count", "ordinal", "dataset", "identifier", "empty", "duplicate",
            "objectAmount", "arrayArea"})
    void malformedResponsesCannotReplacePreviousCatalog(String invalidPart) {
        seed(row("기존 단지"));
        SourceView previous = source();
        String payload = switch (invalidPart) {
            case "page" -> page(1, 1, row("A")).replace("\"PAGE\":\"1\"", "\"PAGE\":\"2\"");
            case "size" -> page(1, 1, row("A")).replace("\"PG_SZ\":\"2\"", "\"PG_SZ\":\"1\"");
            case "count" -> page(1, 1, row("A")).replace("\"ALL_CNT\":\"1\"", "\"ALL_CNT\":\"1.9\"");
            case "ordinal" -> page(1, 1, row("A")).replace("\"RNUM\":\"1\"", "\"RNUM\":\"2\"");
            case "dataset" -> page(1, 1, row("A")).replace("\"dsList\"", "\"unknown\"");
            case "identifier" -> page(1, 1, row("A")).replace("\"SBD_LGO_NM\":\"A\"", "\"SBD_LGO_NM\":\" \"");
            case "empty" -> page(1, 0);
            case "duplicate" -> page(1, 1, row("A")).replace("{\"resHeader\"", "{\"dsList\":[]},{\"resHeader\"");
            case "objectAmount" -> page(1, 1, row("A")).replace("\"RFE\":\" 300 \"",
                    "\"RFE\":{\"amount\":\"1000\"}");
            case "arrayArea" -> page(1, 1, row("A")).replace("\"DDO_AR\":\"59.123456789000\"", "\"DDO_AR\":[]");
            default -> throw new IllegalArgumentException(invalidPart);
        };
        expectPage(1, payload);

        assertThatThrownBy(() -> collector.collect(request(2))).isInstanceOf(ExternalDataRequestException.class);

        assertThat(source()).isEqualTo(previous);
        assertFailed();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "TOO_MANY_REQUESTS"})
    void httpFailurePreservesSourceAndRetryClassification(HttpStatus status) {
        seed(row("기존 단지"));
        SourceView previous = source();
        http.expect(requestTo(apiUrl(1))).andRespond(withStatus(status));

        assertThatThrownBy(() -> collector.collect(request(2)))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, failure -> {
                    assertThat(failure.getMessage()).contains("PG_SZ=2&PAGE=1");
                    assertThat(failure.isRateLimited()).isEqualTo(status == HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(failure.isRetryable()).isTrue();
                });

        assertThat(source()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void successRecordPersistenceFailureRollsBackCatalogReplacement() {
        seed(row("기존 단지"));
        SourceView previous = source();
        jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_success "
                + "CHECK (status <> 'SUCCESS' OR stored_row_count <> 2)");
        try {
            expectPage(1, page(1, 2, row("A"), row("B")));

            assertThatThrownBy(() -> collector.collect(request(2))).isInstanceOf(RuntimeException.class);

            assertThat(source()).isEqualTo(previous);
            assertFailed();
        } finally {
            jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_success");
        }
    }

    private LhLeaseCatalogCollectionRequest request(int maxPages) {
        return new LhLeaseCatalogCollectionRequest(EXECUTION_ID, 2, maxPages, STARTED_AT);
    }

    private void seed(LhCatalogSourceSnapshot snapshot) {
        LhLeaseCatalogCollectionRequest request = request(2);
        UUID id = history.start(request);
        storage.complete(id, request, new LhLeaseCatalogCollectedResponse(1, COLLECTED_AT, List.of(snapshot)));
    }

    private SourceView source() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            LhLeaseCatalogSource source = sources.findByScopeKey("ALL").orElseThrow();
            return new SourceView(source.getId(), source.getVersion(), source.getCollectedAt(),
                    source.getLastCollectionRecord().getId(),
                    source.getRows().stream().map(row -> row.snapshot()).toList());
        });
    }

    private void assertFailed() {
        assertThat(records.findAll()).filteredOn(record -> record.getStatus() == CollectionStatus.FAILED).hasSize(1);
        assertThat(records.findAll()).noneMatch(record -> record.getStatus() == CollectionStatus.RUNNING);
    }

    private String apiUrl(int page) {
        return "https://example.com/lhLeaseInfo1/lhLeaseInfo1?serviceKey=test-key&PG_SZ=2&PAGE=" + page;
    }

    private void expectPage(int page, String payload) {
        http.expect(requestTo(apiUrl(page))).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
    }

    private String page(int page, int total, LhCatalogSourceSnapshot... snapshots) {
        var rows = new ArrayList<Map<String, String>>();
        for (int index = 0; index < snapshots.length; index++) {
            LhCatalogSourceSnapshot row = snapshots[index];
            rows.add(Map.of("ARA_NM", row.areaName(), "AIS_TP_CD_NM", row.supplyTypeName(),
                    "SBD_LGO_NM", row.complexLabel(), "SUM_HSH_CNT", row.complexTotalUnitCount(),
                    "DDO_AR", row.exclusiveArea(), "HSH_CNT", row.totalUnitCount(), "LS_GMY", row.depositText(),
                    "RFE", row.monthlyRentText(), "ALL_CNT", Integer.toString(total),
                    "RNUM", Integer.toString((page - 1) * 2 + index + 1)));
        }
        return "[{\"dsSch\":[{\"PAGE\":\"%d\",\"PG_SZ\":\"2\"}]},{\"dsList\":%s},"
                .formatted(page, mapper.writeValueAsString(rows)) + "{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]";
    }

    private LhCatalogSourceSnapshot row(String name) {
        return new LhCatalogSourceSnapshot(
                " 서울 ", " 행복주택 ", name, "0100", "59.123456789000", "020", "12,345", " 300 ");
    }

    private record SourceView(
            Long id, long version, Instant collectedAt, UUID recordId, List<LhCatalogSourceSnapshot> rows
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {LhLeaseCatalogSource.class, SourceCollectionRecord.class})
    @EnableJpaRepositories(basePackageClasses = {
            LhLeaseCatalogSourceRepository.class, SourceCollectionRecordRepository.class})
    static class JpaConfiguration {

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

        @Bean("lhOpenApiClient")
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
                        "V20261004_03__lh_lease_catalog_collection_storage.sql",
                        "V20260926_01__ingest_execution_ownership.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + script));
                }
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource(
                            "db/migration/V20261004_06__source_import_metadata_and_announcement_lifecycle.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                throw new IllegalStateException("LH 카탈로그 저장 테스트 스키마를 준비하지 못했습니다.", failure);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("leaseCatalogSchema", Map.of(
                    "spring.datasource.url", url + "?currentSchema=" + SCHEMA,
                    "spring.jpa.properties.hibernate.default_schema", SCHEMA)));
        }
    }
}
