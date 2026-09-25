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
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
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
import com.toadzip.backend.ingest.collection.service.LhAnnouncementResponseFetcher;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

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
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(20);
        });
        assertThat(supplyTargetRepository.count()).isOne();
    }

    @Test
    void 공공임대_5년_dsList02_수집부터_보강까지_금회_공급_세대수를_유지한다() {
        saveComplex("PUBLIC_RENTAL_5Y");
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource("21026", "5년임대"));
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchDetail(any())).thenReturn(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsEtcInfo":[{"CRC_RSN":"정정 사유"}]}]
                """));
        when(external.fetchSupply(any())).thenReturn(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsList01":[],
                 "dsList02":[{"BZDT_NM":"동삼2","HTY_NM":"46A","RSDN_DDO_AR":"46.8",
                 "SPL_AR":"67.0","TOT_HSH_CNT":"100","SIL_HSH_CNT":"20",
                 "LS_GMY":"10000000","MM_RFE":"200000"}]}]
                """));
        LhAnnouncementCandidateCollector collector = collector(external);

        assertThat(collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate).failedRequestCount())
                .isZero();
        assertThat(collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate).failedRequestCount())
                .isZero();
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();

        assertThat(supplySourceRepository.count()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(20));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(20);
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
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
    void 최초_LH_공급_API가_빈_응답이어도_마이홈_공급행을_매핑하고_LH_상세로_보강한다(
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
        linkRepository.deleteAll();
        checkpointRepository.deleteAll();
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchSupply(any())).thenReturn(response("[{\"dsList01\":[],\"dsList02\":[]}]"));
        progressManager.complete(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate);

        var collected = collector(external).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);
        assertThat(collected.failedRequestCount()).isZero();
        assertThat(collected.storedRowCount()).isZero();

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

    @ParameterizedTest
    @CsvSource({
            "5년임대, PUBLIC_RENTAL_5Y",
            "10년임대, PUBLIC_RENTAL_10Y",
            "50년임대, PUBLIC_RENTAL_50Y",
            "국민임대, NATIONAL_RENTAL",
            "영구임대, PERMANENT_RENTAL",
            "행복주택, HAPPY_HOUSING",
            "통합공공임대, INTEGRATED_PUBLIC_RENTAL"
    })
    void 빈_재수집은_원천과_성공_기록과_정제_결과를_보존하고_정상_응답에서_복구한다(
            String supplyType,
            RentalType rentalType
    ) {
        saveComplex(rentalType.name());
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource("21026", supplyType));
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        saveLhSources("10000000", "200000");
        completeLinks(source);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        var previousSources = supplySourceRepository.findAll();
        var previousCheckpoints = checkpointRepository.findAll();
        var previousLinks = linkRepository.findAll();
        Long rowId = supplyRowRepository.findAll().getFirst().getId();
        Long targetId = supplyTargetRepository.findAll().getFirst().getId();
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchSupply(any())).thenReturn(response("[{\"dsList01\":[],\"dsList02\":[]}]"));
        LhAnnouncementCandidateCollector collector = collector(external);

        var failed = collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);

        assertThat(failed.failedRequestCount()).isOne();
        assertThat(failed.storedRowCount()).isZero();
        assertThat(failed.externalApiCallCount()).isOne();
        assertThat(supplySourceRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(previousSources);
        assertThat(checkpointRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousCheckpoints);
        assertThat(linkRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousLinks);
        assertThat(externalFailureRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getRequestDescription()).isEqualTo(candidate.requestDescription());
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING);
            assertThat(failure.getReason()).isEqualTo("기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.");
        });
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(rowId);
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(20);
        });
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getId()).isEqualTo(targetId);
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(20);
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });

        when(external.fetchSupply(any())).thenReturn(supplyResponse(
                candidate.request().supplyInfoTypeCode(), "30", "12000000", "250000"
        ));

        var recovered = collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);

        assertThat(recovered.failedRequestCount()).isZero();
        assertThat(recovered.storedRowCount()).isOne();
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplySourceRepository.findAll()).singleElement().satisfies(supply ->
                assertThat(supply.getSuppliedUnitCount()).isEqualTo("30"));
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(rowId);
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(30);
        });
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getId()).isEqualTo(targetId);
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(30);
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
        assertThat(externalFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.RESOLVED));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 새_버전의_빈_응답이나_기존행_누락도_구버전_원천과_정제_공급행을_보존한다(boolean empty) {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        saveLhSources("10000000", "200000");
        completeLinks(source);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        Long rowId = supplyRowRepository.findAll().getFirst().getId();
        Long targetId = supplyTargetRepository.findAll().getFirst().getId();
        String previousRequest = candidate.requestDescription().replace("COLLECTION_VERSION=6", "COLLECTION_VERSION=5");
        String previousHash = LhAnnouncementCollectionCheckpoint.requestHashOf(previousRequest);
        var previousSources = supplySourceRepository.findAll();
        previousSources.forEach(supply -> supply.assignRequestHash(previousHash));
        supplySourceRepository.saveAll(previousSources);
        var previousDetails = detailSourceRepository.findAll();
        previousDetails.forEach(detail -> detail.assignRequestHash(previousHash));
        detailSourceRepository.saveAll(previousDetails);
        checkpointRepository.deleteAll();
        linkRepository.deleteAll();
        for (ExternalDataSource target : List.of(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY)) {
            progressStore.complete(target, source.getPblancId(), previousRequest, candidate.panId());
        }
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        ExternalDataResponse incomplete = response("[{\"dsList01\":[]}]");
        if (!empty) {
            incomplete = response("""
                    [{"dsList01":[{"SBD_LGO_NM":"동삼2","HTY_NNA":"다른 주택형",
                    "DDO_AR":"46.8","SPL_AR":"67.0","HSH_CNT":"100","NOW_HSH_CNT":"20"}]}]
                    """);
        }
        when(external.fetchSupply(any())).thenReturn(incomplete);
        when(external.fetchDetail(any())).thenReturn(response("[{\"dsEtcInfo\":[{\"CRC_RSN\":\"정정\"}]}]"));
        LhAnnouncementCandidateCollector collector = collector(external);

        assertThat(collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate).failedRequestCount())
                .isOne();
        assertThat(collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, candidate).failedRequestCount())
                .isZero();
        assertThat(mappingService.mapAll().failedSourceRowCount()).isOne();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();

        assertThat(supplySourceRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(previousSources);
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(rowId);
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(20);
        });
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getId()).isEqualTo(targetId);
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(20);
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "5년임대, PUBLIC_RENTAL_5Y",
            "10년임대, PUBLIC_RENTAL_10Y",
            "50년임대, PUBLIC_RENTAL_50Y",
            "국민임대, NATIONAL_RENTAL",
            "영구임대, PERMANENT_RENTAL",
            "행복주택, HAPPY_HOUSING",
            "통합공공임대, INTEGRATED_PUBLIC_RENTAL"
    })
    void 부분_응답은_정제행까지_보존하고_완전한_재수집으로_복구한다(String supplyType, RentalType rentalType) {
        saveComplex(rentalType.name());
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource("21026", supplyType));
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        saveLhSources("10000000", "200000");
        saveSupply(new LhAnnouncementSupplySource(1, PAN_ID, new LhAnnouncementSupplySourceSnapshot(
                "동삼2", "46A", "46.8", "67.0", "100", "20", "10000000", "200000")));
        completeLinks(source);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        var previousSources = supplySourceRepository.findAll();
        var previousCheckpoints = checkpointRepository.findAll();
        var previousLinks = linkRepository.findAll();
        var rowIds = supplyRowRepository.findAll().stream().map(SupplyRow::getId).toList();
        var targetIds = supplyTargetRepository.findAll().stream().map(SupplyTarget::getId).toList();
        assertThat(rowIds).hasSize(2);
        assertThat(targetIds).hasSize(2);
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchSupply(any())).thenReturn(supplyResponse(
                candidate.request().supplyInfoTypeCode(), "30", "12000000", "250000"));
        var collector = collector(external);

        var failed = collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);

        assertThat(failed.failedRequestCount()).isOne();
        assertThat(failed.storedRowCount()).isZero();
        assertThat(supplySourceRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousSources);
        assertThat(checkpointRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousCheckpoints);
        assertThat(linkRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousLinks);
        assertThat(externalFailureRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING);
            assertThat(failure.getReason()).contains("기존 공급행 1건이 누락");
        });
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyRowRepository.findAll()).extracting(SupplyRow::getId)
                .containsExactlyInAnyOrderElementsOf(rowIds);
        assertThat(supplyTargetRepository.findAll()).extracting(SupplyTarget::getId)
                .containsExactlyInAnyOrderElementsOf(targetIds);
        assertThat(supplyTargetRepository.findAll()).allSatisfy(target -> {
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(20);
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });

        when(external.fetchSupply(any())).thenReturn(repeatedSupplyResponse(
                candidate.request().supplyInfoTypeCode(), 2));

        assertThat(collector.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate).failedRequestCount())
                .isZero();
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyTargetRepository.findAll()).allSatisfy(target -> {
            assertThat(target.getSupplyHouseholdCount()).isEqualTo(30);
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000");
        });
        assertThat(externalFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.RESOLVED));
    }

    @Test
    void 기존_150행보다_새_단일_응답이_100행으로_줄면_완료_처리하지_않는다() {
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        var supplies = IntStream.range(0, 150)
                .mapToObj(index -> new LhAnnouncementSupplySource(index, PAN_ID,
                        new LhAnnouncementSupplySourceSnapshot(
                                "동삼2", "46A", "46.8", "67.0", "100", "20", "10000000", "200000")))
                .toList();
        sourceStore.replaceSupplies(PAN_ID, candidate.requestDescription(), supplies);
        completeLinks(source);
        var previousSources = supplySourceRepository.findAll();
        var previousCheckpoints = checkpointRepository.findAll();
        var previousLinks = linkRepository.findAll();
        LhAnnouncementExternalRepository external = mock(LhAnnouncementExternalRepository.class);
        when(external.fetchSupply(any())).thenReturn(repeatedSupplyResponse(
                candidate.request().supplyInfoTypeCode(), 100
        ));

        var result = collector(external).collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, candidate);

        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
        assertThat(supplySourceRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousSources);
        assertThat(checkpointRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousCheckpoints);
        assertThat(linkRepository.findAll()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(previousLinks);
    }

    private ExternalDataResponse repeatedSupplyResponse(String typeCode, int count) {
        ExternalDataResponse result = supplyResponse(typeCode, "30", "12000000", "250000");
        String dataset = "dsList01";
        if ("060".equals(typeCode)) {
            dataset = "dsList02";
        }
        var rows = (ArrayNode) result.body().get(1).get(dataset);
        var row = rows.get(0).deepCopy();
        for (int index = 1; index < count; index++) {
            rows.add(row.deepCopy());
        }
        return result;
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

    @ParameterizedTest
    @ValueSource(strings = {"공고문 참조", "별도 안내"})
    void 저장했던_임대료가_미제공으로_바뀌어도_마지막_정상_금액을_보존한다(String unavailableValue) {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10,000,000", "200,000");
        enrichmentService.enrichAll();
        assertThat(supplyTargetRepository.count()).isOne();

        supplySourceRepository.deleteAll();
        saveSupply(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", unavailableValue, unavailableValue
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
        assertThat(announcementRepository.count()).isOne();
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
    void 같은_공고의_첫_행에_조회_URL이_없어도_연결된_다음_행으로_매핑과_보강한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id-59B", "59B",
                new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        MyHomeAnnouncementSource unsupported = myHomeSource();
        ReflectionTestUtils.setField(unsupported, "url", null);
        myHomeSourceRepository.save(unsupported);
        MyHomeAnnouncementSource linked = myHomeSource();
        ReflectionTestUtils.setField(linked, "houseTyNm", "59B");
        ReflectionTestUtils.setField(linked, "houseSn", 2);
        ReflectionTestUtils.setField(linked, "sourceKey", "5:210261:2");
        myHomeSourceRepository.save(linked);
        saveLhSources("10,000,000", "200,000");
        completeLinks(linked);

        var mapping = mappingService.mapAll();
        var enrichment = enrichmentService.enrichAll();

        assertThat(mapping.failedSourceRowCount()).isZero();
        assertThat(enrichment.failedSourceCount()).isZero();
        assertThat(enrichment.updatedAnnouncementCount()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("정정 사유"));
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target ->
                assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000"));
    }

    @ParameterizedTest
    @CsvSource({
            "name, 공고명",
            "status, 공고 상태",
            "url, 원문 URL",
            "supplyType, 공급유형",
            "provider, 공급기관",
            "previousId, 이전 공고 식별자",
            "contact, 문의처",
            "postedDate, 모집 공고일",
            "startDate, 모집 시작일",
            "endDate, 모집 종료일",
            "winnerDate, 당첨자 발표일"
    })
    void 같은_공고의_공통값이_충돌하면_매핑과_보강이_기존_데이터를_보존한다(
            String conflictingField,
            String fieldName
    ) {
        saveComplex();
        MyHomeAnnouncementSource linked = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(linked);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        Long announcementId = announcementRepository.findAll().getFirst().getId();
        Long rowId = supplyRowRepository.findAll().getFirst().getId();
        Long scheduleId = scheduleRepository.findAll().getFirst().getId();
        Long attachmentId = attachmentRepository.findAll().getFirst().getId();
        Long targetId = supplyTargetRepository.findAll().getFirst().getId();

        sourceStore.replaceDetails(PAN_ID, requestDescriptionFor(PAN_ID), List.of(
                detail(0, "ETC_INFO", null, null, null, null, null, null, null, "변경된 정정 사유")
        ));
        sourceStore.replaceSupplies(PAN_ID, requestDescriptionFor(PAN_ID), List.of(
                new LhAnnouncementSupplySource(0, PAN_ID, new LhAnnouncementSupplySourceSnapshot(
                        "동삼2", "46A", "46.8", "67.0", "100", "20", "12,000,000", "250,000"
                ))
        ));
        if (conflictingField.equals("previousId")) {
            ReflectionTestUtils.setField(linked, "beforePblancId", "21024");
            myHomeSourceRepository.save(linked);
        }
        MyHomeAnnouncementSource conflicting = myHomeSource();
        ReflectionTestUtils.setField(conflicting, "houseSn", 2);
        ReflectionTestUtils.setField(conflicting, "sourceKey", "5:210261:2");
        switch (conflictingField) {
            case "name" -> ReflectionTestUtils.setField(conflicting, "pblancNm", "다른 입주자 모집공고");
            case "status" -> ReflectionTestUtils.setField(conflicting, "sttusNm", "마감");
            case "url" -> ReflectionTestUtils.setField(
                    conflicting, "url", linked.getUrl().replace("panId=100", "panId=200")
            );
            case "supplyType" -> ReflectionTestUtils.setField(conflicting, "suplyTyNm", "행복주택");
            case "provider" -> ReflectionTestUtils.setField(conflicting, "suplyInsttNm", "SH공사");
            case "previousId" -> ReflectionTestUtils.setField(conflicting, "beforePblancId", "21025");
            case "contact" -> ReflectionTestUtils.setField(conflicting, "refrnc", "02-000-0000");
            case "postedDate" -> ReflectionTestUtils.setField(conflicting, "rcritPblancDe", "20260913");
            case "startDate" -> ReflectionTestUtils.setField(conflicting, "beginDe", "20260825");
            case "endDate" -> ReflectionTestUtils.setField(conflicting, "endDe", "20260901");
            case "winnerDate" -> ReflectionTestUtils.setField(conflicting, "przwnerPresnatnDe", "20261107");
            default -> throw new IllegalArgumentException(conflictingField);
        }
        myHomeSourceRepository.save(conflicting);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isEqualTo(2);
        assertThat(mappingFailureRepository.findAll())
                .allSatisfy(failure -> assertThat(failure.getReason())
                        .isEqualTo(MyHomeAnnouncementMappingFailureReason.CONFLICTING_SOURCE_VALUE));
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();
        assertThat(enrichmentFailureRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getReason()).isEqualTo(LhAnnouncementEnrichmentFailureReason.INVALID_VALUE);
            assertThat(failure.getDetail()).contains(fieldName);
        });
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement -> {
            assertThat(announcement.getId()).isEqualTo(announcementId);
            assertThat(announcement.getLhPanId()).isEqualTo(PAN_ID);
            assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("정정 사유");
            assertThat(announcement.getReceptionPlace().getContact()).isEqualTo("1600-1004");
        });
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getId()).isEqualTo(rowId));
        assertThat(scheduleRepository.findAll()).singleElement().satisfies(schedule -> {
            assertThat(schedule.getId()).isEqualTo(scheduleId);
            assertThat(schedule.getName()).isEqualTo("접수");
            assertThat(schedule.getStartAt()).isEqualTo(LocalDateTime.of(2026, 8, 24, 10, 0));
        });
        assertThat(attachmentRepository.findAll()).singleElement().satisfies(attachment -> {
            assertThat(attachment.getId()).isEqualTo(attachmentId);
            assertThat(attachment.getFileName()).isEqualTo("공고문.pdf");
            assertThat(attachment.getFileUrl()).isEqualTo("https://example.com/file.pdf");
        });
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getId()).isEqualTo(targetId);
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
    }

    @Test
    void 공통값_충돌이_해소되면_보강을_재개하고_실패_이력을_해결한다() {
        saveComplex();
        MyHomeAnnouncementSource linked = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(linked);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        sourceStore.replaceDetails(PAN_ID, requestDescriptionFor(PAN_ID), List.of(
                detail(0, "ETC_INFO", null, null, null, null, null, null, null, "변경된 정정 사유")
        ));
        MyHomeAnnouncementSource conflicting = myHomeSource();
        ReflectionTestUtils.setField(conflicting, "houseSn", 2);
        ReflectionTestUtils.setField(conflicting, "sourceKey", "5:210261:2");
        ReflectionTestUtils.setField(conflicting, "url", linked.getUrl().replace("panId=100", "panId=200"));
        conflicting = myHomeSourceRepository.save(conflicting);

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();

        myHomeSourceRepository.delete(conflicting);
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getCorrectionCancellationReason()).isEqualTo("변경된 정정 사유"));
        assertThat(enrichmentFailureRepository.findAll()).singleElement().satisfies(failure ->
                assertThat(failure.getStatus()).isEqualTo(IngestFailureStatus.RESOLVED));
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
        // 주택형 이름 정정은 자동 교체 대상이 아니므로 확인 후 정정된 원천을 준비한다.
        supplySourceRepository.deleteAll(supplySourceRepository.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                "200", LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescriptionFor("200"))));
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
    void 새_주택형의_보강이_실패하면_이전_주택형과_금액을_함께_보존한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        HousingType previousType = housingTypeRepository.findAllByHousingComplex(complex).getFirst();
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id:59B", "59B",
                new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        switchLhSource(source, "59B", "12,000,000", "invalid-monthly-rent");

        assertThat(mappingService.mapAll().failedSourceRowCount()).isOne();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();

        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getHousingType().getId()).isEqualTo(previousType.getId())
        );
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });
    }

    @Test
    void 새_주택형의_금액이_미제공되면_이전_주택형과_금액을_함께_보존한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        HousingType previousType = housingTypeRepository.findAllByHousingComplex(complex).getFirst();
        HousingType nextType = housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id:59B", "59B",
                new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        switchLhSource(source, "59B", null, null);

        assertThat(mappingService.mapAll().failedSourceRowCount()).isOne();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isOne();
        assertThat(announcementRepository.findAll()).singleElement().satisfies(announcement ->
                assertThat(announcement.getLhPanId()).isEqualTo(PAN_ID)
        );

        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getHousingType().getId()).isEqualTo(previousType.getId())
        );
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("10000000");
            assertThat(target.getMonthlyRent()).isEqualByComparingTo("200000");
        });

        sourceStore.replaceSupplies("200", requestDescriptionFor("200"), List.of(new LhAnnouncementSupplySource(
                0, "200", new LhAnnouncementSupplySourceSnapshot(
                "동삼2", "59B", "46.8", "67.0", "100", "20", "12000000", "250000"
        ))));
        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();
        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getHousingType().getId()).isEqualTo(nextType.getId())
        );
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target ->
                assertThat(target.getMonthlyRent()).isEqualByComparingTo("250000")
        );
    }

    @Test
    void 새_주택형과_금액은_공고_매핑에서_함께_반영한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        HousingType newType = housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id:59B", "59B",
                new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        MyHomeAnnouncementSource source = myHomeSourceRepository.save(myHomeSource());
        saveLhSources("10,000,000", "200,000");
        completeLinks(source);
        mappingService.mapAll();
        enrichmentService.enrichAll();
        switchLhSource(source, "59B", "12,000,000", "250,000");

        assertThat(mappingService.mapAll().failedSourceRowCount()).isZero();

        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getHousingType().getId()).isEqualTo(newType.getId())
        );
        assertThat(supplyTargetRepository.findAll()).singleElement().satisfies(target -> {
            assertThat(target.getRentalDeposit()).isEqualByComparingTo("12000000");
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
        LhAnnouncementResponseFetcher fetcher = new LhAnnouncementResponseFetcher(
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

    private ExternalDataResponse supplyResponse(String typeCode, String suppliedCount, String deposit, String rent) {
        if (!"060".equals(typeCode)) {
            return supplyResponse(suppliedCount, deposit, rent);
        }
        return response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsList01":[],
                  "dsList02":[{"BZDT_NM":"동삼2","HTY_NM":"46A","RSDN_DDO_AR":"46.8",
                  "SPL_AR":"67.0","TOT_HSH_CNT":"100","SIL_HSH_CNT":"%s","LS_GMY":"%s",
                  "MM_RFE":"%s"}]}]
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

    @Test
    void 잘못_보강했던_당첨자_발표일을_재정제로_복구하고_서류대상자_발표를_분리한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mapMyHomeSource();
        saveLhSources("10000000", "200000");
        detailSourceRepository.deleteAll();
        saveDetails(new LhAnnouncementDetailResponseParser().parse(PAN_ID, JsonMapper.builder().build().readTree("""
                [{"dsSplScdl":[{"ACP_DTTM":"~", "SBSC_ACP_ST_DT":"2026.08.24",
                "SBSC_ACP_CLSG_DT":"2026.08.31", "PPR_SBM_OPE_ANC_DT":"2026.08.26",
                "PZWR_ANC_DT":"2026.11.06"}]}]
                """)));
        Announcement announcement = announcementRepository.findAll().getFirst();
        Long previousScheduleId = scheduleRepository.save(AnnouncementSchedule.createFromSource(
                announcement, "LH:" + PAN_ID + ":SCHEDULE:0:WINNER_ANNOUNCEMENT",
                ScheduleType.WINNER_ANNOUNCEMENT, "당첨자 발표", LocalDateTime.of(2026, 8, 26, 0, 0),
                LocalDateTime.of(2026, 8, 26, 0, 0), 1
        )).getId();

        assertThat(enrichmentService.enrichAll().failedSourceCount()).isZero();

        assertThat(scheduleRepository.findAll())
                .filteredOn(schedule -> schedule.getScheduleType() == ScheduleType.WINNER_ANNOUNCEMENT)
                .singleElement().satisfies(schedule -> {
                    assertThat(schedule.getId()).isEqualTo(previousScheduleId);
                    assertThat(schedule.getStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 0, 0));
                });
        assertThat(scheduleRepository.findAll())
                .filteredOn(schedule -> schedule.getScheduleType() == ScheduleType.ETC)
                .singleElement().satisfies(schedule -> {
                    assertThat(schedule.getName()).isEqualTo("서류제출 대상자 발표");
                    assertThat(schedule.getStartAt()).isEqualTo(LocalDateTime.of(2026, 8, 26, 0, 0));
                });
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
