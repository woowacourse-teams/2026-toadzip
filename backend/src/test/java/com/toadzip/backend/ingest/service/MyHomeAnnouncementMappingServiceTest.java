package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSourceData;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementSourceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MyHomeAnnouncementMappingServiceTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-28T00:00:00Z");

    private static final String PNU = "1111010100100010000";

    @Autowired
    private MyHomeAnnouncementMappingService service;

    @Autowired
    private MyHomeAnnouncementSourceRepository sourceRepository;

    @Autowired
    private MyHomeAnnouncementMappingFailureRepository failureRepository;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private HousingComplexRepository complexRepository;

    @Autowired
    private HousingTypeRepository housingTypeRepository;

    @BeforeEach
    void setUp() {
        supplyRowRepository.deleteAll();
        announcementRepository.deleteAll();
        housingTypeRepository.deleteAll();
        complexRepository.deleteAll();
        failureRepository.deleteAll();
        sourceRepository.deleteAll();
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

    @Test
    void 변경된_원본_정보를_기존_공고와_공급행에_반영한다() {
        saveMappedComplex();
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));
        service.mapAll();
        MyHomeAnnouncementSource source = sourceRepository.findAll().getFirst();
        MyHomeAnnouncementSourceData changed = data("21026", 1, "서울주택도시공사", "동삼2 변경");
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
        sourceRepository.save(source(0, data("21026", 1, "부산도시공사", "동삼2")));

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
        MyHomeAnnouncementSourceData sourceData = withHousingType(
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
    void 필수_날짜를_변환할_수_없으면_공고를_만들지_않고_실패를_기록한다() {
        MyHomeAnnouncementSourceData invalid = withPostedDate(
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
        MyHomeAnnouncementSourceData withoutContact = withContact(
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

    private void saveMappedComplex() {
        saveMappedComplex("동삼2", "123:NATIONAL_RENTAL");
    }

    private HousingComplex saveMappedComplex(String name, String sourceIdentifier) {
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
                "NATIONAL_RENTAL",
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

    private MyHomeAnnouncementSource source(int order, MyHomeAnnouncementSourceData data) {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(order, data);
        source.markCollectedAt(COLLECTED_AT);
        return source;
    }

    private MyHomeAnnouncementSourceData data(
            String pblancId,
            int houseSn,
            String provider,
            String complexName
    ) {
        return new MyHomeAnnouncementSourceData(
                pblancId,
                houseSn,
                "모집중",
                "국민임대 입주자 모집공고",
                provider,
                "아파트",
                "국민임대",
                null,
                "20260813",
                "20261106",
                "20260824",
                "20260831",
                "1600-1004",
                "https://example.com/announcements/" + pblancId,
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

    private MyHomeAnnouncementSourceData withNameAndSupplyCount(
            MyHomeAnnouncementSourceData data,
            String name,
            int supplyCount
    ) {
        return new MyHomeAnnouncementSourceData(
                data.pblancId(), data.houseSn(), data.sttusNm(), name, data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), supplyCount, data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceData withPrevious(MyHomeAnnouncementSourceData data, String previousIdentifier) {
        return new MyHomeAnnouncementSourceData(
                data.pblancId(), data.houseSn(), "정정공고", data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), previousIdentifier, data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceData withPostedDate(MyHomeAnnouncementSourceData data, String postedDate) {
        return new MyHomeAnnouncementSourceData(
                data.pblancId(), data.houseSn(), data.sttusNm(), data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), data.beforePblancId(), postedDate,
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceData withContact(MyHomeAnnouncementSourceData data, String contact) {
        return new MyHomeAnnouncementSourceData(
                data.pblancId(), data.houseSn(), data.sttusNm(), data.pblancNm(), data.suplyInsttNm(),
                data.houseTyNm(), data.suplyTyNm(), data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), contact, data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }

    private MyHomeAnnouncementSourceData withHousingType(MyHomeAnnouncementSourceData data, String housingType) {
        return new MyHomeAnnouncementSourceData(
                data.pblancId(), data.houseSn(), data.sttusNm(), data.pblancNm(), data.suplyInsttNm(),
                housingType, data.suplyTyNm(), data.beforePblancId(), data.rcritPblancDe(),
                data.przwnerPresnatnDe(), data.beginDe(), data.endDe(), data.refrnc(), data.url(),
                data.pcUrl(), data.mobileUrl(), data.hsmpNm(), data.brtcNm(), data.signguNm(),
                data.fullAdres(), data.rnCodeNm(), data.refrnLegaldongNm(), data.pnu(), data.heatMthdNm(),
                data.totHshldCo(), data.sumSuplyCo(), data.rentGtn(), data.enty(), data.surlus(), data.mtRntchrg()
        );
    }
}
