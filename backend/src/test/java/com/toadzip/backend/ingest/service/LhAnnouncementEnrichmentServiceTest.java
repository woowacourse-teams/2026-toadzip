package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.domain.LhAnnouncementSupplySourceData;
import com.toadzip.backend.ingest.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSourceData;
import com.toadzip.backend.ingest.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.repository.LhAnnouncementEnrichmentFailureRepository;
import com.toadzip.backend.ingest.repository.LhAnnouncementSupplySourceRepository;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementSourceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LhAnnouncementEnrichmentServiceTest {

    private static final String PNU = "1111010100100010000";
    private static final String PAN_ID = "100";

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
    }

    @Test
    void 기존_LH_공고를_일정_첨부파일_접수처_공급정보로_보강하고_반복해도_중복하지_않는다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mappingService.mapAll();
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
    void 마이홈_URL에_panId가_없으면_공고를_새로_생성하지_않고_실패를_기록한다() {
        saveComplex();
        MyHomeAnnouncementSource source = myHomeSource();
        source.replaceWith(new MyHomeAnnouncementSourceData(
                "21026", 1, "모집중", "국민임대 입주자 모집공고", "LH서울", "아파트", "국민임대", null,
                "20260813", "20261106", "20260824", "20260831", "1600-1004",
                "https://example.com/announcements", null, null, "동삼2", "서울특별시", "종로구",
                "서울특별시 종로구 테스트로 1", "테스트로", "테스트동", PNU, "지역난방", "100", 20,
                10_000_000L, 2_000_000L, 8_000_000L, 200_000L
        ));
        myHomeSourceRepository.save(source);
        mappingService.mapAll();

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isOne();
        assertThat(announcementRepository.count()).isOne();
        assertThat(enrichmentFailureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.PAN_ID_NOT_FOUND);
    }

    @Test
    void 공고문_참조로_표시된_임대료는_추정하지_않고_공급행만_보강한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mappingService.mapAll();
        saveLhSources("공고문 참조", "공고문 참조");

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isZero();
        assertThat(report.createdSupplyTargetCount()).isZero();
        assertThat(supplyTargetRepository.count()).isZero();
    }

    @Test
    void 긴_접수_안내문은_접수처명으로_저장하지_않고_기본명을_사용한다() {
        saveComplex();
        myHomeSourceRepository.save(myHomeSource());
        mappingService.mapAll();
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
        mappingService.mapAll();
        saveLhSources("10,000,000", "200,000", "LH 현장접수처", "99Z");

        var report = enrichmentService.enrichAll();

        assertThat(report.failedSourceCount()).isOne();
        assertThat(supplyRowRepository.findAll()).singleElement().satisfies(row ->
                assertThat(row.getLhSourceSupplyRowIdentifier()).isNull()
        );
        assertThat(supplyTargetRepository.count()).isZero();
        assertThat(enrichmentFailureRepository.findAll()).singleElement()
                .extracting(failure -> failure.getReason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.HOUSING_TYPE_NOT_FOUND);
    }

    @Test
    void LH_공급행_순서가_바뀌어도_주택형을_다시_확인해_올바른_공급행을_보강한다() {
        saveComplex();
        HousingComplex complex = housingComplexRepository.findAll().getFirst();
        HousingType secondType = housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id-59B", "59B", new BigDecimal("59.8000"), new BigDecimal("84.0000")
        ));
        myHomeSourceRepository.save(myHomeSource());
        mappingService.mapAll();
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
        supplySourceRepository.save(new LhAnnouncementSupplySource(1, PAN_ID,
                new LhAnnouncementSupplySourceData(
                        "동삼2", "59B", "59.8", "84.0", "80", "10", "20,000,000", "300,000"
                )));
        enrichmentService.enrichAll();

        supplySourceRepository.deleteAll();
        supplySourceRepository.saveAll(List.of(
                new LhAnnouncementSupplySource(0, PAN_ID, new LhAnnouncementSupplySourceData(
                        "동삼2", "59B", "59.8", "84.0", "80", "10", "21,000,000", "310,000"
                )),
                new LhAnnouncementSupplySource(1, PAN_ID, new LhAnnouncementSupplySourceData(
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

    private void saveComplex() {
        Address address = Address.create(
                "서울특별시 종로구 테스트로 1", PNU, PNU.substring(0, 10), "11", "11110",
                new BigDecimal("37.566206"), new BigDecimal("126.977706")
        );
        HousingComplex complex = housingComplexRepository.save(HousingComplex.createFromMyHome(
                "동삼2", "123:NATIONAL_RENTAL", "NATIONAL_RENTAL", address, 100, "LH", null,
                "DISTRICT", "APARTMENT", "CORRIDOR", true, 80
        ));
        housingTypeRepository.save(HousingType.createFromMyHome(
                complex, "source-housing-type-id", "46A", new BigDecimal("46.8000"), new BigDecimal("67.0000")
        ));
    }

    private MyHomeAnnouncementSource myHomeSource() {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0, new MyHomeAnnouncementSourceData(
                "21026", 1, "모집중", "국민임대 입주자 모집공고", "LH서울", "아파트", "국민임대", null,
                "20260813", "20261106", "20260824", "20260831", "1600-1004",
                "https://example.com/announcements?panId=" + PAN_ID, null, null, "동삼2", "서울특별시", "종로구",
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
        detailSourceRepository.saveAll(List.of(
                detail(0, "ETC_INFO", null, null, null, null, null, null, null, "정정 사유"),
                detail(1, "SCHEDULE", "2026.08.24 10:00 ~ 2026.08.31 17:00", null, null, null, null, null, null, null),
                detail(2, "RECEPTION", null, "서울특별시 종로구 접수로 1", "101호", "1600-1004", receptionGuidance, null, null, null),
                detail(3, "ANNOUNCEMENT_FILE", null, null, null, null, null, "공고문.pdf", "https://example.com/file.pdf", null),
                detail(4, "COMPLEX", null, null, null, null, null, null, null, null)
        ));
        supplySourceRepository.save(new LhAnnouncementSupplySource(0, PAN_ID,
                new LhAnnouncementSupplySourceData(
                        "동삼2", housingTypeName, "46.8", "67.0", "100", "20", deposit, rent
                )));
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
