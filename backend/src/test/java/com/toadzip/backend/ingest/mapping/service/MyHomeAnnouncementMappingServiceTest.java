package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.announcement.service.AnnouncementQueryService;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.repository.CollectedSourceRows;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementSupplySourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.history.repository.SourceCollectionRecordRepository;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.projection.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementCollectionRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementStorageService;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementApiRepository;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class MyHomeAnnouncementMappingServiceTest {

    @Autowired
    private CollectedSourceRows fixtures;

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-28T00:00:00Z");

    private static final String PNU = "1111010100100010000";

    @Autowired
    private MyHomeAnnouncementMappingService service;

    @Autowired
    private MyHomeAnnouncementSourceFixtures sourceRepository;

    @Autowired
    private MyHomeAnnouncementMappingFailureRepository failureRepository;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private AnnouncementQueryService announcementQueryService;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private SupplyTargetRepository supplyTargetRepository;

    @Autowired
    private HousingComplexRepository complexRepository;

    @Autowired
    private HousingTypeRepository housingTypeRepository;


    @Autowired
    private LhAnnouncementSupplySourceFixtures lhSupplyRepository;

    @Autowired
    private LhAnnouncementCollectionLinkRepository linkRepository;

    @Autowired
    private LhAnnouncementCollectionProgressStore progressStore;

    @Autowired
    private LhAnnouncementCollectionCandidateResolver candidateResolver;

    @Autowired
    private LhStorageFixtures lhSourceStore;

    @Autowired
    private MyHomeAnnouncementStorageService collectedSourceStorage;

    @Autowired
    private MyHomeAnnouncementCollectionRepository collectedSources;

    @Autowired
    private SourceCollectionRecordService collectionHistory;

    @Autowired
    private SourceCollectionRecordRepository collectionRecords;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private MyHomeAnnouncementApiRepository announcementApi;

    @Autowired
    private DataPipelineExecutionService pipeline;

    @Autowired
    private DataPipelineExecutionRepository executions;

    @Autowired
    private DataPipelineExecutionLock pipelineLock;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        executions.deleteAll();
        fixtures.clear();
        supplyTargetRepository.deleteAll();
        supplyRowRepository.deleteAll();
        announcementRepository.deleteAll();
        housingTypeRepository.deleteAll();
        complexRepository.deleteAll();
        failureRepository.deleteAll();
        sourceRepository.deleteAll();
        linkRepository.deleteAll();
        lhSupplyRepository.deleteAll();
        collectedSources.deleteAll();
        collectionRecords.deleteAll();
    }

    @Test
    void 단건_비동기_실행은_원천_확보부터_등록_완료까지_대상만_처리한다() throws Exception {
        saveMappedComplex();
        var rows = List.of(data("21026", 1, "부산도시공사", "동삼2"),
                        data("21026", 2, "부산도시공사", "동삼2"), data("other", 1, "부산도시공사", "동삼2"))
                .stream().map(row -> json.convertValue(row,
                        com.toadzip.backend.ingest.collection.myhome.announcement.domain.
                                MyHomeAnnouncementSourceSnapshot.class)).toList();
        org.mockito.Mockito.when(announcementApi.fetch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
                    MyHomeAnnouncementCollectionRequest request = invocation.getArgument(0);
                    if ("01".equals(request.supplyTypeCode())) {
                        return new SourcePage<>(3, rows);
                    }
                    return new SourcePage<>(0, List.of());
                });

        var accepted = pipeline.startAnnouncementRegistration("21026");
        var completed = awaitRegistration(accepted.executionId());

        assertThat(accepted.status()).isEqualTo(DataPipelineExecutionStatus.RUNNING);
        assertThat(completed.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        assertThat(completed.targetAnnouncementIdentifier()).isEqualTo("21026");
        assertThat(completed.completedSteps()).containsExactly("마이홈 공고 수집", "마이홈 공고 정제");
        assertThat(completed.skippedSteps()).hasSize(2).allSatisfy(step ->
                assertThat(step.reason()).contains("해당 없음"));
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getSourceAnnouncementIdentifier()).isEqualTo("21026"));
        assertThat(supplyRowRepository.count()).isEqualTo(2);
        assertThat(sourceRepository.findAll()).hasSize(2);
    }

    @Test
    void 단건_비동기_실행은_없는_ID의_실패_사유를_상태에_저장한다() throws Exception {
        org.mockito.Mockito.when(announcementApi.fetch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(new SourcePage<>(0, List.of()));

        var accepted = pipeline.startAnnouncementRegistration("missing");
        var failed = awaitRegistration(accepted.executionId());

        assertThat(failed.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(failed.failure().message()).contains("공고를 찾을 수 없습니다");
        assertThat(announcementRepository.count()).isZero();
        assertThat(supplyRowRepository.count()).isZero();
    }

    private com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse awaitRegistration(
            UUID executionId
    ) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var response = pipeline.find(executionId);
            if (response.status() != DataPipelineExecutionStatus.RUNNING && !pipelineLock.isHeld()) {
                return response;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("단건 등록 실행이 제한 시간 안에 종료되지 않았습니다.");
    }

    @Test
    void 단건_등록은_이번_수집에_없는_과거_공급행을_함께_등록하지_않는다() {
        saveMappedComplex();
        storeCollectedHouses(COLLECTED_AT, List.of(1, 2));
        UUID executionId = UUID.randomUUID();
        var request = new MyHomeAnnouncementCollectionRequest(executionId, "01", 500, 1000,
                COLLECTED_AT.plusSeconds(59), "21026");
        var row = json.convertValue(data("21026", 1, "부산도시공사", "동삼2"),
                com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot.class);
        collectedSourceStorage.complete(collectionHistory.start(request), request,
                new MyHomeAnnouncementCollectedResponse(1, COLLECTED_AT.plusSeconds(60), List.of(row)));

        org.slf4j.MDC.put("executionId", executionId.toString());
        try {
            service.registerAnnouncement("21026");
        }
        finally {
            org.slf4j.MDC.remove("executionId");
        }

        assertThat(supplyRowRepository.count()).isOne();
        assertThat(sourceRepository.findAll()).hasSize(2);
    }

    @Test
    void 단건_등록은_이전_공고를_자동_등록하지_않는다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        sourceRepository.save(source(1, withPrevious(data("21027", 1, "부산도시공사", "동삼2"), "21026")));

        assertThatThrownBy(() -> service.registerAnnouncement("21027")).hasMessageContaining("이전 공고");

        assertThat(announcementRepository.count()).isZero();
        assertThat(sourceRepository.findAll()).hasSize(2);
    }

    @Test
    void 단건_실패_후_재시도는_해당_공고_실패만_해결한다() {
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        var other = failureRepository.save(MyHomeAnnouncementMappingFailure.create(
                "other:1", "other", 1, MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                "다른 공고의 실패", COLLECTED_AT));
        assertThatThrownBy(() -> service.registerAnnouncement("21026")).hasMessageContaining("매칭");
        saveMappedComplex();

        service.registerAnnouncement("21026");

        assertThat(failureRepository.findById(other.getId()).orElseThrow().getStatus())
                .isEqualTo(IngestFailureStatus.PENDING);
        assertThat(failureRepository.findAllBySourceAnnouncementIdentifier("21026"))
                .isNotEmpty().allSatisfy(failure ->
                        assertThat(failure.getStatus()).isEqualTo(IngestFailureStatus.RESOLVED));
        assertThat(announcementRepository.count()).isOne();
    }

    @Test
    void 단건_LH_보강_실패는_공고와_공급행을_모두_롤백하고_원천으로_재시도할_수_있다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveDefaultLhSupply("21026");

        assertThatThrownBy(() -> service.registerAnnouncement("21026")).hasMessageContaining("LH 보강 실패");
        assertThat(announcementRepository.count()).isZero();
        assertThat(supplyRowRepository.count()).isZero();
        assertThat(sourceRepository.findAll()).hasSize(1);
        assertThat(lhSupplyRepository.findAll()).hasSize(1);

        lhSourceStore.replaceDetails("21026", lhRequestDescription("21026"), List.of(
                new LhAnnouncementDetailSource(
                        0, "21026", "ETC_INFO", null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        "정정 사유", null)));
        service.registerAnnouncement("21026");

        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getLhPanId()).isEqualTo("21026"));
        assertThat(supplyRowRepository.count()).isOne();
    }

    @Test
    void 단건_등록은_대상_공고의_모든_공급행만_저장한다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        sourceRepository.save(source(1, data("21026", 2, "부산도시공사", "동삼2")));
        sourceRepository.save(source(2, data("21027", 1, "부산도시공사", "동삼2")));

        var report = service.registerAnnouncement("21026");

        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(report.createdSupplyRowCount()).isEqualTo(2);
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getSourceAnnouncementIdentifier()).isEqualTo("21026"));
        assertThat(sourceRepository.findAll()).hasSize(3);
    }

    @Test
    void 단건_등록의_매칭_실패는_공고와_공급행을_롤백하고_원천은_보존한다() {
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "없는 단지")));

        assertThatThrownBy(() -> service.registerAnnouncement("21026"))
                .hasMessageContaining("매칭");

        assertThat(announcementRepository.count()).isZero();
        assertThat(supplyRowRepository.count()).isZero();
        assertThat(sourceRepository.findAll()).hasSize(1);
        assertThat(failureRepository.findAll()).hasSize(1);
    }

    @Test
    void 단건_등록은_기존_공고를_수정하지_않는다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        service.mapAll();
        Long announcementId = announcementRepository.findAll().getFirst().getId();

        assertThatThrownBy(() -> service.registerAnnouncement("21026"))
                .hasMessageContaining("이미 등록");

        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getId()).isEqualTo(announcementId));
        assertThat(supplyRowRepository.count()).isOne();
    }

    @Test
    void 재수집에서_빠진_주택의_과거_공급행과_공급대상을_정제해도_보존한다() {
        saveMappedComplex();
        storeCollectedHouses(COLLECTED_AT, List.of(1, 2));
        assertThat(service.mapAll().failedSourceRowCount()).isZero();
        List<Long> previousRowIds = supplyRowRepository.findAll().stream().map(SupplyRow::getId).sorted().toList();
        SupplyRow missingHouse = supplyRow(MyHomeAnnouncementSource.sourceKeyOf(
                data("21026", 2, "부산도시공사", "동삼2")));
        SupplyTarget target = supplyTargetRepository.save(SupplyTarget.create(
                missingHouse, "청년", null, 1, null, null, null, null, null, 1));

        storeCollectedHouses(COLLECTED_AT.plusSeconds(60), List.of(1));
        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(report.deletedSupplyRowCount()).isZero();
        assertThat(supplyRowRepository.findAll().stream().map(SupplyRow::getId).sorted().toList())
                .containsExactlyElementsOf(previousRowIds);
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isEqualTo(target.getId());
            assertThat(saved.getSupplyRow().getId()).isEqualTo(missingHouse.getId());
        });
    }

    @Test
    void 과거_주택의_충돌도_정제_실패로_기록하고_다른_공고는_계속_처리한다() {
        saveMappedComplex();
        storeCollectedSources(COLLECTED_AT, List.of(
                withNameAndSupplyCount(data("21026", 2, "부산도시공사", "동삼2"), "국민임대 모집공고", 5),
                withNameAndSupplyCount(data("21026", 2, "부산도시공사", "동삼2"), "국민임대 모집공고", 6)
        ));
        storeCollectedSources(COLLECTED_AT.plusSeconds(60), List.of(
                data("21026", 1, "부산도시공사", "동삼2"),
                data("21027", 1, "부산도시공사", "동삼2")
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isEqualTo(3);
        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(announcementRepository.findBySourceAnnouncementIdentifier("21026")).isEmpty();
        assertThat(announcementRepository.findBySourceAnnouncementIdentifier("21027")).isPresent();
        assertThat(supplyRowRepository.count()).isOne();
        assertThat(failureRepository.findAll()).hasSize(2).allSatisfy(failure ->
                assertThat(failure.getReason())
                        .isEqualTo(MyHomeAnnouncementMappingFailureReason.CONFLICTING_SOURCE_VALUE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 관리자_보호로_기관변경을_거절하면_LH_파생행과_모든_공급대상을_보존한다(boolean deleted) {
        saveMappedComplex();
        MyHomeAnnouncementSource source = sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-1");
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "46A", "46.8000", "67.0000"),
                lhSupply(1, "PAN-1", "동삼2", "46A", "46.8000", "67.0000")
        ));
        assertThat(service.mapAll().createdSupplyRowCount()).isEqualTo(2);
        Announcement announcement = announcementRepository.findAll().getFirst();
        announcement.enrichFromLh("PAN-1", null, null);
        if (deleted) {
            announcement.moveToTrash();
        }
        if (!deleted) {
            announcement.reviseByAdmin(announcement);
        }
        announcementRepository.save(announcement);
        List<SupplyRow> rows = supplyRowRepository.findAll();
        List<Long> rowIds = rows.stream().map(SupplyRow::getId).toList();
        for (SupplyRow row : rows) {
            supplyTargetRepository.saveAll(List.of(
                    SupplyTarget.createFromSource(row, "LH:" + row.getId(), "일반", null, 1, null, null, 1),
                    SupplyTarget.createFromSource(row, "OTHER:" + row.getId(), "일반", null, 1, null, null, 2),
                    SupplyTarget.create(row, "수동", null, 1, null, null, null, null, "공고문 참조", 3)
            ));
        }
        List<Long> targetIds = supplyTargetRepository.findAll().stream().map(SupplyTarget::getId).toList();
        source.replaceWith(data("21026", 1, "서울주택도시공사", "동삼2"));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(report.updatedAnnouncementCount()).isZero();
        assertThat(report.updatedSupplyRowCount()).isZero();
        assertThat(report.deletedSupplyRowCount()).isZero();
        assertThat(report.unchangedSupplyRowCount()).isEqualTo(2);
        assertThat(supplyRowRepository.findAll()).extracting(SupplyRow::getId)
                .containsExactlyInAnyOrderElementsOf(rowIds);
        assertThat(supplyTargetRepository.findAll()).extracting(SupplyTarget::getId)
                .containsExactlyInAnyOrderElementsOf(targetIds);
        assertThat(announcementRepository.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getProvider()).isEqualTo(AgencyCode.LH);
            assertThat(stored.getLhPanId()).isEqualTo("PAN-1");
            if (!deleted) {
                assertThat(stored.isSourceReviewRequired()).isTrue();
            }
        });
    }

    @Test
    void 공급기관과_관계없이_같은_pblancId를_하나의_공고와_여러_공급행으로_매핑한다() {
        saveMappedComplex();
        sourceRepository.saveAll(List.of(
                source(0, data("21026", 1, "부산도시공사", "동삼2")),
                source(1, data("21026", 2, "부산도시공사", "동삼3"))
        ));

        var report = service.mapAll();

        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(report.createdSupplyRowCount()).isEqualTo(2);
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(announcementRepository.count()).isOne();
        assertThat(supplyRowRepository.findAll()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getHousingComplex()).isNotNull();
            assertThat(row.getHousingType()).isNotNull();
            assertThat(row.getMatchingFailureReason()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "5년임대, PUBLIC_RENTAL_5Y",
            "10년임대, PUBLIC_RENTAL_10Y"
    })
    void 공공임대_기간을_공고_임대유형으로_보존하고_단지와_연결한다(String sourceSupplyType, RentalType expectedType) {
        saveMappedComplex("동삼2", "123:" + expectedType.name(), expectedType.name());
        sourceRepository.save(source(0, withSupplyType(
                data("21026", 1, "부산도시공사", "동삼2"),
                sourceSupplyType
        )));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getSupplyType()).isEqualTo(expectedType));
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingComplex()).isNotNull();
            assertThat(row.getHousingType()).isNotNull();
        });
    }

    @Test
    void 기존에_기타로_저장된_5년임대_공고를_재매핑하면_정식_임대유형으로_교정한다() {
        saveMappedComplex("동삼2", "123:PUBLIC_RENTAL_5Y", "PUBLIC_RENTAL_5Y");
        sourceRepository.save(source(0, withSupplyType(
                data("21026", 1, "부산도시공사", "동삼2"),
                "5년임대"
        )));
        service.mapAll();
        Announcement stored = announcementRepository.findAll().getFirst();
        ReflectionTestUtils.setField(stored, "supplyType", RentalType.ETC);
        announcementRepository.save(stored);

        var report = service.mapAll();

        assertThat(report.updatedAnnouncementCount()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getSupplyType()).isEqualTo(RentalType.PUBLIC_RENTAL_5Y));
    }

    @Test
    void 같은_원본을_반복_매핑해도_공고와_공급행을_중복_생성하지_않는다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        service.mapAll();

        var report = service.mapAll();

        assertThat(report.unchangedAnnouncementCount()).isOne();
        assertThat(report.unchangedSupplyRowCount()).isOne();
        assertThat(announcementRepository.count()).isOne();
        assertThat(supplyRowRepository.count()).isOne();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void 공급기관이_바뀌면_기존_공급행들의_LH_공급대상만_삭제한다(int storedRowCount) {
        saveMappedComplex();
        var sources = List.of(
                source(0, data("21026", 1, "LH", "동삼2")),
                source(1, data("21026", 2, "LH", "동삼2")),
                source(2, data("21026", 3, "LH", "동삼2"))
        );
        sourceRepository.saveAll(sources);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver
                .resolve(sources.getFirst());
        completeLinks("21026", candidate);
        assertThat(service.mapAll().createdSupplyRowCount()).isEqualTo(3);
        Announcement announcement = announcementRepository.findAll().getFirst();
        announcement.enrichFromLh("21026", null, null);
        announcementRepository.save(announcement);
        if (storedRowCount == 0) {
            supplyRowRepository.deleteAll();
        }
        for (SupplyRow row : supplyRowRepository.findAll()) {
            supplyTargetRepository.saveAll(List.of(
                    SupplyTarget.createFromSource(row, "LH:" + row.getId(), "일반", null, 1, null, null, 1),
                    SupplyTarget.createFromSource(row, "OTHER:" + row.getId(), "일반", null, 1, null, null, 2),
                    SupplyTarget.create(row, "수동", null, 1, null, null, null, null, "공고문 참조", 3)
            ));
        }
        for (MyHomeAnnouncementSource source : sources) {
            source.replaceWith(data("21026", source.getHouseSn(), "서울주택도시공사", "동삼2"));
        }
        sourceRepository.saveAll(sources);

        var report = service.mapAll();

        assertThat(report.updatedAnnouncementCount()).isOne();
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getProvider()).isEqualTo(AgencyCode.SH);
            assertThat(stored.getLhPanId()).isNull();
        });
        assertThat(supplyTargetRepository.findAll()).hasSize(storedRowCount * 2)
                .allSatisfy(target -> assertThat(target.getSourceSupplyTargetIdentifier())
                        .satisfiesAnyOf(identifier -> assertThat(identifier).isNull(),
                                identifier -> assertThat(identifier).startsWith("OTHER:")));
    }

    @Test
    void 변경된_원본_정보를_기존_공고와_공급행에_반영한다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        service.mapAll();
        MyHomeAnnouncementSource source = sourceRepository.findAll().getFirst();
        MyHomeAnnouncementSourceSnapshot changed = data("21026", 1, "서울주택도시공사", "동삼2 변경");
        source.replaceWith(withNameAndSupplyCount(changed, "변경된 국민임대 모집공고", 35));
        source.markCollectedAt(COLLECTED_AT.plusSeconds(60));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.updatedAnnouncementCount()).isOne();
        assertThat(report.updatedSupplyRowCount()).isOne();
        assertThat(announcementRepository.findAll()).singleElement()
                .extracting(Announcement::getName)
                .isEqualTo("변경된 국민임대 모집공고");
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSourceComplexName()).isEqualTo("동삼2 변경");
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(35);
        });
    }

    @Test
    void 이전_공고_식별자로_정정공고를_연결한다() {
        saveMappedComplex();
        saveDefaultLhSupply("21026");
        saveDefaultLhSupply("21027");
        sourceRepository.saveAll(List.of(
                source(1, withPrevious(data("21027", 2, "LH서울", "동삼2"), "21026")),
                source(0, data("21026", 1, "LH서울", "동삼2"))
        ));

        var report = service.mapAll();

        assertThat(report.createdAnnouncementCount()).isEqualTo(2);
        Announcement correction = announcementRepository.findBySourceAnnouncementIdentifier("21027").orElseThrow();
        assertThat(correction.getStatus()).isEqualTo(AnnouncementPublicationType.CORRECTION);
        assertThat(correction.getPreviousSourceAnnouncementIdentifier()).isEqualTo("21026");
        assertThat(correction.getPreviousAnnouncement()).isNotNull();
    }

    @Test
    void 이전_공고가_순환하면_기존_공고가_있어도_두_공고를_저장하지_않는다() {
        saveMappedComplex();
        MyHomeAnnouncementSource original = source(
                0, data("21026", 1, "부산도시공사", "동삼2")
        );
        sourceRepository.save(original);
        assertThat(service.mapAll().createdAnnouncementCount()).isOne();

        original.replaceWith(withPrevious(data("21026", 1, "부산도시공사", "동삼2"), "21027"));
        sourceRepository.save(original);
        sourceRepository.save(source(
                1, withPrevious(data("21027", 2, "부산도시공사", "동삼2"), "21026")
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isEqualTo(2);
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getSourceAnnouncementIdentifier()).isEqualTo("21026");
            assertThat(announcement.getPreviousSourceAnnouncementIdentifier()).isNull();
        });
        assertThat(failureRepository.findAll())
                .extracting(failure -> failure.getReason())
                .containsExactlyInAnyOrder(
                        MyHomeAnnouncementMappingFailureReason.CYCLIC_ANNOUNCEMENT_REVISION,
                        MyHomeAnnouncementMappingFailureReason.CYCLIC_ANNOUNCEMENT_REVISION
                );
    }

    @Test
    void 비활성화된_원공고의_상세를_유지하고_재수집된_취소공고를_반영한다() {
        saveMappedComplex();
        saveDefaultLhSupply("21026");
        saveDefaultLhSupply("21027");
        MyHomeAnnouncementSource originalSource = source(
                0,
                data("21026", 1, "LH서울", "동삼2")
        );
        sourceRepository.save(originalSource);
        service.mapAll();
        Announcement original = announcementRepository
                .findBySourceAnnouncementIdentifier("21026")
                .orElseThrow();
        originalSource.markMissed();
        originalSource.markMissed();
        sourceRepository.save(originalSource);
        sourceRepository.save(source(
                1,
                withCancellation(data("21027", 2, "LH서울", "동삼2"), "21026")
        ));

        service.mapAll();

        Announcement cancellation = announcementRepository
                .findBySourceAnnouncementIdentifier("21027")
                .orElseThrow();
        assertThat(sourceRepository.findById(originalSource.getId()).orElseThrow().isActive()).isFalse();
        assertThat(cancellation.getStatus()).isEqualTo(AnnouncementPublicationType.CANCELLATION);
        assertThat(cancellation.getPreviousSourceAnnouncementIdentifier()).isEqualTo("21026");
        assertThat(announcementQueryService.getAnnouncement(cancellation.getId()).applicationStatus())
                .isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(announcementQueryService.getAnnouncement(original.getId()).announcementId())
                .isEqualTo(original.getId());
    }

    @Test
    void 비활성_공급_원천은_보존하면서_활성_원천의_공고_정정을_반영한다() {
        saveMappedComplex();
        MyHomeAnnouncementSource current = source(0, data("21026", 1, "부산도시공사", "동삼2"));
        MyHomeAnnouncementSource historical = source(1, data("21026", 2, "부산도시공사", "동삼2"));
        sourceRepository.saveAll(List.of(current, historical));
        assertThat(service.mapAll().failedSourceRowCount()).isZero();
        List<Long> previousRowIds = supplyRowRepository.findAll().stream().map(SupplyRow::getId).sorted().toList();

        historical.markMissed();
        historical.markMissed();
        ReflectionTestUtils.setField(current, "pblancNm", "정정된 국민임대 입주자 모집공고");
        ReflectionTestUtils.setField(current, "endDe", "20260902");
        sourceRepository.saveAll(List.of(current, historical));

        assertThat(service.mapAll().failedSourceRowCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getName()).isEqualTo("정정된 국민임대 입주자 모집공고");
            assertThat(announcement.getApplicationEndDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        });
        assertThat(supplyRowRepository.findAll().stream().map(SupplyRow::getId).sorted().toList())
                .containsExactlyElementsOf(previousRowIds);
        assertThat(sourceRepository.findById(historical.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void 모든_원천이_비활성이면_마지막으로_관찰한_실행의_공통값과_공급행을_보존한다() {
        saveMappedComplex();
        MyHomeAnnouncementSource previous = source(0, data("21026", 1, "부산도시공사", "동삼2"));
        MyHomeAnnouncementSource latest = source(1, data("21026", 2, "부산도시공사", "동삼2"));
        sourceRepository.saveAll(List.of(previous, latest));
        service.mapAll();
        List<Long> previousRowIds = supplyRowRepository.findAll().stream().map(SupplyRow::getId).sorted().toList();

        previous.markSeen("earlier", COLLECTED_AT);
        previous.markMissed();
        previous.markMissed();
        latest.markSeen("later", COLLECTED_AT.plusSeconds(60));
        ReflectionTestUtils.setField(latest, "pblancNm", "마지막으로 관찰한 공고명");
        latest.markMissed();
        latest.markMissed();
        sourceRepository.saveAll(List.of(previous, latest));

        assertThat(service.mapAll().failedSourceRowCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement()
                .extracting(Announcement::getName).isEqualTo("마지막으로 관찰한 공고명");
        assertThat(supplyRowRepository.findAll().stream().map(SupplyRow::getId).sorted().toList())
                .containsExactlyElementsOf(previousRowIds);
    }

    @Test
    void 같은_실행의_활성_원천끼리_공통값이_다르면_계속_거절한다() {
        MyHomeAnnouncementSource first = source(0, data("21026", 1, "부산도시공사", "동삼2"));
        MyHomeAnnouncementSource second = source(1, data("21026", 2, "부산도시공사", "동삼2"));
        first.markSeen("same-run", COLLECTED_AT);
        second.markSeen("same-run", COLLECTED_AT);
        ReflectionTestUtils.setField(second, "pblancNm", "충돌하는 공고명");
        sourceRepository.saveAll(List.of(first, second));

        assertThat(service.mapAll().failedSourceRowCount()).isEqualTo(2);
        assertThat(announcementRepository.count()).isZero();
        assertThat(supplyRowRepository.count()).isZero();
        assertThat(failureRepository.findAll()).allSatisfy(failure ->
                assertThat(failure.getReason()).isEqualTo(
                        MyHomeAnnouncementMappingFailureReason.CONFLICTING_SOURCE_VALUE));
    }

    @Test
    void 단지_매칭에_실패해도_공급행을_보존하고_실패_사유를_기록한다() {
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));

        var report = service.mapAll();

        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(report.createdSupplyRowCount()).isOne();
        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingComplex()).isNull();
            assertThat(row.getHousingType()).isNull();
            assertThat(row.getMatchingFailureReason()).contains("일치하는 단지가 없습니다");
        });
        assertThat(failureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND);
    }

    @Test
    void 단지명_표기를_보정해_여러_단지_후보_중_하나를_확정한다() {
        HousingComplex matched = saveMappedComplex("동삼2", "123:NATIONAL_RENTAL");
        saveMappedComplex("동삼3", "124:NATIONAL_RENTAL");
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼 2단지")));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingComplex().getId()).isEqualTo(matched.getId());
            assertThat(row.getHousingType()).isNotNull();
            assertThat(row.getMatchingFailureReason()).isNull();
        });
    }

    @Test
    void 주택형을_하나로_확정할_수_없으면_단지만_연결하고_실패_사유를_기록한다() {
        saveMappedComplex();
        HousingComplex complex = complexRepository.findAll().getFirst();
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "second-source-housing-type-id",
                "59A",
                new BigDecimal("59.9500"),
                new BigDecimal("84.0500")
        ));
        sourceRepository.save(source(0, withHousingType(
                data("21026", 1, "부산도시공사", "동삼2"),
                "77A"
        )));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingComplex()).isNotNull();
            assertThat(row.getHousingType()).isNull();
            assertThat(row.getMatchingFailureReason()).contains("주택형 하나를 확정할 수 없습니다");
        });
        assertThat(failureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(MyHomeAnnouncementMappingFailureReason.AMBIGUOUS_HOUSING_TYPE);
    }

    @Test
    void 단일_주택형_후보가_원천_주택형명과_다르면_연결하지_않는다() {
        saveMappedComplex();
        MyHomeAnnouncementSourceSnapshot sourceData = withHousingType(
                data("21026", 1, "부산도시공사", "동삼2"),
                "59A"
        );
        sourceRepository.save(source(0, sourceData));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingComplex()).isNotNull();
            assertThat(row.getHousingType()).isNull();
            assertThat(row.getMatchingFailureReason()).contains("주택형 하나를 확정할 수 없습니다");
        });
        assertThat(failureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(MyHomeAnnouncementMappingFailureReason.AMBIGUOUS_HOUSING_TYPE);
    }

    @Test
    void 주택형명_표기를_보정해_여러_주택형_중_하나를_확정한다() {
        saveMappedComplex();
        HousingComplex complex = complexRepository.findAll().getFirst();
        HousingType expected = housingTypeRepository.findAllByHousingComplex(complex).getFirst();
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "second-source-housing-type-id",
                "59A",
                new BigDecimal("59.9500"),
                new BigDecimal("84.0500")
        ));
        MyHomeAnnouncementSourceSnapshot sourceData = withHousingType(
                data("21026", 1, "부산도시공사", "동삼2"),
                "46-A형"
        );
        sourceRepository.save(source(0, sourceData));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(expected.getId());
            assertThat(row.getMatchingFailureReason()).isNull();
        });
    }

    @Test
    void 미확정_공급행은_원천_주택형명이_보정되면_재매핑하여_연결한다() {
        saveMappedComplex();
        HousingComplex complex = complexRepository.findAll().getFirst();
        HousingType expected = housingTypeRepository.findAllByHousingComplex(complex).getFirst();
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "second-source-housing-type-id",
                "59A",
                new BigDecimal("59.9500"),
                new BigDecimal("84.0500")
        ));
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        service.mapAll();
        MyHomeAnnouncementSource source = sourceRepository.findAll().getFirst();
        source.replaceWith(withHousingType(
                data("21026", 1, "부산도시공사", "동삼2"),
                "46 A 타입"
        ));
        source.markCollectedAt(COLLECTED_AT.plusSeconds(60));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.updatedSupplyRowCount()).isOne();
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(expected.getId());
            assertThat(row.getMatchingFailureReason()).isNull();
        });
    }

    @Test
    void lh_공급행을_정제_단지에_연결하고_주택형명과_면적으로_정제_주택형을_확정한다() {
        saveMappedComplex();
        HousingComplex complex = complexRepository.findAll().getFirst();
        HousingType nameMatched = housingTypeRepository.findAllByHousingComplex(complex).getFirst();
        HousingType exclusiveAreaMatched = housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "second-source-housing-type-id",
                "59A",
                new BigDecimal("59.9500"),
                new BigDecimal("84.0500")
        ));
        HousingType supplyAreaMatched = housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "third-source-housing-type-id",
                "36A",
                new BigDecimal("36.1000"),
                new BigDecimal("50.0000")
        ));
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveDefaultLhSupply("21026");
        service.mapAll();
        saveLhSupplyLink("21026", "PAN-1");
        lhSupplyRepository.saveAll(List.of(
                lhSupply(0, "PAN-1", "동삼 2단지 국민임대", "46-A형", "99.0000", "99.0000"),
                lhSupply(1, "PAN-1", "동삼 2단지 국민임대", "59형", "59.9500", "99.0000"),
                lhSupply(2, "PAN-1", "동삼 2단지 국민임대", "기타", "99.0000", "50.0000"),
                lhSupply(3, "PAN-1", "동삼 2단지 국민임대", "미상", "77.0000", "88.0000")
        ));

        var report = service.mapAll();

        assertThat(report.createdSupplyRowCount()).isEqualTo(3);
        assertThat(report.updatedSupplyRowCount()).isOne();
        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).hasSize(4);
        SupplyRow firstResolvedRow = supplyRowRepository.findAll().stream()
                .filter(row -> !row.getSourceSupplyRowIdentifier().contains(":LH:"))
                .findFirst()
                .orElseThrow();
        assertThat(firstResolvedRow.getHousingType().getId()).isEqualTo(nameMatched.getId());
        assertThat(supplyRow("21026:LH:PAN-1:1").getHousingType().getId())
                .isEqualTo(exclusiveAreaMatched.getId());
        assertThat(supplyRow("21026:LH:PAN-1:2").getHousingType().getId())
                .isEqualTo(supplyAreaMatched.getId());
        assertThat(supplyRow("21026:LH:PAN-1:3")).satisfies(row -> {
            assertThat(row.getHousingType()).isNull();
            assertThat(row.getMatchingFailureReason()).contains("면적으로도 주택형 하나를 확정할 수 없습니다");
        });
    }

    @Test
    void 부분_lh_공급_교체를_거절하고_기존_공급행과_공급대상을_보존한다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-1");
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "46A", "46.8000", "67.0000"),
                lhSupply(1, "PAN-1", "동삼2", "46A", "46.8000", "67.0000"),
                lhSupply(2, "PAN-1", "동삼2", "46A", "46.8000", "67.0000")
        ));
        service.mapAll();
        assertThat(supplyRowRepository.findAll()).hasSize(3);
        SupplyRow staleRow = supplyRow("21026:LH:PAN-1:2");
        supplyTargetRepository.save(SupplyTarget.create(
                staleRow,
                "청년",
                null,
                1,
                null,
                null,
                null,
                null,
                null,
                1
        ));

        assertThatThrownBy(() -> lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "46A", "46.8000", "67.0000")
        ))).isInstanceOf(IncompleteLhSupplyReplacementException.class);

        var report = service.mapAll();

        assertThat(report.deletedSupplyRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).hasSize(3);
        assertThat(supplyTargetRepository.findAll()).singleElement()
                .extracting(target -> target.getSupplyRow().getId()).isEqualTo(staleRow.getId());
    }

    @Test
    void LH_매핑_후_공급_원천이_비어도_단일_공급행의_마지막_정상_연결을_보존한다() {
        saveMappedComplex();
        HousingComplex complex = complexRepository.findAll().getFirst();
        HousingType lhType = housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "source-housing-type-id:59A",
                "59A",
                new BigDecimal("59.9500"),
                new BigDecimal("84.0500")
        ));
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-1");
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "59A", "59.9500", "84.0500")
        ));
        service.mapAll();
        lhSupplyRepository.deleteAll();

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(lhType.getId());
            assertThat(row.getSourceHousingTypeName()).isEqualTo("59A");
            assertThat(row.getLhSourceSupplyRowIdentifier()).isEqualTo("LH:PAN-1:SUPPLY:0");
        });
    }

    @Test
    void LH_주택형_매칭이_실패한_뒤_공급_원천이_비면_마이홈_주택형으로_복구한다() {
        saveMappedComplex();
        HousingType myHomeType = housingTypeRepository.findAll().getFirst();
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-1");
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "99Z", "99.0000", "120.0000")
        ));
        service.mapAll();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType()).isNull();
            assertThat(row.getLhSourceSupplyRowIdentifier()).isNull();
        });
        lhSupplyRepository.deleteAll();

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(myHomeType.getId());
            assertThat(row.getSourceHousingTypeName()).isEqualTo("46A");
            assertThat(row.getMatchingFailureReason()).isNull();
        });
    }

    @Test
    void 이전에_성공한_LH_주택형은_재수집_매칭_실패와_후속_원천_유실에도_보존한다() {
        saveMappedComplex();
        HousingComplex complex = complexRepository.findAll().getFirst();
        HousingType lhType = housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "source-housing-type-id:59A",
                "59A",
                new BigDecimal("59.9500"),
                new BigDecimal("84.0500")
        ));
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-1");
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "59A", "59.9500", "84.0500")
        ));
        service.mapAll();

        // 수집기의 누락 방어 도입 전에 저장된 잘못된 원천의 매핑 방어를 검증한다.
        lhSupplyRepository.deleteAll();
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "99Z", "99.0000", "120.0000")
        ));
        var failed = service.mapAll();

        assertThat(failed.failedSourceRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(lhType.getId());
            assertThat(row.getSourceHousingTypeName()).isEqualTo("59A");
            assertThat(row.getMatchingFailureReason()).isNull();
            assertThat(row.getLhSourceSupplyRowIdentifier()).isEqualTo("LH:PAN-1:SUPPLY:0");
        });

        lhSupplyRepository.deleteAll();
        assertThat(service.mapAll().failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(lhType.getId());
            assertThat(row.getSourceHousingTypeName()).isEqualTo("59A");
            assertThat(row.getMatchingFailureReason()).isNull();
        });
    }

    @Test
    void 같은_LH_공급행의_모집세대수_변경을_상세_보강_전까지_반영한다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-1");
        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "46A", "46.8000", "67.0000", "10")
        ));
        service.mapAll();

        lhSourceStore.replaceSupplies("PAN-1", lhRequestDescription("PAN-1"), List.of(
                lhSupply(0, "PAN-1", "동삼2", "46A", "46.8000", "67.0000", "15")
        ));
        var report = service.mapAll();

        assertThat(report.updatedSupplyRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(15);
            assertThat(row.isLhTotalSupplyHouseholdCountOwned()).isTrue();
            assertThat(row.isLhTotalSupplyHouseholdCountEnriched()).isFalse();
        });
    }

    @Test
    void 현재_성공_연결의_lh_공급행만_매핑한다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "LH", "동삼2")));
        saveLhSupplyLink("21026", "PAN-OLD");
        lhSourceStore.replaceSupplies("PAN-OLD", lhRequestDescription("PAN-OLD"), List.of(
                lhSupply(0, "PAN-OLD", "동삼2", "과거형", "99.0000", "99.0000")
        ));
        saveLhSupplyLink("21026", "PAN-CURRENT");
        lhSourceStore.replaceSupplies("PAN-CURRENT", lhRequestDescription("PAN-CURRENT"), List.of(
                lhSupply(0, "PAN-CURRENT", "동삼2", "46A", "46.8000", "67.0000")
        ));

        service.mapAll();

        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSourceHousingTypeName()).isEqualTo("46A");
            assertThat(row.getHousingType()).isNotNull();
        });
    }

    @Test
    void 필수_날짜를_변환할_수_없으면_공고를_만들지_않고_실패를_기록한다() {
        MyHomeAnnouncementSourceSnapshot invalid = withPostedDate(
                data("21026", 1, "부산도시공사", "동삼2"),
                "20260230"
        );
        sourceRepository.save(source(0, invalid));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(announcementRepository.count()).isZero();
        assertThat(supplyRowRepository.count()).isZero();
        assertThat(failureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(MyHomeAnnouncementMappingFailureReason.INVALID_VALUE);
    }

    @Test
    void 문의처가_없어도_공고와_공급행을_매핑한다() {
        MyHomeAnnouncementSourceSnapshot withoutContact = withContact(
                data("21026", 1, "부산도시공사", "동삼2"),
                null
        );
        sourceRepository.save(source(0, withoutContact));

        var report = service.mapAll();

        assertThat(report.createdAnnouncementCount()).isOne();
        assertThat(report.createdSupplyRowCount()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getReceptionPlace().getContact()).isNull()
        );

        var repeatedReport = service.mapAll();

        assertThat(repeatedReport.unchangedAnnouncementCount()).isOne();
        assertThat(repeatedReport.unchangedSupplyRowCount()).isOne();
    }

    private void storeCollectedHouses(Instant collectedAt, List<Integer> houses) {
        storeCollectedSources(collectedAt, houses.stream()
                .map(house -> data("21026", house, "부산도시공사", "동삼2")).toList());
    }

    private void storeCollectedSources(Instant collectedAt, List<MyHomeAnnouncementSourceSnapshot> snapshots) {
        var request = new MyHomeAnnouncementCollectionRequest(UUID.randomUUID(), "01", 500, 1_000,
                collectedAt.minusSeconds(1));
        var rows = snapshots.stream().map(snapshot -> json.convertValue(snapshot,
                com.toadzip.backend.ingest.collection.myhome.announcement.domain.
                        MyHomeAnnouncementSourceSnapshot.class)).toList();
        UUID recordId = collectionHistory.start(request);
        collectedSourceStorage.complete(recordId, request,
                new MyHomeAnnouncementCollectedResponse(rows.size(), collectedAt, rows));
    }

    private void saveMappedComplex() {
        saveMappedComplex("동삼2", "123:NATIONAL_RENTAL");
    }

    private HousingComplex saveMappedComplex(String name, String sourceIdentifier) {
        return saveMappedComplex(name, sourceIdentifier, "NATIONAL_RENTAL");
    }

    private HousingComplex saveMappedComplex(String name, String sourceIdentifier, String supplyType) {
        Address address = Address.create(
                "서울특별시 종로구 테스트로 1",
                PNU,
                PNU.substring(0, 10),
                "11",
                "11110",
                new BigDecimal("37.566206"),
                new BigDecimal("126.977706")
        );
        HousingComplex complex = complexRepository.save(HousingComplex.createFromMyHome(
                name,
                sourceIdentifier,
                supplyType,
                address,
                100,
                "LH",
                null,
                "DISTRICT",
                "APARTMENT",
                "CORRIDOR",
                true,
                80
        ));
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                "source-housing-type-id:" + sourceIdentifier,
                "46A",
                new BigDecimal("46.8000"),
                new BigDecimal("67.0000")
        ));
        return complex;
    }

    private MyHomeAnnouncementSource source(int order, MyHomeAnnouncementSourceSnapshot data) {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(order, data);
        source.markCollectedAt(COLLECTED_AT);
        return source;
    }

    private SupplyRow supplyRow(String sourceIdentifier) {
        return supplyRowRepository.findBySourceSupplyRowIdentifier(sourceIdentifier).orElseThrow();
    }

    private void saveDefaultLhSupply(String identifier) {
        MyHomeAnnouncementSource source = source(0, data(identifier, 1, "LH", "동삼2"));
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        completeLinks(identifier, candidate);
        lhSupplyRepository.save(lhSupply(0, identifier, "동삼2", "46A", "46.8000", "67.0000"));
    }

    private void saveLhSupplyLink(String announcementIdentifier, String panId) {
        MyHomeAnnouncementSource source = sourceRepository.findAll().stream()
                .filter(row -> row.getPblancId().equals(announcementIdentifier))
                .findFirst().orElseThrow();
        ReflectionTestUtils.setField(source, "url", "https://example.com/announcements?panId=" + panId
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07");
        sourceRepository.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        completeLinks(announcementIdentifier, candidate);
    }

    private void completeLinks(String identifier, LhAnnouncementCollectionCandidateResolver.Candidate candidate) {
        for (ExternalDataSource target : List.of(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, identifier, candidate.requestDescription(), candidate.panId());
        }
    }

    private LhAnnouncementSupplySource lhSupply(
            int sourceOrder,
            String panId,
            String complexLabel,
            String typeName,
            String exclusiveArea,
            String supplyArea
    ) {
        return lhSupply(sourceOrder, panId, complexLabel, typeName, exclusiveArea, supplyArea, "10");
    }

    private LhAnnouncementSupplySource lhSupply(
            int sourceOrder,
            String panId,
            String complexLabel,
            String typeName,
            String exclusiveArea,
            String supplyArea,
            String suppliedUnitCount
    ) {
        LhAnnouncementSupplySource source = new LhAnnouncementSupplySource(
                sourceOrder,
                panId,
                new LhAnnouncementSupplySourceSnapshot(
                        complexLabel,
                        typeName,
                        exclusiveArea,
                        supplyArea,
                        "100",
                        suppliedUnitCount,
                        null,
                        null
                )
        );
        source.markCollectedAt(COLLECTED_AT);
        source.assignRequestHash(LhAnnouncementQuery.requestHashOf(lhRequestDescription(panId)));
        return source;
    }

    private String lhRequestDescription(String panId) {
        return new LhAnnouncementRequest(panId, "03", "06", "07", "062").requestDescription();
    }

    private MyHomeAnnouncementSourceSnapshot data(
            String pblancId,
            int houseSn,
            String provider,
            String complexName
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                pblancId,
                houseSn,
                "모집중",
                "국민임대 입주자 모집공고",
                provider,
                "46A",
                "국민임대",
                null,
                "20260813",
                "20261106",
                "20260824",
                "20260831",
                "1600-1004",
                "https://example.com/announcements?panId=" + pblancId
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07",
                null,
                null,
                complexName,
                "서울특별시",
                "종로구",
                "서울특별시 종로구 테스트로 1",
                "테스트로",
                "테스트동",
                PNU,
                "지역난방",
                "100",
                20,
                10_000_000L,
                2_000_000L,
                8_000_000L,
                200_000L
        );
    }

    private MyHomeAnnouncementSourceSnapshot withNameAndSupplyCount(
            MyHomeAnnouncementSourceSnapshot data,
            String name,
            int supplyCount
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), data.sttusNm(), name, data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), supplyCount, data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceSnapshot withSupplyType(
            MyHomeAnnouncementSourceSnapshot data,
            String supplyType
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), data.sttusNm(), supplyType + " 입주자 모집공고",
                data.suplyInsttNm(), data.houseTyNm(), supplyType, data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceSnapshot withPrevious(
            MyHomeAnnouncementSourceSnapshot data, String previousIdentifier
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), "정정공고", data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), previousIdentifier, data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceSnapshot withCancellation(
            MyHomeAnnouncementSourceSnapshot data,
            String previousIdentifier
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), "취소공고", data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), previousIdentifier, data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceSnapshot withPostedDate(MyHomeAnnouncementSourceSnapshot data, String postedDate) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), data.sttusNm(), data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), data.beforePblancId(), postedDate,
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceSnapshot withContact(MyHomeAnnouncementSourceSnapshot data, String contact) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), data.sttusNm(), data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), contact, data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceSnapshot withHousingType(
            MyHomeAnnouncementSourceSnapshot data, String housingType
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                data.pblancId(), data.houseSn(), data.sttusNm(), data.pblancNm(), data.suplyInsttNm(),
                housingType, data.suplyTyNm(), data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }
}
