package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementSupplyResponseParser;
import com.toadzip.backend.ingest.collection.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCandidateCollector;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionProgressManager;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementPageFetcher;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class LhAnnouncementEnrichmentServiceTest {

    private static final String PNU = "1111010100100010000";
    private static final String PAN_ID = "100";

    @Autowired
    private LhSourceStore sourceStore;

    @Autowired
    private ExternalDataFailureRecorder failureRecorder;

    @Autowired
    private ExternalDataCollectionFailureRepository externalFailureRepository;

    @Autowired
    private LhAnnouncementCollectionProgressManager progressManager;

    @Autowired
    private LhAnnouncementCollectionProgressStore progressStore;

    @Autowired
    private LhAnnouncementCollectionLinkRepository linkRepository;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    @Autowired
    private LhAnnouncementCollectionCandidateResolver candidateResolver;

    @Autowired
    private MyHomeAnnouncementMappingService mappingService;

    @Autowired
    private LhAnnouncementEnrichmentService enrichmentService;

    @Autowired
    private MyHomeAnnouncementSourceRepository myHomeSourceRepository;

    @Autowired
    private MyHomeAnnouncementMappingFailureRepository mappingFailureRepository;

    @Autowired
    private LhAnnouncementDetailSourceRepository detailSourceRepository;

    @Autowired
    private LhAnnouncementSupplySourceRepository supplySourceRepository;

    @Autowired
    private LhAnnouncementEnrichmentFailureRepository enrichmentFailureRepository;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private AnnouncementScheduleRepository scheduleRepository;

    @Autowired
    private AnnouncementAttachmentRepository attachmentRepository;

    @Autowired
    private SupplyTargetRepository supplyTargetRepository;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private HousingTypeRepository housingTypeRepository;

    @Autowired
    private HousingComplexRepository housingComplexRepository;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        supplyTargetRepository.deleteAll();
        supplyRowRepository.deleteAll();
        scheduleRepository.deleteAll();
        attachmentRepository.deleteAll();
        announcementRepository.deleteAll();
        housingTypeRepository.deleteAll();
        housingComplexRepository.deleteAll();
        enrichmentFailureRepository.deleteAll();
        detailSourceRepository.deleteAll();
        supplySourceRepository.deleteAll();
        mappingFailureRepository.deleteAll();
        myHomeSourceRepository.deleteAll();
        linkRepository.deleteAll();
        checkpointRepository.deleteAll();
        externalFailureRepository.deleteAll();
    }

    @Test
    void 기존_LH_공고를_일정_첨부파일_접수처_공급정보로_보강하고_반복해도_중복하지_않는다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");

        var first = enrichmentService.enrichAll();

        assertThat(first.updatedAnnouncementCount()).isOne();
        assertThat(first.createdScheduleCount()).isOne();
        assertThat(first.createdAttachmentCount()).isOne();
        assertThat(first.updatedSupplyRowCount()).isOne();
        assertThat(first.createdSupplyTargetCount()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getLhPanId()).isEqualTo(PAN_ID);
            assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("정정 사유");
            assertThat(announcement.getReceptionPlace().getContact()).isEqualTo("1600-1004");
        });
        assertThat(scheduleRepository.count()).isOne();
        assertThat(attachmentRepository.count()).isOne();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(20);
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });

        var repeated = enrichmentService.enrichAll();

        assertThat(repeated.unchangedAnnouncementCount()).isOne();
        assertThat(scheduleRepository.count()).isOne();
        assertThat(attachmentRepository.count()).isOne();
        assertThat(supplyTargetRepository.count()).isOne();
        assertThat(announcementRepository.count()).isOne();

        detailSourceRepository.deleteAll();
        supplySourceRepository.deleteAll();
        saveLhSources("12,000,000", "250,000");

        var changed = enrichmentService.enrichAll();

        assertThat(changed.updatedSupplyTargetCount()).isOne();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("12000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
    }

    @Test
    void 마이홈_재정제는_기존_LH_보강값을_지우지_않는다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();

        var report = mappingService.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("정정 사유");
            assertThat(announcement.getReceptionPlace().getName()).isEqualTo("LH 현장접수처");
        });
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLhSourceSupplyRowIdentifier()).isEqualTo("LH:" + PAN_ID + ":SUPPLY:0");
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(100);
        });
        assertThat(supplyTargetRepository.count()).isOne();
    }

    @Test
    void LH가_세대수를_미제공하면_마이홈의_변경된_세대수를_반영한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSourcesWithMissingHouseholdCountAndReception();
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        ReflectionTestUtils.setField(source, "sumSuplyCo", 30);
        myHomeSourceRepository.save(source);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();

        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLhSourceSupplyRowIdentifier()).isEqualTo("LH:" + PAN_ID + ":SUPPLY:0");
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(30);
        });
    }

    @Test
    void LH가_접수처를_미제공하면_마이홈의_변경된_접수처를_반영한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSourcesWithMissingHouseholdCountAndReception();
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        ReflectionTestUtils.setField(source, "refrnc", "02-1234-5678");
        myHomeSourceRepository.save(source);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();

        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getLhPanId()).isEqualTo(PAN_ID);
            assertThat(announcement.getReceptionPlace().getContact()).isEqualTo("02-1234-5678");
        });
    }

    @Test
    void 공급기관이_LH에서_SH로_바뀌면_LH_보강값만_정리하고_수기값을_보존한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        Announcement announcement = announcementRepository.findAll().getFirst();
        SupplyRow supplyRow = supplyRowRepository.findAll().getFirst();
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 10, 0);
        Long scheduleId = scheduleRepository.save(AnnouncementSchedule.create(
                announcement, ScheduleType.APPLICATION, "수동 일정", start, start.plusDays(1), 2
        )).getId();
        Long attachmentId = attachmentRepository.save(AnnouncementAttachment.create(
                announcement, "수동 첨부.pdf", AttachmentType.REFERENCE, "https://example.com/manual.pdf", 2
        )).getId();
        Long targetId = supplyTargetRepository.save(SupplyTarget.create(
                supplyRow, "수동 대상", null, 1, null,
                new BigDecimal("3000000"), new BigDecimal("100000"), null, null, 2
        )).getId();
        ReflectionTestUtils.setField(source, "suplyInsttNm", "서울주택도시공사");
        myHomeSourceRepository.save(source);

        var report = mappingService.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getProvider()).isEqualTo(AgencyCode.SH);
            assertThat(stored.getLhPanId()).isNull();
            assertThat(stored.getCorrectionCancellationReason()).isNull();
            assertThat(stored.getReceptionPlace().getName()).isEqualTo("서울주택도시공사");
        });
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getLhSourceSupplyRowIdentifier()).isNull();
            assertThat(stored.getExpectedMoveInMonth()).isNull();
            assertThat(stored.getTotalSupplyHouseholdCount()).isEqualTo(20);
        });
        assertThat(scheduleRepository.findAll()).singleElement().satisfies(stored ->
                assertThat(stored.getId()).isEqualTo(scheduleId));
        assertThat(attachmentRepository.findAll()).singleElement().satisfies(stored ->
                assertThat(stored.getId()).isEqualTo(attachmentId));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(stored ->
                assertThat(stored.getId()).isEqualTo(targetId));
    }

    @ParameterizedTest
    @CsvSource({
            "5년임대, PUBLIC_RENTAL_5Y",
            "10년임대, PUBLIC_RENTAL_10Y"
    })
    void 공공임대_기간을_보존한_LH_공고를_상세와_공급정보로_보강한다(
            String sourceSupplyType,
            RentalType expectedType
    ) {
        saveComplex(expectedType.name());
        myHomeSourceRepository.save(myHomeSource("21026", sourceSupplyType));
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getSupplyType()).isEqualTo(expectedType));
        assertThat(supplyTargetRepository.count()).isOne();
        assertThat(enrichmentFailureRepository.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "국민임대, NATIONAL_RENTAL",
            "영구임대, PERMANENT_RENTAL",
            "5년임대, PUBLIC_RENTAL_5Y",
            "10년임대, PUBLIC_RENTAL_10Y"
    })
    void LH_공급_API가_빈_응답이어도_마이홈_공급행을_매핑하고_LH_상세로_보강한다(
            String sourceSupplyType,
            RentalType expectedType
    ) {
        saveComplex(expectedType.name());
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(
                myHomeSource("21026", sourceSupplyType)
        );
        completeLinks(source);
        saveLhSources("10,000,000", "200,000");
        supplySourceRepository.deleteAll();

        var mappingReport = mappingService.mapAll();
        var enrichmentReport = enrichmentService.enrichAll();

        assertThat(mappingReport.failedSourceRowCount()).isZero();
        assertThat(enrichmentReport.failedSourceCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getSupplyType()).isEqualTo(expectedType);
            assertThat(announcement.getLhPanId()).isEqualTo(PAN_ID);
        });
        assertThat(supplyRowRepository.count()).isOne();
        assertThat(scheduleRepository.count()).isOne();
        assertThat(attachmentRepository.count()).isOne();
        assertThat(supplyTargetRepository.count()).isZero();
        assertThat(mappingFailureRepository.count()).isZero();
        assertThat(enrichmentFailureRepository.count()).isZero();
    }

    @Test
    void 마이홈_URL에_panId가_없으면_공고를_새로_생성하지_않고_실패를_기록한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        source.replaceWith(new MyHomeAnnouncementSourceSnapshot(
                "21026", 1, "모집중", "국민임대 입주자 모집공고", "LH서울", "아파트", "국민임대", null,
                "20260813", "20261106", "20260824", "20260831", "1600-1004",
                "https://example.com/announcements", null, null, "동삼2", "서울특별시", "종로구",
                "서울특별시 종로구 테스트로 1", "테스트로", "테스트동", PNU, "지역난방", "100", 20,
                10_000_000L, 2_000_000L, 8_000_000L, 200_000L
        ));
        myHomeSourceRepository.save(source);

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isOne();
        assertThat(announcementRepository.count()).isOne();
        assertThat(enrichmentFailureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_REQUEST_UNSUPPORTED);
    }

    @Test
    void 공고문_참조로_표시된_임대료는_추정하지_않고_공급행만_보강한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("공고문 참조", "공고문 참조");

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isZero();
        assertThat(report.createdSupplyTargetCount()).isZero();
        assertThat(supplyTargetRepository.count()).isZero();
    }

    @Test
    void 저장했던_임대료가_공고문_참조로_바뀌어도_마지막_정상_금액을_보존한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");
        enrichmentService.enrichAll();
        assertThat(supplyTargetRepository.count()).isOne();

        supplySourceRepository.deleteAll();
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", "공고문 참조", "공고문 참조"
                )));

        enrichmentService.enrichAll();

        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
    }

    @Test
    void 임대료가_허용되지_않은_형식이면_기존_공급대상을_보존하고_실패를_기록한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");
        enrichmentService.enrichAll();

        supplySourceRepository.deleteAll();
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", "10~20만원", "200,000"
                )));

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isOne();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
        assertThat(enrichmentFailureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.INVALID_VALUE);
    }

    @Test
    void 긴_접수_안내문은_접수처명으로_저장하지_않고_기본명을_사용한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000", "안내".repeat(200));

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getReceptionPlace().getName()).isEqualTo("LH 접수처")
        );
    }

    @Test
    void 단지만_일치하고_주택형이_다르면_공급행을_보강하지_않고_실패를_기록한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000", "LH 현장접수처", "99Z");

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getLhSourceSupplyRowIdentifier()).isEqualTo("LH:" + PAN_ID + ":SUPPLY:0")
        );
        assertThat(supplyTargetRepository.count()).isZero();
        assertThat(enrichmentFailureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.HOUSING_TYPE_NOT_FOUND);
    }

    @Test
    void 같은_panId의_주택형_매칭이_실패해도_기존_금액을_보존한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");
        enrichmentService.enrichAll();

        supplySourceRepository.deleteAll();
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "99Z", "99.0", "120.0", "100", "20", "12,000,000", "250,000"
                )));

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isOne();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
    }

    @Test
    void LH_상세가_일정과_첨부를_미제공해도_마지막_정상값을_보존한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");
        enrichmentService.enrichAll();

        detailSourceRepository.deleteAll();
        saveDetail(detail(
                0, "ETC_INFO", null, null, null, null, null, null, null, "변경된 정정 사유"
        ));

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isZero();
        assertThat(scheduleRepository.count()).isOne();
        assertThat(attachmentRepository.count()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("변경된 정정 사유")
        );
    }

    @Test
    void LH_공급행_순서가_바뀌어도_주택형을_다시_확인해_올바른_공급행을_보강한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        HousingType secondType = housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id-59B", "59B", new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        Announcement announcement = announcementRepository.findAll().getFirst();
        SupplyRow mappedRow = supplyRowRepository.findAll().getFirst();
        HousingType firstType = housingTypeRepository.findAll().stream()
                .filter(type -> type.getName().equals("46A"))
                .findFirst()
                .orElseThrow();
        supplyRowRepository.deleteAll();
        supplyRowRepository.saveAll(List.of(
                SupplyRow.create(
                        announcement, complex, firstType, "manual-46A", 1, "동삼2", "46A", PNU,
                        null, mappedRow.getSupplyCategory(), null, null
                ),
                SupplyRow.create(
                        announcement, complex, secondType, "manual-59B", 2, "동삼2", "59B", PNU,
                        null, mappedRow.getSupplyCategory(), null, null
                )
        ));
        saveLhSources("10,000,000", "200,000");
        saveSupply(new LhAnnouncementSupplySource(1, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "59B", "59.8", "84.0", "80", "10", "20,000,000", "300,000"
                )));
        enrichmentService.enrichAll();

        supplySourceRepository.deleteAll();
        saveSupplies(List.of(
                new LhAnnouncementSupplySource(0, PAN_ID, new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "59B", "59.8", "84.0", "80", "10", "21,000,000", "310,000"
                )),
                new LhAnnouncementSupplySource(1, PAN_ID, new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", "11,000,000", "210,000"
                ))
        ));

        enrichmentService.enrichAll();

        assertThat(supplyRowRepository.findAll())
                .filteredOn(row -> row.getSourceHousingTypeName().equals("46A"))
                .singleElement()
                .extracting(SupplyRow::getLhSourceSupplyRowIdentifier)
                .isEqualTo("LH:" + PAN_ID + ":SUPPLY:1");
        assertThat(supplyRowRepository.findAll())
                .filteredOn(row -> row.getSourceHousingTypeName().equals("59B"))
                .singleElement()
                .extracting(SupplyRow::getLhSourceSupplyRowIdentifier)
                .isEqualTo("LH:" + PAN_ID + ":SUPPLY:0");
    }

    @Test
    void URL의_조회조건이_바뀌고_새_수집이_완료되지_않으면_두_단계_모두_기존_데이터를_보존한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        Long rowId = supplyRowRepository.findAll().getFirst().getId();

        ReflectionTestUtils.setField(source, "url", source.getUrl().replace("aisTpCd=07", "aisTpCd=08"));
        myHomeSourceRepository.save(source);
        supplySourceRepository.deleteAll();
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "99", "90,000,000", "900,000"
                )));

        var mapping = mappingService.mapAll();
        var enrichment = enrichmentService.enrichAll();

        assertThat(mapping.failedSourceRowCount()).isOne();
        assertThat(enrichment.failedSourceCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getId()).isEqualTo(rowId));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
    }

    @Test
    void URL_변경_후_수집_실패는_연결을_보존하고_재수집_성공_후_두_단계가_새_원천을_사용한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        Integer previousCount = supplyRowRepository.findAll().getFirst().getTotalSupplyHouseholdCount();
        ReflectionTestUtils.setField(source, "url", source.getUrl().replace("panId=100", "panId=200"));
        myHomeSourceRepository.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchSupply(any()))
                .thenThrow(new ExternalDataRequestException("새 공고 수집 실패"))
                .thenReturn(response("""
                        [{"resHeader":[{"SS_CODE":"Y"}]},
                         {"dsList01":[{"SBD_LGO_NM":"동삼2","HTY_NNA":"46A","DDO_AR":"46.8",
                         "SPL_AR":"67.0","HSH_CNT":"100","NOW_HSH_CNT":"30",
                         "LS_GMY":"12000000","RFE":"250000"}]}]
                        """));
        LhAnnouncementCandidateCollector collector = collector(external);

        var failed = collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);

        assertThat(failed.failedRequestCount()).isOne();
        assertThat(linkRepository.findAll()).allSatisfy(link -> assertThat(link.getPanId()).isEqualTo(PAN_ID));
        assertThat(mappingService.mapAll().failedSourceRowCount()).isOne();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target ->
                assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000"));

        var collected = collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);

        assertThat(collected.storedRowCount()).isOne();
        assertThat(mappingService.mapAll().failedSourceRowCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(previousCount));
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();
        assertThat(enrichmentFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getReason()).isEqualTo(
                        LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_MISMATCH));
        when(external.fetchDetail(any())).thenReturn(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsEtcInfo":[{"CRC_RSN":"새 공고"}]}]
                """));

        collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(30));
        var enriched = enrichmentService.enrichAll();

        assertThat(enriched.failedSourceCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getLhPanId()).isEqualTo("200"));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("12000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
        assertThat(linkRepository.findAll()).allSatisfy(link -> assertThat(link.getPanId()).isEqualTo("200"));
        assertThat(scheduleRepository.count()).isZero();
        assertThat(attachmentRepository.count()).isZero();
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyTargetRepository.count()).isOne();
    }

    @Test
    void 동일_공고를_재수집하면_변경된_일정_첨부_공급정보를_정제_결과에_반영한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchDetail(any()))
                .thenReturn(detailResponse(
                        "초기 정정 사유",
                        "2026.08.24 10:00 ~ 2026.08.31 17:00",
                        "초기 공고문.pdf",
                        "https://example.com/initial.pdf"
                ))
                .thenReturn(detailResponse(
                        "변경 정정 사유",
                        "2026.09.01 09:00 ~ 2026.09.07 18:00",
                        "변경 공고문.pdf",
                        "https://example.com/changed.pdf"
                ));
        when(external.fetchSupply(any()))
                .thenReturn(supplyResponse("20", "10000000", "200000"))
                .thenReturn(supplyResponse("30", "12000000", "250000"));
        LhAnnouncementCandidateCollector collector = collector(external);

        collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate);
        collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        Long scheduleId = scheduleRepository.findAll().getFirst().getId();
        Long attachmentId = attachmentRepository.findAll().getFirst().getId();
        Long targetId = supplyTargetRepository.findAll().getFirst().getId();

        collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate);
        collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();

        assertThat(scheduleRepository.findAll()).singleElement().satisfies(schedule -> {
            assertThat(schedule.getId()).isEqualTo(scheduleId);
            assertThat(schedule.getStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0));
            assertThat(schedule.getEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 7, 18, 0));
        });
        assertThat(attachmentRepository.findAll()).singleElement().satisfies(attachment -> {
            assertThat(attachment.getId()).isEqualTo(attachmentId);
            assertThat(attachment.getFileName()).isEqualTo("변경 공고문.pdf");
            assertThat(attachment.getFileUrl()).isEqualTo("https://example.com/changed.pdf");
        });
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getId()).isEqualTo(targetId);
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(30);
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("12000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("변경 정정 사유")
        );
        assertThat(detailSourceRepository.count()).isEqualTo(4);
        assertThat(supplySourceRepository.count()).isOne();
        assertThat(checkpointRepository.count()).isEqualTo(2);
        assertThat(linkRepository.count()).isEqualTo(2);
    }

    @Test
    void 연결이_없으면_체크포인트와_URL의_원천으로_우회하지_않고_두_단계가_누락을_기록한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        linkRepository.deleteAll();
        assertThat(checkpointRepository.count()).isEqualTo(2);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isOne();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();

        assertThat(mappingFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getReason()).isEqualTo(
                        MyHomeAnnouncementMappingFailureReason.LH_COLLECTION_LINK_NOT_FOUND));
        assertThat(enrichmentFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getReason()).isEqualTo(
                        LhAnnouncementEnrichmentFailureReason.LH_COLLECTION_LINK_NOT_FOUND));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target ->
                assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000"));
    }

    @Test
    void 연결된_공급_원천이_없어도_기존_공급행을_보존하고_보강을_계속한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        supplySourceRepository.deleteAll();

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();

        assertThat(mappingFailureRepository.count()).isZero();
        assertThat(enrichmentFailureRepository.count()).isZero();
        assertThat(supplyRowRepository.count()).isOne();
        assertThat(supplyTargetRepository.count()).isOne();
    }

    @Test
    void 정상_빈_LH_공급_응답은_기존_확장_공급행과_금액을_보존한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        Long firstHousingTypeId = housingTypeRepository.findAllByHousingComplex(complex).getFirst().getId();
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id-59B", "59B",
                new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        MyHomeAnnouncementSource source = myHomeSource();
        ReflectionTestUtils.setField(source, "houseTyNm", "통합형");
        myHomeSourceRepository.save(source);
        saveLhSources("10,000,000", "200,000");
        saveSupply(new LhAnnouncementSupplySource(1, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "59B", "59.8", "84.0", "80", "10", "20,000,000", "300,000"
                )));
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        assertThat(supplyRowRepository.count()).isEqualTo(2);
        assertThat(supplyTargetRepository.count()).isEqualTo(2);

        supplySourceRepository.deleteAll();
        String changedPnu = "1111010100100020000";
        ReflectionTestUtils.setField(source, "pnu", changedPnu);
        ReflectionTestUtils.setField(source, "hsmpNm", "동삼2 변경");
        ReflectionTestUtils.setField(source, "pblancNm", "국민임대 재공급 공고");
        myHomeSourceRepository.save(source);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyRowRepository.count()).isEqualTo(2);
        assertThat(supplyTargetRepository.count()).isEqualTo(2);
        assertThat(supplyRowRepository.findAll())
                .allSatisfy(row -> {
                    assertThat(row.getSupplyPnu()).isEqualTo(changedPnu);
                    assertThat(row.getSourceComplexName()).isEqualTo("동삼2 변경");
                    assertThat(row.getSupplyCategory()).isEqualTo(SupplyCategory.RESUPPLY);
                })
                .extracting(SupplyRow::getDisplayOrder)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(supplyRowRepository.findAll())
                .filteredOn(row -> row.getSourceSupplyRowIdentifier().equals(source.getSourceKey()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getSourceHousingTypeName()).isEqualTo("46A");
                    assertThat(row.getHousingType().getId()).isEqualTo(firstHousingTypeId);
                    assertThat(row.getMatchingFailureReason()).isNull();
                });
    }

    @Test
    void 상세_연결은_있지만_원천이_없으면_상세_원천_누락을_기록한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");
        detailSourceRepository.deleteAll();

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();

        assertThat(enrichmentFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getReason()).isEqualTo(
                        LhAnnouncementEnrichmentFailureReason.LH_DETAIL_SOURCE_NOT_FOUND));
    }

    @Test
    void 같은_LH_요청을_공유하는_각_공고가_자신의_완료_연결로_매핑과_보강을_수행한다() {
        saveComplex();
        MyHomeAnnouncementSource first = myHomeSourceRepository.save(myHomeSource());
        MyHomeAnnouncementSource second = myHomeSourceRepository.save(myHomeSource("21027"));
        saveLhSources("10,000,000", "200,000");
        completeLinks(first);
        completeLinks(second);

        assertThat(mappingService.mapAll().createdAnnouncementCount()).isEqualTo(2);
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();

        assertThat(checkpointRepository.count()).isEqualTo(2);
        assertThat(linkRepository.count()).isEqualTo(4);
        assertThat(announcementRepository.findAll()).hasSize(2).allSatisfy(announcement ->
                assertThat(announcement.getLhPanId()).isEqualTo(PAN_ID));
        assertThat(supplyTargetRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_panId의_다른_조회조건_원천은_매핑과_보강에_섞이지_않는다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        String otherRequest = new LhAnnouncementRequest(PAN_ID, "03", "06", "08", "062")
                .requestDescription();
        sourceStore.replaceDetails(PAN_ID, otherRequest, List.of(detail(
                0, "ETC_INFO", null, null, null, null, null, null, null, "다른 조회조건"
        )));
        sourceStore.replaceSupplies(PAN_ID, otherRequest, List.of(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "99", "90,000,000", "900,000"
                ))));
        completeLinks(source);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyRowRepository.count()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("정정 사유"));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target ->
                assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000"));
    }

    @Test
    void LH_연결을_바꿔도_수동_일정_첨부_금액과_이전_원천을_보존한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        Announcement announcement = announcementRepository.findAll().getFirst();
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 10, 0);
        Long scheduleId = scheduleRepository.save(AnnouncementSchedule.create(
                announcement, ScheduleType.APPLICATION, "수동 일정", start, start.plusDays(1), 2
        )).getId();
        Long attachmentId = attachmentRepository.save(AnnouncementAttachment.create(
                announcement, "수동 첨부.pdf", AttachmentType.REFERENCE, "https://example.com/manual.pdf", 2
        )).getId();
        Long targetId = supplyTargetRepository.save(SupplyTarget.create(
                supplyRowRepository.findAll().getFirst(), "수동 대상", null, 1, null,
                new BigDecimal("3000000"), new BigDecimal("100000"), null, null, 2
        )).getId();
        switchLhSource(source, "46A");

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();

        assertThat(scheduleRepository.findAll()).singleElement().satisfies(schedule ->
                assertThat(schedule.getId()).isEqualTo(scheduleId));
        assertThat(attachmentRepository.findAll()).singleElement().satisfies(attachment ->
                assertThat(attachment.getId()).isEqualTo(attachmentId));
        assertThat(supplyTargetRepository.findById(targetId)).isPresent();
        assertThat(supplyTargetRepository.count()).isEqualTo(2);
        String oldRequest = new LhAnnouncementRequest(PAN_ID, "03", "06", "07", "062")
                .requestDescription();
        String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(oldRequest);
        assertThat(detailSourceRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(PAN_ID, requestHash))
                .hasSize(5);
        assertThat(supplySourceRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(PAN_ID, requestHash))
                .hasSize(1);
    }

    @Test
    void 새_연결의_주택형_매칭이_실패하면_이전_금액을_보존하고_복구_후_교체한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        switchLhSource(source, "99Z");

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target ->
                assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000"));
        sourceStore.replaceSupplies("200", requestDescriptionFor("200"), List.of(new LhAnnouncementSupplySource(0, "200",
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", "12000000", "250000"
                ))));

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getSourceSupplyTargetIdentifier()).isEqualTo("LH:200:SUPPLY:0:TARGET");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
    }

    @Test
    void 새_연결이_금액을_미제공해도_반복_보존하고_유효한_금액으로_복구되면_교체한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        switchLhSource(source, "46A", null, null);

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getSourceSupplyTargetIdentifier()).isEqualTo("LH:" + PAN_ID + ":SUPPLY:0:TARGET");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });

        sourceStore.replaceSupplies("200", requestDescriptionFor("200"), List.of(new LhAnnouncementSupplySource(0, "200",
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", "12000000", "250000"
                ))));

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getSourceSupplyTargetIdentifier()).isEqualTo("LH:200:SUPPLY:0:TARGET");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
    }

    private void switchLhSource(MyHomeAnnouncementSource source, String housingType) {
        switchLhSource(source, housingType, "12000000", "250000");
    }

    private void switchLhSource(
            MyHomeAnnouncementSource source,
            String housingType,
            String deposit,
            String rent
    ) {
        ReflectionTestUtils.setField(source, "url", source.getUrl().replace("panId=100", "panId=200"));
        myHomeSourceRepository.save(source);
        LhAnnouncementDetailSource detail = detail(0, "ETC_INFO", null, null, null, null, null,
                null, null, "새 공고");
        ReflectionTestUtils.setField(detail, "panId", "200");
        saveDetail(detail);
        saveSupply(new LhAnnouncementSupplySource(0, "200",
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", housingType, "46.8", "67.0", "100", "20", deposit, rent
                )));
        completeLinks(source);
    }

    private LhAnnouncementCandidateCollector collector(LhAnnouncementExternalRepository external) {
        LhAnnouncementPageFetcher fetcher = new LhAnnouncementPageFetcher(
                external, new LhAnnouncementDetailResponseParser(), new LhAnnouncementSupplyResponseParser(),
                new ExternalDataRetryExecutor(new SimpleMeterRegistry())
        );
        return new LhAnnouncementCandidateCollector(
                fetcher, sourceStore, failureRecorder, progressManager, new SimpleMeterRegistry()
        );
    }

    private ExternalDataResponse response(String json) {
        return new ExternalDataResponse(json, JsonMapper.builder().build().readTree(json));
    }

    private ExternalDataResponse detailResponse(
            String correctionReason,
            String applicationPeriod,
            String fileName,
            String fileUrl
    ) {
        return response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsEtcInfo":[{"CRC_RSN":"%s"}]},
                 {"dsSbd":[{"LCC_NT_NM":"동삼2","MVIN_XPC_YM":"202612"}]},
                 {"dsSplScdl":[{"ACP_DTTM":"%s"}]},
                 {"dsAhflInfo":[{"SL_PAN_AHFL_DS_CD_NM":"공고문","CMN_AHFL_NM":"%s",
                 "AHFL_URL":"%s"}]}]
                """.formatted(correctionReason, applicationPeriod, fileName, fileUrl));
    }

    private ExternalDataResponse supplyResponse(String suppliedCount, String deposit, String rent) {
        return response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsList01":[{"SBD_LGO_NM":"동삼2","HTY_NNA":"46A","DDO_AR":"46.8",
                 "SPL_AR":"67.0","HSH_CNT":"100","NOW_HSH_CNT":"%s","LS_GMY":"%s",
                 "RFE":"%s"}]}]
                """.formatted(suppliedCount, deposit, rent));
    }

    private void mapMyHomeSource() {
        MyHomeAnnouncementSource source = myHomeSourceRepository.findAll().getFirst();
        completeLinks(source);
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", null, null
                )));
        mappingService.mapAll();
        supplySourceRepository.deleteAll();
    }

    private void completeLinks(MyHomeAnnouncementSource source) {
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        for (ExternalDataSource target : List.of(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, source.getPblancId(), candidate.requestDescription(), candidate.panId());
        }
    }

    private void saveComplex() {
        saveComplex("NATIONAL_RENTAL");
    }

    private void saveComplex(String supplyType) {
        Address address = Address.create(
                "서울특별시 종로구 테스트로 1", PNU, PNU.substring(0, 10), "11", "11110",
                new BigDecimal("37.566206"), new BigDecimal("126.977706")
        );
        HousingComplex complex = housingComplexRepository.save(HousingComplex.createFromMyHome(
                "동삼2", "123:" + supplyType, supplyType, address, 100, "LH", null,
                "DISTRICT", "APARTMENT", "CORRIDOR", true, 80
        ));
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id", "46A", new BigDecimal("46.8000"), new BigDecimal("67.0000")
        ));
    }

    private MyHomeAnnouncementSource myHomeSource() {
        return myHomeSource("21026", "국민임대");
    }

    private MyHomeAnnouncementSource myHomeSource(String identifier) {
        return myHomeSource(identifier, "국민임대");
    }

    private MyHomeAnnouncementSource myHomeSource(String identifier, String supplyType) {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0, new MyHomeAnnouncementSourceSnapshot(
                identifier, 1, "모집중", supplyType + " 입주자 모집공고", "LH서울", "46A", supplyType, null,
                "20260813", "20261106", "20260824", "20260831", "1600-1004",
                "https://example.com/announcements?panId=" + PAN_ID
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07", null, null, "동삼2", "서울특별시", "종로구",
                "서울특별시 종로구 테스트로 1", "테스트로", "테스트동", PNU, "지역난방", "100", 20,
                10_000_000L, 2_000_000L, 8_000_000L, 200_000L
        ));
        source.markCollectedAt(Instant.parse("2026-08-28T00:00:00Z"));
        return source;
    }

    private void saveLhSources(String deposit, String rent) {
        saveLhSources(deposit, rent, "LH 현장접수처");
    }

    private void saveLhSources(String deposit, String rent, String receptionGuidance) {
        saveLhSources(deposit, rent, receptionGuidance, "46A");
    }

    private void saveLhSources(String deposit, String rent, String receptionGuidance, String housingTypeName) {
        saveDetails(List.of(
                detail(0, "ETC_INFO", null, null, null, null, null, null, null, "정정 사유"),
                detail(1, "SCHEDULE", "2026.08.24 10:00 ~ 2026.08.31 17:00", null, null, null, null, null, null, null),
                detail(2, "RECEPTION", null, "서울특별시 종로구 접수로 1", "101호", "1600-1004", receptionGuidance, null, null, null),
                detail(3, "ANNOUNCEMENT_FILE", null, null, null, null, null, "공고문.pdf", "https://example.com/file.pdf", null),
                detail(4, "COMPLEX", null, null, null, null, null, null, null, null)
        ));
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", housingTypeName, "46.8", "67.0", "100", "20", deposit, rent
                )));
    }

    private void saveLhSourcesWithMissingHouseholdCountAndReception() {
        saveDetail(detail(
                0, "ETC_INFO", null, null, null, null, null, null, null, "정정 사유"
        ));
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", null, null, null, null
                )));
    }

    private String requestDescriptionFor(String panId) {
        return myHomeSourceRepository.findAll().stream()
                .map(candidateResolver::resolve)
                .filter(LhAnnouncementCollectionCandidateResolver.Candidate.class::isInstance)
                .map(LhAnnouncementCollectionCandidateResolver.Candidate.class::cast)
                .filter(candidate -> candidate.panId().equals(panId))
                .map(LhAnnouncementCollectionCandidateResolver.Candidate::requestDescription)
                .findFirst().orElseThrow();
    }

    private LhAnnouncementDetailSource saveDetail(LhAnnouncementDetailSource source) {
        source.assignRequestHash(LhAnnouncementCollectionCheckpoint.requestHashOf(
                requestDescriptionFor(source.getPanId())));
        return detailSourceRepository.save(source);
    }

    private void saveDetails(List<LhAnnouncementDetailSource> sources) {
        sources.forEach(this::saveDetail);
    }

    private LhAnnouncementSupplySource saveSupply(LhAnnouncementSupplySource source) {
        source.assignRequestHash(LhAnnouncementCollectionCheckpoint.requestHashOf(
                requestDescriptionFor(source.getPanId())));
        return supplySourceRepository.save(source);
    }

    private void saveSupplies(List<LhAnnouncementSupplySource> sources) {
        sources.forEach(this::saveSupply);
    }

    private LhAnnouncementDetailSource detail(
            int order,
            String datasetType,
            String applicationPeriod,
            String address,
            String detailAddress,
            String phone,
            String guidance,
            String name,
            String url,
            String correctionReason
    ) {
        return new LhAnnouncementDetailSource(
                order, PAN_ID, datasetType, null, address, detailAddress, null, null, null,
                datasetType.equals("COMPLEX") ? "202612" : null, guidance, applicationPeriod, null, null, null,
                null, null, address, detailAddress, null, null, phone, guidance, null, name, url, null,
                correctionReason, null
        );
    }
}
