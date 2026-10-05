package com.toadzip.backend.ingest.collection.lh.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionStatus;
import com.toadzip.backend.ingest.collection.history.domain.SourceCollectionRecord;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailRow;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhAnnouncementDetailRowRepository;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhDetailResponseParser;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuerySource;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.collection.lh.repository.external.LhResponseStatusValidator;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplyRow;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhSupplySnapshot;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhAnnouncementSupplyRowRepository;
import com.toadzip.backend.ingest.collection.lh.supply.repository.LhSupplyResponseParser;
import com.toadzip.backend.ingest.collection.lh.supply.repository.VerifiedLhSupplyReplacementStore;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import com.toadzip.backend.ingest.pipeline.repository.IngestExecutionOwnershipRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = LhAnnouncementStorageIntegrationTest.JpaConfiguration.class,
        initializers = LhAnnouncementStorageIntegrationTest.SchemaInitializer.class)
@Import({LhAnnouncementQueryCollector.class, LhAnnouncementStorageService.class, SourceCollectionRecordService.class,
        LhAnnouncementQueryApiRepository.class, LhSupplyResponseParser.class, LhDetailResponseParser.class,
        VerifiedLhSupplyReplacementStore.class, IngestWriteOwnershipGuard.class,
        IngestExecutionOwnershipRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LhAnnouncementStorageIntegrationTest {

    private static final String SCHEMA = "lh_query_" + UUID.randomUUID().toString().replace("-", "");

    private static final Instant STARTED_AT = Instant.parse("2026-10-04T00:00:00Z");

    private static final Instant COLLECTED_AT = STARTED_AT.plusSeconds(1);

    private static final Instant NOW = STARTED_AT.plusSeconds(30);

    private static final UUID EXECUTION_ID = UUID.fromString("3c8c7eb0-e980-4c53-9c84-9c79b8d161d7");

    @Autowired
    private LhAnnouncementQueryCollector collector;

    @Autowired
    private LhAnnouncementStorageService storage;

    @Autowired
    private SourceCollectionRecordService history;

    @Autowired
    private LhAnnouncementQuerySourceRepository sources;

    @Autowired
    private LhAnnouncementSupplyRowRepository supplies;

    @Autowired
    private LhAnnouncementDetailRowRepository details;

    @Autowired
    private VerifiedLhSupplyReplacementStore approvals;

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
        jdbc.update("DELETE FROM lh_announcement_supply_rows");
        jdbc.update("DELETE FROM lh_announcement_detail_rows");
        jdbc.update("DELETE FROM lh_announcement_query_parameters");
        jdbc.update("DELETE FROM lh_announcement_query_sources");
        jdbc.update("DELETE FROM verified_lh_supply_replacements");
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
    void preservesRawSupplyRowsAndDuplicatesWithRequestAndSuccessfulRecord() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        var row = supply(" 단지 ", " 형 1 ");
        expect(request, "dsList01", List.of(supplyWire(row), supplyWire(row)));

        UUID id = collector.collect(request);

        assertThat(view()).singleElement().satisfies(source -> {
            assertThat(source.supplies()).containsExactly(row, row);
            assertThat(source.collectedAt()).isEqualTo(NOW);
            assertThat(source.requestHash()).isEqualTo(request.requestHash());
            assertThat(source.recordId()).isEqualTo(id);
        });
        assertThat(records.findAll()).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(record.getStoredRowCount()).isEqualTo(2);
        });
    }

    @Test
    void supplyRowsMissingAtTheSameTotalCountAreHeld() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        seedSupply(request, supply("단지", "A"), supply("단지", "B"));
        var previous = view();
        expect(request, "dsList01", List.of(supplyWire(supply("단지", "A")), supplyWire(supply("단지", "C"))));

        assertThatThrownBy(() -> collector.collect(request))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class);

        assertThat(view()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void parserVersionDoesNotChangeExternalIdentityOrBypassPreviousSupplyProtection() {
        var previousRequest = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 5);
        seedSupply(previousRequest, supply("단지", "A"), supply("단지", "B"));
        var previous = view();
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        expect(request, "dsList01", List.of(supplyWire(supply("단지", "A"))));

        assertThatThrownBy(() -> collector.collect(request))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class);

        assertThat(view()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void samePanWithDifferentQueryConditionsCreatesIndependentSource() {
        var first = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        var second = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "060", 6);
        seedSupply(first, supply("A", "59"));
        seedSupply(second, supply("B", "84"));

        assertThat(view()).hasSize(2).extracting(SourceView::queryHash).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"panId", "dataset", "duplicate", "objectAmount"})
    void malformedSupplyResponsePreservesSuccessfulSource(String invalid) {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        seedSupply(request, supply("단지", "A"));
        var previous = view();
        String payload = payload(request, "dsList01", List.of(supplyWire(supply("단지", "A"))));
        payload = switch (invalid) {
            case "panId" -> payload.replace("\"PAN_ID\":\"PAN-A\"", "\"PAN_ID\":\"PAN-B\"");
            case "dataset" -> payload.replace("\"dsList01\"", "\"unknown\"");
            case "duplicate" -> payload.replace("{\"resHeader\"", "{\"dsList01\":[]},{\"resHeader\"");
            case "objectAmount" -> payload.replace("\"RFE\":\" 00300 \"", "\"RFE\":{}");
            default -> throw new IllegalArgumentException(invalid);
        };
        expectPayload(request, payload);

        assertThatThrownBy(() -> collector.collect(request)).isInstanceOf(ExternalDataRequestException.class);

        assertThat(view()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void verifiedFingerprintAllowsReductionAndKeepsOriginalIncomingFields() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        seedSupply(request, supply("단지", "A"), supply("단지", "B"));
        var incoming = supply(" 단지 ", " A ");
        long approvalId = approve(request, incoming);
        expect(request, "dsList01", List.of(supplyWire(incoming)));

        collector.collect(request);

        assertThat(view()).singleElement().satisfies(source -> assertThat(source.supplies()).containsExactly(incoming));
        assertThat(consumed(approvalId)).isNotNull();
    }

    @Test
    void unmatchedApprovalCannotReplaceSourceOrBeConsumed() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        seedSupply(request, supply("단지", "A"), supply("단지", "B"));
        var previous = view();
        long approvalId = approve(request, supply("단지", "A"));
        expect(request, "dsList01", List.of(supplyWire(supply("단지", "C"))));

        assertThatThrownBy(() -> collector.collect(request))
                .isInstanceOf(IncompleteLhSupplyReplacementException.class);

        assertThat(view()).isEqualTo(previous);
        assertThat(consumed(approvalId)).isNull();
        assertFailed();
    }

    @Test
    void successRecordFailureRollsBackApprovalConsumptionAndSource() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        seedSupply(request, supply("단지", "A"), supply("단지", "B"));
        var previous = view();
        long approvalId = approve(request, supply("단지", "A"));
        jdbc.execute("ALTER TABLE source_collection_records ADD CONSTRAINT test_reject_success "
                + "CHECK (status <> 'SUCCESS' OR stored_row_count <> 1)");
        try {
            expect(request, "dsList01", List.of(supplyWire(supply("단지", "A"))));

            assertThatThrownBy(() -> collector.collect(request)).isInstanceOf(RuntimeException.class);

            assertThat(view()).isEqualTo(previous);
            assertThat(consumed(approvalId)).isNull();
            assertFailed();
        } finally {
            jdbc.execute("ALTER TABLE source_collection_records DROP CONSTRAINT test_reject_success");
        }
    }

    @Test
    void verifiedEmptyBaselineSurvivesParserVersionChange() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 6);
        seedSupply(request, supply("단지", "A"));
        approve(request);
        expect(request, "dsList01", List.of());
        collector.collect(request);
        http.reset();
        var upgraded = request(CollectionSource.LH_ANNOUNCEMENT_SUPPLY, "PAN-A", "050", 7);
        expect(upgraded, "dsList01", List.of());

        collector.collect(upgraded);

        assertThat(view()).singleElement().satisfies(source -> {
            assertThat(source.supplies()).isEmpty();
            assertThat(source.verifiedEmpty()).isTrue();
            assertThat(source.requestHash()).isEqualTo(upgraded.requestHash());
        });
    }

    @Test
    void emptyDetailCannotReplacePreviousSuccessfulDetails() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_DETAIL, "PAN-A", "050", 6);
        expect(request, "dsSbd", List.of(Map.of("LCC_NT_NM", " 단지 ", "LGDN_ADR", " 원문 주소 ")));
        collector.collect(request);
        var previous = view();
        http.reset();
        expect(request, "dsSbd", List.of());

        assertThatThrownBy(() -> collector.collect(request)).isInstanceOf(EmptyLhDetailReplacementException.class);

        assertThat(view()).isEqualTo(previous);
        assertFailed();
    }

    @Test
    void detailKeepsResponseFieldsAndAllowsExistingPartialDatasetReplacement() {
        var request = request(CollectionSource.LH_ANNOUNCEMENT_DETAIL, "PAN-A", "050", 6);
        expect(request, "dsSbd", List.of(Map.of("LCC_NT_NM", " 단지 ", "LGDN_ADR", " 원문 주소 "),
                Map.of("LCC_NT_NM", " 단지 B ", "LGDN_ADR", " 주소 B ")));
        collector.collect(request);
        http.reset();
        expect(request, "dsSbd", List.of(Map.of("LCC_NT_NM", " 단지 ", "LGDN_ADR", " 새 주소 ")));

        collector.collect(request);

        assertThat(view()).singleElement().satisfies(source ->
                assertThat(source.details()).singleElement().satisfies(row -> {
                    assertThat(row.complexName()).isEqualTo(" 단지 ");
                    assertThat(row.address()).isEqualTo(" 새 주소 ");
                }));
    }

    private LhAnnouncementCollectionRequest request(
            CollectionSource source, String panId, String supplyType, int version
    ) {
        return new LhAnnouncementCollectionRequest(EXECUTION_ID, source,
                new LhAnnouncementQuery(panId, "01", "06", "10", supplyType), version, STARTED_AT);
    }

    private void seedSupply(LhAnnouncementCollectionRequest request, LhAnnouncementSupplySourceSnapshot... snapshots) {
        UUID id = history.start(request);
        storage.completeSupply(id, request, COLLECTED_AT, List.of(snapshots));
    }

    private long approve(LhAnnouncementCollectionRequest request, LhAnnouncementSupplySourceSnapshot... snapshots) {
        List<LhAnnouncementSupplySource> views = new ArrayList<>();
        for (int index = 0; index < snapshots.length; index++) {
            views.add(LhAnnouncementSupplySource.read(null, index, request.query().panId(),
                    request.requestHash(), null, snapshots[index]));
        }
        return approvals.approve(request.requestHash(), LhSupplySnapshot.fingerprint(views),
                "https://apply.lh.or.kr/evidence", "공식 근거 확인", "test-admin");
    }

    private java.sql.Timestamp consumed(long id) {
        return jdbc.queryForObject("SELECT consumed_at FROM verified_lh_supply_replacements WHERE id = ?",
                java.sql.Timestamp.class, id);
    }

    private List<SourceView> view() {
        return new TransactionTemplate(transactionManager).execute(status -> sources.findAll().stream()
                .sorted(java.util.Comparator.comparing(LhAnnouncementQuerySource::getQueryHash))
                .map(source -> new SourceView(source.getId(), source.getVersion(), source.getQueryHash(),
                        source.getRequestHash(), source.getCollectedAt(), source.isVerifiedEmpty(),
                        source.getLastCollectionRecord().getId(),
                        supplies.findAllBySourceIdOrderBySourceOrderAsc(source.getId()).stream()
                                .map(LhAnnouncementSupplyRow::snapshot).toList(),
                        details.findAllBySourceIdOrderBySourceOrderAsc(source.getId()).stream()
                                .map(LhAnnouncementDetailRow::snapshot).toList())).toList());
    }

    private void assertFailed() {
        assertThat(records.findAll()).filteredOn(record -> record.getStatus() == CollectionStatus.FAILED).hasSize(1);
        assertThat(records.findAll()).noneMatch(record -> record.getStatus() == CollectionStatus.RUNNING);
    }

    private void expect(LhAnnouncementCollectionRequest request, String dataset, List<?> rows) {
        expectPayload(request, payload(request, dataset, rows));
    }

    private void expectPayload(LhAnnouncementCollectionRequest request, String payload) {
        http.expect(httpRequest -> {
            var query = UriComponentsBuilder.fromUri(httpRequest.getURI()).build().getQueryParams();
            request.query().parameters().forEach((name, value) -> assertThat(query.getFirst(name)).isEqualTo(value));
        }).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
    }

    private String payload(LhAnnouncementCollectionRequest request, String dataset, List<?> rows) {
        return mapper.writeValueAsString(List.of(Map.of("dsSch", List.of(request.query().parameters())),
                Map.of(dataset, rows), Map.of("resHeader", List.of(Map.of("SS_CODE", "Y")))));
    }

    private Map<String, String> supplyWire(LhAnnouncementSupplySourceSnapshot row) {
        return Map.of("SBD_LGO_NM", row.complexLabel(), "HTY_NNA", row.typeName(),
                "DDO_AR", row.exclusiveArea(), "SPL_AR", row.supplyArea(), "HSH_CNT", row.totalUnitCount(),
                "NOW_HSH_CNT", row.suppliedUnitCount(), "LS_GMY", row.depositText(), "RFE", row.monthlyRentText());
    }

    private LhAnnouncementSupplySourceSnapshot supply(String complexName, String typeName) {
        return new LhAnnouncementSupplySourceSnapshot(complexName, typeName, "59.123456789000",
                "70.000", "0100", "020", " 12,345 ", " 00300 ");
    }

    private record SourceView(
            Long id, long version, String queryHash, String requestHash, Instant collectedAt, boolean verifiedEmpty,
            UUID recordId, List<LhAnnouncementSupplySourceSnapshot> supplies,
            List<LhAnnouncementDetailSourceSnapshot> details
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {LhAnnouncementQuerySource.class, LhAnnouncementSupplyRow.class,
            LhAnnouncementDetailRow.class, SourceCollectionRecord.class})
    @EnableJpaRepositories(basePackageClasses = {LhAnnouncementQuerySourceRepository.class,
            LhAnnouncementSupplyRowRepository.class, LhAnnouncementDetailRowRepository.class,
            SourceCollectionRecordRepository.class})
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

        @Bean("lhAnnouncementOpenApiClient")
        DataGoKrOpenApiClient client(RestClient.Builder builder, MockRestServiceServer server, ObjectMapper mapper) {
            return new DataGoKrOpenApiClient(builder.build(), mapper, "https://example.com", "test-key",
                    "LH", new LhResponseStatusValidator());
        }
        @Bean
        LhAnnouncementCircuitBreaker breaker(Clock clock) {
            return new LhAnnouncementCircuitBreaker(clock, new SimpleMeterRegistry());
        }

        @Bean
        JdbcClient jdbcClient(JdbcTemplate jdbc) {
            return JdbcClient.create(jdbc);
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
                connection.createStatement().execute("CREATE TABLE supply_targets (id BIGINT PRIMARY KEY)");
                for (String script : List.of("V20261004_01__myhome_announcement_collection_storage.sql",
                        "V20261004_05__lh_announcement_query_collection_storage.sql",
                        "V20260928_04__verified_lh_supply_replacements.sql",
                        "V20260926_01__ingest_execution_ownership.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + script));
                }
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource(
                            "db/migration/V20261004_06__source_import_metadata_and_announcement_lifecycle.sql")
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception failure) {
                throw new IllegalStateException("LH 공급·상세 저장 테스트 스키마를 준비하지 못했습니다.", failure);
            }
            environment.getPropertySources().addFirst(new MapPropertySource("lhQuerySchema", Map.of(
                    "spring.datasource.url", url + "?currentSchema=" + SCHEMA,
                    "spring.jpa.properties.hibernate.default_schema", SCHEMA)));
        }
    }
}
