package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.controller.MyHomeInitialRegistrationController;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationLocation;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.pipeline.configuration.IngestWriteOwnershipConfiguration;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.IngestExecutionOwnershipRepository;
import com.toadzip.backend.ingest.pipeline.repository.IngestOwnershipLostException;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMonitor;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionStateService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({MyHomeInitialRegistrationExecutionService.class, MyHomeInitialRegistrationWriter.class,
        MyHomeInitialRegistrationService.class, MyHomeInitialRegistrationAssessment.class,
        MyHomeComplexSourceMapper.class, InitialRegistrationProductStore.class,
        InitialRegistrationInputs.class, MyHomeInitialRegistrationSupplementService.class,
        IngestWriteOwnershipConfiguration.class, IngestWriteOwnershipGuard.class,
        IngestExecutionOwnershipRepository.class, DataPipelineExecutionLock.class,
        IngestExecutionOwnershipService.class,
        MyHomeInitialRegistrationIntegrationTest.Config.class})
class MyHomeInitialRegistrationIntegrationTest {

    private static final String ADDRESS = "대전광역시 서구 둔산로 100";
    private static final String PNU = "3017011300102180007";
    @Autowired private MyHomeInitialRegistrationExecutionService execution;
    @Autowired private MyHomeInitialRegistrationService preparation;
    @Autowired private MyHomeInitialRegistrationWriter writer;
    @Autowired private MyHomeInitialRegistrationSupplementService supplements;
    @Autowired private com.toadzip.backend.ingest.mapping.repository.InitialRegistrationFailureRepository failures;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private MyHomeComplexLinkRepository links;
    @Autowired private InitialRegistrationTargetRepository targets;
    @Autowired private AdminDataChangeRepository changes;
    @Autowired private JdbcClient jdbc;
    @Autowired private IngestExecutionOwnershipService executionOwnership;
    @MockitoBean private MyHomeComplexSourceReader sources;
    @MockitoBean private RoadAddressGeocodingService geocoding;
    private IngestExecutionOwnershipService.Execution ownership;

    @BeforeEach
    void prepareCoordinates() {
        ownership = executionOwnership.acquire();
        when(geocoding.geocode(any())).thenAnswer(invocation -> new GeocodedRoadAddress(
                invocation.getArgument(0), new BigDecimal("36.35"), new BigDecimal("127.38")));
    }

    @AfterEach
    void clean() {
        try {
            jdbc.sql("ALTER TABLE housing_types DROP CONSTRAINT IF EXISTS registration_test_failure").update();
            failures.deleteAll();
            changes.deleteAll();
            links.deleteAll();
            types.deleteAll();
            complexes.deleteAll();
            targets.deleteAll();
        }
        finally {
            if (ownership != null) {
                ownership.close();
            }
        }
    }

    @Test
    void 등록_HTTP_접수_후_작업_스레드의_실제_쓰기_검증기를_통과해_단지와_주택형을_저장한다() throws Exception {
        rows(List.of(source(123, "26A"), source(123, "26B")));
        var scheduler = org.mockito.Mockito.mock(
                DataPipelineExecutionService.class);
        var queued = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Callable<
                com.toadzip.backend.ingest.mapping.dto.InitialRegistrationRunResponse>>();
        org.mockito.Mockito.when(scheduler.start(
                DataPipelineType.COMPLEX_REGISTRATION, "admin"))
                .thenAnswer(invocation -> {
                    String actor = invocation.getArgument(1);
                    queued.set(IngestExecutionScope.propagate(() -> execution.register(actor)));
                    return DataPipelineExecutionResponse.idle(DataPipelineType.COMPLEX_REGISTRATION);
                });
        var mvc = MockMvcBuilders.standaloneSetup(
                new MyHomeInitialRegistrationController(preparation, scheduler, supplements)).build();

        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/register").principal(() -> "admin"))
                .andExpect(status().isAccepted());
        assertThat(complexes.count()).isZero();
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThat(worker.submit(queued.get()).get().counts().get(InitialRegistrationStatus.COMPLETED)).isEqualTo(1L);
        }

        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.count()).isEqualTo(2);
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void 단지_사이에서_중지하면_완료한_단지는_남고_다음_단지는_등록하지_않는다() {
        rows(List.of(source(123, "26A"), source(124, "26A")));
        var state = org.mockito.Mockito.mock(
                DataPipelineExecutionStateService.class);
        var id = UUID.randomUUID();
        org.mockito.Mockito.when(state.isStopRequested(id)).thenReturn(false, true);
        var monitor = new DataPipelineExecutionMonitor(id, state,
                Clock.systemUTC());
        try (var scope = IngestExecutionScope.open(IngestExecutionScope.current().orElseThrow(), monitor)) {
            assertThatThrownBy(() -> execution.register("admin"))
                    .isInstanceOf(DataPipelineStoppedException.class);
        }
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.COMPLETED);
        assertThat(targets.findById("124:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.READY);
    }

    @Test
    void 실행_소유권을_잃으면_실제_쓰기_검증기가_단지_생성을_차단한다() {
        ready(123);
        jdbc.sql("UPDATE ingest_execution_ownership SET generation = generation + 1 WHERE id = 1").update();

        assertThatThrownBy(() -> writer.register("123:PERMANENT_RENTAL", "admin"))
                .isInstanceOf(IngestOwnershipLostException.class);

        assertThat(complexes.count()).isZero();
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.READY);
    }

    @Test
    void 신규_단지와_주택형_원천_이력을_함께_저장하고_재요청은_중복_생성하지_않는다() {
        rows(List.of(source(123, "26A"), source(123, "26B")));
        var result = execution.register("admin");
        assertThat(result.targetCount()).isEqualTo(1);
        assertThat(result.uniqueAddressCount()).isEqualTo(1);
        assertThat(result.counts().get(InitialRegistrationStatus.COMPLETED)).isEqualTo(1);
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.count()).isEqualTo(2);
        assertThat(links.count()).isEqualTo(1);
        var completed = targets.findById("123:PERMANENT_RENTAL").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(InitialRegistrationStatus.COMPLETED);
        assertThat(changes.findAll()).singleElement().satisfies(change -> {
            assertThat(change.getActor()).isEqualTo("admin");
            assertThat(change.getBeforeValue()).isEqualTo("{}");
            assertThat(change.getAfterValue()).contains("26A", "26B", "sourceValues", "appliedValues", "location");
        });
        rows(List.of());
        assertThat(execution.register("admin").targetCount()).isZero();
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getHousingComplexId())
                .isEqualTo(completed.getHousingComplexId());
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.count()).isEqualTo(2);
        assertThat(changes.count()).isEqualTo(1);
    }

    @Test
    void 한_단지의_저장이_실패하면_부분_저장_없이_다른_단지를_계속_등록한다() {
        jdbc.sql("ALTER TABLE housing_types ADD CONSTRAINT registration_test_failure CHECK (name <> '실패형')")
                .update();
        rows(List.of(source(123, "26A"), source(123, "실패형"), source(456, "46A")));
        var result = execution.register("admin");
        assertThat(result.counts().get(InitialRegistrationStatus.COMPLETED)).isEqualTo(1);
        assertThat(result.counts().get(InitialRegistrationStatus.FAILED)).isEqualTo(1);
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.findAll()).extracting(HousingType::getName).containsExactly("46A");
        assertThat(links.count()).isEqualTo(1);
        assertThat(changes.count()).isEqualTo(1);
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.FAILED);
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getHousingComplexId()).isNull();
        execution.register("admin");
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.FAILED);
    }

    @Test
    void PDF로_먼저_등록한_단지와_주택형은_갱신하거나_삭제하지_않는다() {
        var complex = existing("ADMIN_ENTRY-HOUSING-COMPLEX-test");
        var type = types.save(HousingType.createFromAnnouncement(complex, "ADMIN_ENTRY-HOUSING-TYPE-test", "26A",
                new BigDecimal("26"), new BigDecimal("38"), 10));
        rows(List.of(source(123, "26")));
        var result = execution.register("admin");
        assertThat(result.counts().get(InitialRegistrationStatus.EXISTING)).isEqualTo(1);
        assertThat(complexes.findById(complex.getId()).orElseThrow().getTotalHouseholdCount()).isEqualTo(777);
        assertThat(types.findById(type.getId()).orElseThrow().getName()).isEqualTo("26A");
        assertThat(links.count()).isZero();
        assertThat(changes.count()).isZero();
        rows(List.of());
        execution.register("admin");
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.count()).isEqualTo(1);
    }

    @Test
    void 관리자_보완_주소에_기존_PDF_단지가_있으면_다른_원천값이어도_생성을_보류한다() {
        complexes.saveAndFlush(HousingComplex.createFromMyHome("PDF 단지", "ADMIN_ENTRY-HOUSING-COMPLEX-address",
                "PERMANENT_RENTAL", Address.create(ADDRESS, null, "3017011300", "30", "30170",
                        new BigDecimal("36.35"), new BigDecimal("127.38")),
                100, "LH", null, null, null, null, null, 100));
        var target = InitialRegistrationTarget.create("123:PERMANENT_RENTAL");
        target.assess("원천 단지", null, InitialRegistrationStatus.READY, "보완됨", null, Instant.EPOCH);
        target.prepareLocation(InitialRegistrationLocation.prepared(null, ADDRESS,
                new BigDecimal("36.35"), new BigDecimal("127.38"), true, "admin", Instant.EPOCH));
        targets.saveAndFlush(target);
        rows(List.of(sourceAt(123, "26A", null, PNU, "원천 단지")));

        assertThat(writer.register("123:PERMANENT_RENTAL", "admin"))
                .isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(links.count()).isZero();
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getDetail()).contains("기존 후보");
    }

    @Test
    void 저장_요청이_겹쳐도_대상_행_잠금으로_한_번만_생성한다() throws Exception {
        ready(123);
        rows(List.of(source(123, "26A")));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<InitialRegistrationStatus> register = IngestExecutionScope.propagate(() -> {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return writer.register("123:PERMANENT_RENTAL", "admin");
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(register);
            var second = executor.submit(register);
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(InitialRegistrationStatus.COMPLETED);
            assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(InitialRegistrationStatus.COMPLETED);
        }
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.count()).isEqualTo(1);
        assertThat(links.count()).isEqualTo(1);
        assertThat(changes.count()).isEqualTo(1);
    }

    @Test
    void 준비_후_필수_정보가_빠지면_저장_직전에_보완_필요로_남긴다() {
        ready(123);
        rows(List.of(source(123, null)));
        assertThat(writer.register("123:PERMANENT_RENTAL", "admin"))
                .isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(complexes.count()).isZero();
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getDetail()).contains("주택형명");
    }

    @Test
    void 준비_후_같은_단지가_등록되면_저장_직전에_건너뛴다() {
        ready(123);
        rows(List.of(source(123, "26A")));
        existing("123:PERMANENT_RENTAL");
        assertThat(writer.register("123:PERMANENT_RENTAL", "admin")).isEqualTo(InitialRegistrationStatus.EXISTING);
        assertThat(complexes.count()).isEqualTo(1);
        assertThat(types.count()).isZero();
    }

    private void ready(long id) {
        var target = InitialRegistrationTarget.create(id + ":PERMANENT_RENTAL");
        target.assess("단지", ADDRESS, InitialRegistrationStatus.READY, "준비됨", null, Instant.EPOCH);
        target.prepareLocation(InitialRegistrationLocation.prepared(ADDRESS, ADDRESS,
                new BigDecimal("36.35"), new BigDecimal("127.38"), true, "admin", Instant.EPOCH));
        targets.saveAndFlush(target);
    }

    @Test
    void 여러_페이지에서_선택한_이백곳_초과_대상을_모두_재평가한다() {
        rows(List.of());
        var identifiers = java.util.stream.LongStream.rangeClosed(1, 201)
                .mapToObj(id -> id + ":PERMANENT_RENTAL").toList();
        for (long id = 1; id <= 201; id++) {
            ready(id);
        }

        var result = execution.retry("admin", identifiers);

        assertThat(result.targetCount()).isEqualTo(201);
        assertThat(result.counts().get(InitialRegistrationStatus.INFORMATION_REQUIRED)).isEqualTo(201);
        assertThat(failures.count()).isEqualTo(201);
        assertThat(complexes.count()).isZero();
    }

    @Test
    void 정보_부족으로_재처리가_보류되면_사유를_이력에_남긴다() {
        rows(List.of(source(123, null)));
        preparation.refresh();

        var result = execution.retry("retry-admin", List.of("123:PERMANENT_RENTAL"));

        assertThat(result.counts().get(InitialRegistrationStatus.INFORMATION_REQUIRED)).isEqualTo(1);
        assertThat(supplements.failures("123:PERMANENT_RENTAL", 0).failures())
                .extracting(com.toadzip.backend.ingest.mapping.dto.InitialRegistrationFailuresResponse.Failure::detail)
                .singleElement().asString().contains("재처리 보류", "주택형명");
        assertThat(complexes.count()).isZero();
    }

    @Test
    void 저장_실패한_선택_대상만_재처리하고_성공_후에도_실패_이력은_유지한다() {
        rows(List.of(source(123, "26A"), source(456, "26B")));
        preparation.refresh();
        writer.recordFailure("123:PERMANENT_RENTAL", "admin");

        assertThat(execution.register("admin").counts().get(InitialRegistrationStatus.COMPLETED)).isEqualTo(1);
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.FAILED);
        var result = execution.retry("admin", List.of("123:PERMANENT_RENTAL", "456:PERMANENT_RENTAL"));
        assertThat(result.targetCount()).isEqualTo(1);
        assertThat(result.counts().get(InitialRegistrationStatus.COMPLETED)).isEqualTo(1);
        assertThat(complexes.count()).isEqualTo(2);
        assertThat(supplements.failures("123:PERMANENT_RENTAL", 0).totalElements()).isEqualTo(1);
        assertThat(execution.retry("admin", List.of("123:PERMANENT_RENTAL")).targetCount()).isZero();
        assertThat(complexes.count()).isEqualTo(2);
    }

    @Test
    void 재시도_저장도_롤백하며_이전과_현재_실패를_각각_기록한다() {
        rows(List.of(source(123, "26A")));
        preparation.refresh();
        writer.recordFailure("123:PERMANENT_RENTAL", "first-admin");
        jdbc.sql("ALTER TABLE housing_types ADD CONSTRAINT registration_test_failure CHECK (name <> '26A')").update();

        var result = execution.retry("retry-admin", List.of("123:PERMANENT_RENTAL"));
        assertThat(result.counts().get(InitialRegistrationStatus.FAILED)).isEqualTo(1);
        assertThat(complexes.count()).isZero();
        assertThat(types.count()).isZero();
        assertThat(links.count()).isZero();
        assertThat(changes.count()).isZero();
        assertThat(supplements.failures("123:PERMANENT_RENTAL", 0).failures())
                .extracting(com.toadzip.backend.ingest.mapping.dto.InitialRegistrationFailuresResponse.Failure::actor)
                .containsExactly("retry-admin", "first-admin");
    }

    @Test
    void 주택형명을_근거로_보완해_원천을_유지한_채_선택_등록한다() {
        var source = source(123, null);
        rows(List.of(source));
        preparation.refresh();
        var information = supplements.find("123:PERMANENT_RENTAL");
        supplements.supplement(new com.toadzip.backend.ingest.mapping.dto.InitialRegistrationInformationRequest(
                "123:PERMANENT_RENTAL", information.fingerprint(), null,
                java.util.Map.of(source.getSourceKey(), "26A"), "공식 공고문 3쪽"), "admin");
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.READY);

        execution.retry("admin", List.of("123:PERMANENT_RENTAL"));
        assertThat(types.findAll()).extracting(HousingType::getName).containsExactly("26A");
        assertThat(source.getStyleNm()).isNull();
        assertThat(changes.findAll().getFirst().getAfterValue()).contains("공식 공고문 3쪽");
    }

    @Test
    void 보완_후_원천이_변경되면_이전_보완값으로_등록하지_않는다() {
        var source = source(123, null);
        rows(List.of(source));
        preparation.refresh();
        var information = supplements.find("123:PERMANENT_RENTAL");
        var request = new com.toadzip.backend.ingest.mapping.dto.InitialRegistrationInformationRequest(
                "123:PERMANENT_RENTAL", information.fingerprint(), null,
                java.util.Map.of(source.getSourceKey(), "26A"), "공식 공고문 3쪽");
        supplements.supplement(request, "admin");
        rows(List.of(sourceAt(123, null, ADDRESS, PNU, "변경된 단지명")));
        assertThatThrownBy(() -> supplements.supplement(request, "admin"))
                .isInstanceOf(com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException.class)
                .hasMessageContaining("변경");
        execution.retry("admin", List.of("123:PERMANENT_RENTAL"));
        assertThat(complexes.count()).isZero();
        assertThat(targets.findById("123:PERMANENT_RENTAL").orElseThrow().getStatus())
                .isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(supplements.find("123:PERMANENT_RENTAL").supplement().current()).isFalse();
    }

    private HousingComplex existing(String identifier) {
        return complexes.saveAndFlush(HousingComplex.createFromMyHome("테스트123", identifier, "PERMANENT_RENTAL",
                Address.create(ADDRESS, PNU, "3017011300", "30", "30170", new BigDecimal("36.35"),
                        new BigDecimal("127.38")), 777, "LH", null, null, null, null, null, 100));
    }

    private void rows(List<MyHomeComplexSourceView> rows) {
        when(sources.findAll()).thenReturn(rows);
        doAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            return rows.stream().filter(row -> ids.contains(row.getHsmpSn())).toList();
        }).when(sources).findAllByHsmpSnIn(any());
    }

    private MyHomeComplexSourceView source(long id, String style) {
        String pnu = PNU;
        String address = ADDRESS;
        if (id != 123) {
            pnu = PNU.substring(0, 15) + String.format("%04d", id);
            address = "대전광역시 서구 둔산로 101";
        }
        return sourceAt(id, style, address, pnu, "테스트" + id);
    }

    private MyHomeComplexSourceView sourceAt(long id, String style, String address, String pnu, String name) {
        return MyHomeSourceViews.complex(new MyHomeComplexSourceSnapshot(id, "LH", "30", "대전광역시", "170",
                "서구", name, address, pnu, "19930607", 100,
                "영구임대", style, new BigDecimal("26"), new BigDecimal("12"), "아파트", null, null, null,
                100, 1000L, 100L, null));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
    }
}
