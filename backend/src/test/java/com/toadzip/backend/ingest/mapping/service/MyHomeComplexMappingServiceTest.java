package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class MyHomeComplexMappingServiceTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-27T00:00:00Z");
    private static final String NEW_ADDRESS = "서울특별시 종로구 새길 2";
    private static final String NEW_PNU = "1111010100200020000";
    private static final String SPLIT_ADDRESS = "대전광역시 서구 둔산로 100";

    @Autowired
    private MyHomeComplexMappingService service;

    @Autowired
    private MyHomeComplexSourceFixtures sourceRepository;

    @Autowired
    private MyHomeComplexMappingFailureRepository failureRepository;

    @Autowired
    private HousingComplexRepository complexRepository;

    @Autowired
    private HousingTypeRepository housingTypeRepository;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    private com.toadzip.backend.ingest.mapping.repository.AnnouncementSupplyMatchDecisionRepository matchDecisions;

    @Autowired
    private SupplyMatchingFingerprint fingerprints;

    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    void setTransactions(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @MockitoBean
    private RoadAddressGeocodingService geocodingService;

    @BeforeEach
    void setUp() {
        supplyRowRepository.deleteAll();
        announcementRepository.deleteAll();
        housingTypeRepository.deleteAll();
        complexRepository.deleteAll();
        failureRepository.deleteAll();
        sourceRepository.deleteAll();
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedRoadAddress(
                "서울특별시 종로구 테스트로 1",
                new BigDecimal("37.56620552"),
                new BigDecimal("126.97770648")
        ));
    }

    @Test
    void 전체_정제가_좌표와_단지와_주택형을_한_번에_저장한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source("59A", "59.9500", "24.1000")
        ));
        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isOne();
        assertThat(report.createdHousingTypeCount()).isEqualTo(2);
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getAddress().getLatitude()).isEqualByComparingTo("37.566206");
            assertThat(complex.getAddress().getLongitude()).isEqualByComparingTo("126.977706");
        });
    }

    @Test
    void 마이홈에_없던_관리자_등록_단지는_마이홈_단지가_생기면_새로_만들지_않고_연결해_비워_둔_값만_채운다() {
        var admin = complexRepository.save(HousingComplex.create("관리자 등록 단지", HousingComplex.ADMIN_SOURCE_PREFIX + "test",
                "NATIONAL_RENTAL", com.toadzip.backend.housing.domain.Address.create("서울특별시 종로구 테스트로 1",
                        "1111010100100010000", "1111010100", "11", "11110", new BigDecimal("37.5"), new BigDecimal("126.9")),
                350, "LH", null, null, null, null, null, null, null, null));
        var adminType = housingTypeRepository.save(HousingType.createFromMyHome(admin,
                HousingType.ADMIN_SOURCE_PREFIX + "46A", "46A", new BigDecimal("46.8000"), null));
        sourceRepository.saveAll(List.of(source("46A", "46.8000", "20.2000"), source("59A", "59.9500", "24.1000")));

        var report = service.mapAll();
        service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(report.createdComplexCount()).isZero();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getId()).isEqualTo(admin.getId());
            // 관리자가 정한 단지명·세대수는 유지하고, 비워 둔 주차대수·준공일은 마이홈 값으로 채운다.
            assertThat(complex.getName()).isEqualTo("관리자 등록 단지");
            assertThat(complex.getTotalHouseholdCount()).isEqualTo(350);
            assertThat(complex.getParkingSpaceCount()).isEqualTo(80);
            assertThat(complex.getCompletionDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        });
        // 관리자가 만든 46A는 마이홈 46A로 이어받아 주택형이 두 번 생기지 않는다.
        assertThat(housingTypeRepository.findAll()).extracting(HousingType::getName).containsExactlyInAnyOrder("46A", "59A");
        assertThat(housingTypeRepository.findById(adminType.getId()).orElseThrow().getSourceHousingTypeIdentifier())
                .doesNotStartWith(HousingType.ADMIN_SOURCE_PREFIX);
    }

    @Test
    void 주소가_바뀌면_현재_위치정보에서_새_좌표를_읽는다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        service.mapAll();
        sourceRepository.deleteAll();
        sourceRepository.save(source(withAddress(data(123L, "46A", "46.8000", "20.2000",
                "서울주택도시공사", "20200101"), NEW_ADDRESS, NEW_PNU)));
        when(geocodingService.geocode(NEW_ADDRESS)).thenReturn(new GeocodedRoadAddress(
                NEW_ADDRESS, new BigDecimal("37.570000"), new BigDecimal("126.980000")));

        var report = service.mapAll();

        assertThat(report.updatedComplexCount()).isOne();
        verify(geocodingService).geocode(NEW_ADDRESS);
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getAddress().getRoadAddress()).isEqualTo(NEW_ADDRESS);
            assertThat(complex.getAddress().getPnu()).isEqualTo(NEW_PNU);
            assertThat(complex.getAddress().getLatitude()).isEqualByComparingTo("37.570000");
        });
    }

    @Test
    void 바뀐_주소의_좌표_변환이_실패하면_기존_단지를_보존한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        service.mapAll();
        sourceRepository.deleteAll();
        sourceRepository.save(source(withAddress(data(123L, "46A", "46.8000", "20.2000",
                "서울주택도시공사", "20200101"), NEW_ADDRESS, NEW_PNU)));
        when(geocodingService.geocode(NEW_ADDRESS)).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND, "새 주소를 찾지 못했습니다."));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getAddress().getRoadAddress()).isEqualTo("서울특별시 종로구 테스트로 1");
            assertThat(complex.getAddress().getPnu()).isEqualTo("1111010100100010000");
        });
    }

    @Test
    void 공급기관과_관계없이_마이홈_원천을_단지와_주택형으로_매핑한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source("59A", "59.9500", "24.1000")
        ));

        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isOne();
        assertThat(report.createdHousingTypeCount()).isEqualTo(2);
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getSourceComplexIdentifier()).isEqualTo("123:NATIONAL_RENTAL");
            assertThat(complex.getSupplyType()).isEqualTo("NATIONAL_RENTAL");
            assertThat(complex.getProvider()).isEqualTo("SH");
            assertThat(complex.getAddress().getLegalDongCode()).isEqualTo("1111010100");
            assertThat(complex.getAddress().getCityCountyDistrictCode()).isEqualTo("11110");
            assertThat(complex.getAddress().getLatitude()).isEqualByComparingTo("37.566206");
            assertThat(complex.getAddress().getLongitude()).isEqualByComparingTo("126.977706");
            assertThat(complex.getCompletionDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        });
        assertThat(housingTypeRepository.findAll())
                .anySatisfy(type -> {
                    assertThat(type.getName()).isEqualTo("46A");
                    assertThat(type.getExclusiveArea()).isEqualByComparingTo("46.8000");
                    assertThat(type.getSupplyArea()).isEqualByComparingTo("67.0000");
                    assertThat(type.getTotalHouseholdCount()).isNull();
                    assertThat(type.getFloorPlanUrl()).isNull();
                })
                .extracting(type -> type.getName())
                .containsExactlyInAnyOrder("46A", "59A");
    }

    @Test
    void 주택형별_마이홈_금액을_단지_범위로_저장하고_재매핑에서_갱신한다() {
        MyHomeComplexSourceSnapshot firstData = data(
                123L, "46A", "46.8000", "20.2000", "서울주택도시공사", "20200101");
        MyHomeComplexSourceSnapshot secondData = data(
                123L, "59A", "59.9500", "24.1000", "서울주택도시공사", "20200101");
        MyHomeComplexSource first = source(withPrices(firstData, 10_000_000L, 200_000L));
        MyHomeComplexSource second = source(withPrices(secondData, 20_000_000L, 100_000L));
        sourceRepository.saveAll(List.of(first, second));

        service.mapAll();

        assertThat(housingTypeRepository.findAll()).anySatisfy(type -> {
            assertThat(type.getName()).isEqualTo("46A");
            assertThat(type.getBasicRentalCondition().deposit()).isEqualTo(10_000_000L);
            assertThat(type.getBasicRentalCondition().monthlyRent()).isEqualTo(200_000L);
            assertThat(type.getBasicRentalCondition().collectedAt()).isEqualTo(COLLECTED_AT);
        }).anySatisfy(type -> {
            assertThat(type.getName()).isEqualTo("59A");
            assertThat(type.getBasicRentalCondition().deposit()).isEqualTo(20_000_000L);
            assertThat(type.getBasicRentalCondition().monthlyRent()).isEqualTo(100_000L);
        });

        assertThat(complexRepository.findAll()).singleElement().satisfies(complex ->
                assertThat(complex.getRentalPriceRange()).isNull());

        first.replaceWith(withPrices(firstData, null, null));
        second.replaceWith(withPrices(secondData, 30_000_000L, 300_000L));
        sourceRepository.saveAll(List.of(first, second));

        var report = service.mapAll();

        assertThat(report.updatedComplexCount()).isZero();
        assertThat(report.updatedHousingTypeCount()).isEqualTo(2);
        assertThat(housingTypeRepository.findAll()).anySatisfy(type -> {
            assertThat(type.getName()).isEqualTo("46A");
            assertThat(type.getBasicRentalCondition().deposit()).isNull();
            assertThat(type.getBasicRentalCondition().monthlyRent()).isNull();
        }).anySatisfy(type -> {
            assertThat(type.getName()).isEqualTo("59A");
            assertThat(type.getBasicRentalCondition().deposit()).isEqualTo(30_000_000L);
            assertThat(type.getBasicRentalCondition().monthlyRent()).isEqualTo(300_000L);
        });
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex ->
                assertThat(complex.getRentalPriceRange()).isNull());
    }

    @Test
    void 수집시각이_없는_과거_원천은_기본금액을_제공하지_않고_다른_단지도_정제한다() {
        sourceRepository.saveAll(List.of(
                MyHomeComplexSource.from(withPrices(
                        data(123L, "46A", "46.8000", "20.2000", "서울주택도시공사", "20200101"),
                        10_000_000L, 200_000L)),
                source(withPrices(
                        data(124L, "59A", "59.9500", "24.1000", "서울주택도시공사", "20200101"),
                        20_000_000L, 0L))
        ));

        var report = service.mapAll();

        assertThat(report.createdHousingTypeCount()).isEqualTo(2);
        assertThat(housingTypeRepository.findAll()).anySatisfy(type -> {
            assertThat(type.getName()).isEqualTo("46A");
            assertThat(type.getBasicRentalCondition()).isNull();
        }).anySatisfy(type -> {
            assertThat(type.getName()).isEqualTo("59A");
            assertThat(type.getBasicRentalCondition().deposit()).isEqualTo(20_000_000L);
            assertThat(type.getBasicRentalCondition().monthlyRent()).isZero();
        });
    }

    @Test
    void 마이홈_금액이_전혀_없으면_단지_금액을_미제공으로_유지한다() {
        sourceRepository.save(source(withPrices(
                data(123L, "46A", "46.8000", "20.2000", "서울주택도시공사", "20200101"),
                null, null)));

        service.mapAll();
        var repeated = service.mapAll();

        assertThat(complexRepository.findAll()).singleElement()
                .satisfies(complex -> assertThat(complex.getRentalPriceRange()).isNull());
        assertThat(repeated.unchangedComplexCount()).isOne();
    }

    @Test
    void 동일한_원천을_반복_매핑해도_단지와_주택형을_추가하지_않는다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        service.mapAll();

        var report = service.mapAll();

        assertThat(report.unchangedComplexCount()).isOne();
        assertThat(report.unchangedHousingTypeCount()).isOne();
        assertThat(report.createdComplexCount()).isZero();
        assertThat(report.createdHousingTypeCount()).isZero();
        assertThat(complexRepository.count()).isOne();
        assertThat(housingTypeRepository.count()).isOne();
        verify(geocodingService, times(2)).geocode(anyString());
    }

    @Test
    void 위치정보의_좌표가_갱신되면_재정제에서_현재_좌표를_반영한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        service.mapAll();
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedRoadAddress(
                "서울특별시 종로구 테스트로 1",
                new BigDecimal("37.570000"),
                new BigDecimal("126.980000")
        ));

        var report = service.mapAll();

        assertThat(report.updatedComplexCount()).isOne();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getAddress().getLatitude()).isEqualByComparingTo("37.570000");
            assertThat(complex.getAddress().getLongitude()).isEqualByComparingTo("126.980000");
        });
    }

    @Test
    void 같은_주택형명이라도_면적이_다르면_서로_다른_주택형으로_매핑한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source("46A", "47.8000", "21.2000")
        ));

        var report = service.mapAll();

        assertThat(report.createdHousingTypeCount()).isEqualTo(2);
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(housingTypeRepository.findAll())
                .extracting(type -> type.getExclusiveArea())
                .containsExactlyInAnyOrder(new BigDecimal("46.8000"), new BigDecimal("47.8000"));
    }

    @Test
    void 변경된_마이홈_단지_정보를_기존_단지에_갱신한다() {
        MyHomeComplexSource source = source("46A", "46.8000", "20.2000");
        sourceRepository.save(source);
        service.mapAll();
        Long complexId = complexRepository.findAll().getFirst().getId();
        source.replaceWith(data(123L, "46A", "46.8000", "20.2000", "경기주택도시공사", "20200101"));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.updatedComplexCount()).isOne();
        assertThat(report.unchangedHousingTypeCount()).isOne();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getId()).isEqualTo(complexId);
            assertThat(complex.getProvider()).isEqualTo("GH");
        });
    }

    @Test
    void 변경된_주택형_원천_스냅샷을_중복_없이_반영한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        service.mapAll();
        Long housingTypeId = housingTypeRepository.findAll().getFirst().getId();
        sourceRepository.deleteAll();
        MyHomeComplexSource correctedSource = source("46A", "47.12345", "20.2000");
        sourceRepository.save(correctedSource);

        var report = service.mapAll();

        assertThat(report.updatedHousingTypeCount()).isOne();
        assertThat(report.createdHousingTypeCount()).isZero();
        assertThat(report.deletedHousingTypeCount()).isZero();
        assertThat(housingTypeRepository.findAll()).singleElement().satisfies(type -> {
            assertThat(type.getId()).isEqualTo(housingTypeId);
            assertThat(type.getSourceHousingTypeIdentifier()).isEqualTo(correctedSource.getSourceKey());
            assertThat(type.getExclusiveArea()).isEqualByComparingTo("47.1235");
        });
    }

    @Test
    void 공고가_참조하는_주택형은_원천에서_사라져도_보존한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source("59A", "59.9500", "24.1000")
        ));
        service.mapAll();
        var complex = complexRepository.findAll().getFirst();
        var referencedType = housingTypeRepository.findAll()
                .stream()
                .filter(type -> type.getName().equals("46A"))
                .findFirst()
                .orElseThrow();
        Announcement announcement = announcementRepository.save(announcement());
        supplyRowRepository.save(supplyRow(announcement, complex, referencedType));
        sourceRepository.deleteAll();
        sourceRepository.save(source("59A", "59.9500", "24.1000"));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(report.deletedHousingTypeCount()).isZero();
        assertThat(housingTypeRepository.existsById(referencedType.getId())).isTrue();
        assertThat(supplyRowRepository.existsByHousingType(referencedType)).isTrue();
    }

    @Test
    void 관리자_수정_주택형은_공고가_없어도_원천_삭제에_따라_삭제하지_않는다() {
        sourceRepository.saveAll(List.of(source("46A", "46.8000", "20.2000"),
                source("59A", "59.9500", "24.1000")));
        service.mapAll();
        HousingType type = housingTypeRepository.findAll().stream()
                .filter(item -> item.getName().equals("46A")).findFirst().orElseThrow();
        type.reviseByAdmin("관리자 확인 주택형", new BigDecimal("47.1234"), null);
        housingTypeRepository.saveAndFlush(type);
        sourceRepository.deleteAll();
        sourceRepository.save(source("59A", "59.9500", "24.1000"));

        var report = service.mapAll();

        assertThat(report.deletedHousingTypeCount()).isZero();
        assertThat(housingTypeRepository.findById(type.getId())).hasValueSatisfying(persisted -> {
            assertThat(persisted.getName()).isEqualTo("관리자 확인 주택형");
            assertThat(persisted.getExclusiveArea()).isEqualByComparingTo("47.1234");
            assertThat(persisted.isAdminModified()).isTrue();
        });
    }

    @Test
    void 필수값_변환에_실패한_단지는_건너뛰고_원천과_실패_사유를_기록한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source(dataWith(
                        456L, "59A", "59.9500", "24.1000", "부산도시공사",
                        null, "국민임대", "20200101", "지역난방", "아파트", "복도식", "전체동 설치"
                ))
        ));

        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isOne();
        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(complexRepository.findAll())
                .extracting(complex -> complex.getSourceComplexIdentifier())
                .containsExactly("123:NATIONAL_RENTAL");
        assertThat(service.findFailures(0, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.sourceComplexIdentifier()).isEqualTo("456:NATIONAL_RENTAL");
            assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE);
            assertThat(failure.detail()).contains("단지명");
            assertThat(failure.sourceKey()).isNotBlank();
        });
    }

    @Test
    void 변환_불가로_바뀐_원천은_전체_정제에서_한_번만_실패로_집계한다() {
        MyHomeComplexSource source = source("46A", "46.8000", "20.2000");
        sourceRepository.save(source);
        service.mapAll();
        source.replaceWith(dataWith(
                123L, "46A", "46.8000", "20.2000", "서울주택도시공사",
                null, "국민임대", "20200101", "지역난방", "아파트", "복도식", "전체동 설치"
        ));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(complexRepository.count()).isOne();
        verify(geocodingService, times(1)).geocode(anyString());
    }

    @Test
    void 실패했던_원천이_정상화되면_현재_실패_목록에서_제거한다() {
        MyHomeComplexSource source = source(data(
                123L,
                "46A",
                "46.8000",
                "20.2000",
                "서울주택도시공사",
                "20200101"
        ));
        source.replaceWith(dataWith(
                123L, "46A", "46.8000", "20.2000", "서울주택도시공사",
                null, "국민임대", "20200101", "지역난방", "아파트", "복도식", "전체동 설치"
        ));
        sourceRepository.save(source);
        service.mapAll();
        source.replaceWith(dataWith(
                123L,
                "46A",
                "46.8000",
                "20.2000",
                "서울주택도시공사",
                "테스트 단지", "국민임대", "20200101", "지역난방", "아파트", "복도식", "전체동 설치"
        ));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(service.findFailures(0, 100)).isEmpty();
    }

    @Test
    void 매입임대는_정제와_실패_대상에서_제외한다() {
        sourceRepository.save(source(dataWith(
                123L, "46A", "46.8000", "20.2000", "LH서울", "매입임대 주택",
                "매입임대", null, null, null, null, null
        )));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(complexRepository.count()).isZero();
    }

    @Test
    void 장기전세_다가구주택만_정제와_실패_대상에서_제외하고_아파트_장기전세는_유지한다() {
        sourceRepository.saveAll(List.of(
                source(dataWith(
                        1040L, null, "40.4800", "3.9100", "대구도시개발공사", "아그네스",
                        "장기전세", null, "개별가스난방", "다가구주택", "혼합식", "미설치"
                )),
                source(dataWith(
                        2000L, "59A", "59.9500", "24.1000", "SH공사", "장기전세 아파트",
                        "장기전세", "20200101", "지역난방", "아파트", "계단식", "전체동 설치"
                ))
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(service.findFailures(0, 100)).isEmpty();
        assertThat(complexRepository.findAll())
                .extracting(complex -> complex.getSourceComplexIdentifier())
                .containsExactly("2000:LONG_TERM_JEONSE");
    }

    @Test
    void 주택형명이_매입임대인_원천은_공급유형과_관계없이_제외한다() {
        sourceRepository.saveAll(List.of(
                source(dataWith(
                        3000L, "매입임대", "40.0000", "5.0000", "성남시", "매입임대주택",
                        "10년임대", null, null, "다세대주택", null, null
                )),
                source(dataWith(
                        3001L, "59A", "59.9500", "24.1000", "LH경기남부", "10년임대 아파트",
                        "10년임대", null, null, "아파트", null, null
                ))
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(complexRepository.findAll())
                .extracting(complex -> complex.getSourceComplexIdentifier())
                .containsExactly("3001:PUBLIC_RENTAL_10Y");
    }

    @Test
    void 같은_hsmpSn이라도_공급유형이_다르면_다른_단지로_만든다() {
        sourceRepository.saveAll(List.of(
                source(dataWith(
                        123L, "46A", "46.8000", "20.2000", "LH서울", "테스트 단지",
                        "국민임대", "20200101", "지역난방", "아파트", "복도식", "전체동 설치"
                )),
                source(dataWith(
                        123L, "59A", "59.9500", "24.1000", "LH서울", "테스트 단지",
                        "장기전세", null, null, "아파트", null, null
                ))
        ));

        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isEqualTo(2);
        assertThat(complexRepository.findAll())
                .extracting(complex -> complex.getSourceComplexIdentifier())
                .containsExactlyInAnyOrder("123:NATIONAL_RENTAL", "123:LONG_TERM_JEONSE");
    }

    @Test
    void 선택_정보가_누락되어도_단지를_매핑한다() {
        sourceRepository.save(source(dataWith(
                123L, "46A", "46.8000", "20.2000", "LH서울", "테스트 단지",
                "행복주택", null, null, null, null, null
        )));

        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isOne();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getCompletionDate()).isNull();
            assertThat(complex.getHeatingType()).isNull();
            assertThat(complex.getHousingType()).isNull();
            assertThat(complex.getCorridorType()).isNull();
            assertThat(complex.getElevatorInstalled()).isNull();
        });
    }

    @Test
    void 주소_좌표_변환에_실패하면_단지를_저장하지_않고_실패_사유를_기록한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        when(geocodingService.geocode(anyString())).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND,
                "원본과 일치하는 도로명주소를 찾지 못했습니다."
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(report.operationalFailedSourceRowCount()).isZero();
        assertThat(complexRepository.findAll()).isEmpty();
        assertThat(service.findFailures(0, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.GEOCODING_ERROR);
            assertThat(failure.detail()).contains("ADDRESS_NOT_FOUND");
        });
    }

    @Test
    void 좌표_변환에_실패한_원천이_사라지면_현재_실패에서_해결한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        when(geocodingService.geocode(anyString())).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND,
                "원본과 일치하는 도로명주소를 찾지 못했습니다."
        ));
        service.mapAll();
        sourceRepository.deleteAll();

        service.mapAll();

        assertThat(service.findFailures(0, 100)).isEmpty();
        assertThat(service.findFailureHistory(0, 100)).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.GEOCODING_ERROR);
            assertThat(failure.status().name()).isEqualTo("RESOLVED");
            assertThat(failure.lastResolvedAt()).isNotNull();
        });
    }

    @Test
    void 좌표계_변환_오류는_운영_실패로_집계한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        when(geocodingService.geocode(anyString())).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.COORDINATE_CONVERSION_ERROR,
                "UTM-K 좌표를 변환하지 못했습니다."
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(report.operationalFailedSourceRowCount()).isOne();
        assertThat(report.rateLimitedSourceRowCount()).isZero();
    }

    @Test
    void 좌표_조회의_일반_오류는_실행을_중단한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        when(geocodingService.geocode(anyString()))
                .thenThrow(new IllegalStateException("좌표 저장소 연결 실패"))
                .thenThrow(new AssertionError("같은 주소를 다시 조회했습니다."));

        assertThatThrownBy(service::mapAll)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("좌표 저장소 연결 실패");

        verify(geocodingService).geocode(anyString());
        assertThat(complexRepository.count()).isZero();
    }

    @Test
    void 뒤쪽_단지에서_중단돼도_앞서_확인한_실패는_기록한다() {
        sourceRepository.saveAll(List.of(
                source(data(123L, "46A", "46.8000", "20.2000", "서울주택도시공사", "20200101")),
                source(data(456L, "59A", "59.9500", "24.1000", "경기주택도시공사", "20200101"))
        ));
        when(geocodingService.geocode(anyString()))
                .thenThrow(new RoadAddressGeocodingException(
                        RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND, "주소 없음"))
                .thenThrow(new IllegalStateException("좌표 저장소 연결 실패"));

        assertThatThrownBy(service::mapAll)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("좌표 저장소 연결 실패");

        assertThat(service.findFailures(0, 100)).singleElement().satisfies(failure ->
                assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.GEOCODING_ERROR));
    }

    @Test
    void 단지번호만_다른_분할_원천은_세대수를_합산한_한_단지와_면적별_주택형으로_정제한다() {
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(30700086L, "31", "31.32", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 80, SPLIT_ADDRESS)),
                source(split(31324765L, "52", "52.74", 3, 80, SPLIT_ADDRESS)),
                source(split(31309830L, "52", "52.74", 3, 80, SPLIT_ADDRESS))
        ));

        var report = service.mapAll();
        var repeated = service.mapAll();

        assertThat(report.createdComplexCount()).isOne();
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(repeated.unchangedComplexCount()).isOne();
        assertThat(repeated.createdHousingTypeCount()).isZero();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getSourceComplexIdentifier()).isEqualTo("30700086:PERMANENT_RENTAL");
            assertThat(complex.getTotalHouseholdCount()).isEqualTo(1386);
        });
        assertThat(housingTypeRepository.findAll()).extracting(HousingType::getName)
                .containsExactlyInAnyOrder("26", "31", "52");
        assertThat(jdbc.sql("SELECT count(DISTINCT housing_complex_id) FROM myhome_complex_links")
                .query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM myhome_complex_links").query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void 따로_정제된_분할_단지는_가장_작은_ID로_합치고_참조와_별칭을_옮긴다() {
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 81, SPLIT_ADDRESS))
        ));
        service.mapAll();
        List<HousingComplex> separated = complexRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(HousingComplex::getId)).toList();
        assertThat(separated).hasSize(2);
        HousingComplex fragment = separated.stream()
                .filter(complex -> complex.getTotalHouseholdCount() == 13).findFirst().orElseThrow();
        HousingType fragmentType = housingTypeRepository.findAllByHousingComplex(fragment).getFirst();
        Announcement announcement = announcementRepository.save(announcement());
        SupplyRow supply = supplyRowRepository.save(supplyRow(announcement, fragment, fragmentType));

        sourceRepository.deleteAll();
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 80, SPLIT_ADDRESS))
        ));
        var report = service.mapAll();

        long representativeId = separated.getFirst().getId();
        long donorId = separated.getLast().getId();
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getId()).isEqualTo(representativeId);
            assertThat(complex.getTotalHouseholdCount()).isEqualTo(1380);
        });
        assertThat(jdbc.sql("SELECT housing_complex_id FROM supply_rows WHERE id = ?").param(supply.getId())
                .query(Long.class).single()).isEqualTo(representativeId);
        assertThat(jdbc.sql("SELECT housing_complex_id FROM housing_types WHERE id = ?").param(fragmentType.getId())
                .query(Long.class).single()).isEqualTo(representativeId);
        assertThat(jdbc.sql("SELECT housing_complex_id FROM housing_complex_aliases WHERE id = ?").param(donorId)
                .query(Long.class).single()).isEqualTo(representativeId);
        assertThat(jdbc.sql("SELECT DISTINCT housing_complex_id FROM myhome_complex_links")
                .query(Long.class).list()).containsExactly(representativeId);
    }

    @Test
    void 분할_단지를_합치면_관리자_기록을_대표로_옮기고_저장_매칭은_재확인_없이_유지한다() {
        jdbc.sql("TRUNCATE announcement_supply_match_decisions, lh_household_complex_links").update();
        List<HousingComplex> separated = separatedSplitComplexes();
        long representativeId = separated.getFirst().getId();
        HousingComplex donor = separated.getLast();
        HousingType donorType = housingTypeRepository.findAllByHousingComplex(donor).getFirst();
        var decision = new com.toadzip.backend.ingest.mapping.domain.AnnouncementSupplyMatchDecision("row-split-1");
        decision.confirm(donor.getId(), donorType.getId(), "source", targetFingerprint(donorType.getId()),
                "https://example.com/evidence", "공고 52형은 조각 단지 주택형", "admin");
        long decisionId = matchDecisions.save(decision).getId();
        jdbc.sql("""
                INSERT INTO lh_household_complex_links (source_key, housing_complex_id, source_fingerprint,
                    target_fingerprint, reason, actor, updated_at, version) VALUES ('lh-split', ?, 's', 't', '확인', 'admin', now(), 0)
                """).param(donor.getId()).update();
        jdbc.sql("""
                INSERT INTO lh_household_target_decisions (housing_complex_id, decision, sources_fingerprint,
                    target_fingerprint, reason, actor, updated_at, version) VALUES (?, 'SKIP', 's', 't', '확인', 'admin', now(), 0)
                """).param(donor.getId()).update();
        jdbc.sql("""
                INSERT INTO housing_complex_reviews (housing_complex_id, outcome, checked_values, evidence_note,
                    actor, reviewed_at) VALUES (?, 'VERIFIED', '{"NAME": "대전둔산3"}', '확인', 'admin', now())
                """).param(donor.getId()).update();
        for (long complexId : List.of(representativeId, donor.getId())) {
            jdbc.sql("""
                    INSERT INTO complex_comparison_links (housing_complex_id, category, source_key, evidence_note,
                        actor, linked_at) VALUES (?, 'LH_LEASE_CATALOG', 'lh-catalog-1', '같은 단지', 'admin', now())
                    """).param(complexId).update();
        }
        jdbc.sql("""
                INSERT INTO complex_comparison_links (housing_complex_id, category, source_key, evidence_note,
                    actor, linked_at) VALUES (?, 'MYHOME_COMPLEX', 'myhome-1', '조각 단지 근거', 'admin', now())
                """).param(donor.getId()).update();

        mergeSeparatedSplitComplexes();

        var moved = matchDecisions.findById(decisionId).orElseThrow();
        HousingType movedType = housingTypeRepository.findById(donorType.getId()).orElseThrow();
        assertThat(moved.getComplexId()).isEqualTo(representativeId);
        assertThat(jdbc.sql("SELECT housing_complex_id FROM housing_types WHERE id = ?").param(movedType.getId())
                .query(Long.class).single()).isEqualTo(representativeId);
        assertThat(moved.getTargetFingerprint()).isEqualTo(targetFingerprint(movedType.getId()));
        for (String table : List.of("lh_household_complex_links", "lh_household_target_decisions",
                "housing_complex_reviews")) {
            assertThat(jdbc.sql("SELECT housing_complex_id FROM " + table).query(Long.class).list())
                    .as(table).containsExactly(representativeId);
        }
        assertThat(jdbc.sql("SELECT housing_complex_id || ':' || source_key FROM complex_comparison_links")
                .query(String.class).list()).containsExactlyInAnyOrder(
                        representativeId + ":lh-catalog-1", representativeId + ":myhome-1");
    }

    @Test
    void 분할_단지마다_LH_세대수_결정이_있으면_통합을_보류한다() {
        jdbc.sql("TRUNCATE lh_household_target_decisions").update();
        List<HousingComplex> separated = separatedSplitComplexes();
        for (HousingComplex complex : separated) {
            jdbc.sql("""
                    INSERT INTO lh_household_target_decisions (housing_complex_id, decision, sources_fingerprint,
                        target_fingerprint, reason, actor, updated_at, version) VALUES (?, 'SKIP', 's', 't', '확인', 'admin', now(), 0)
                    """).param(complex.getId()).update();
        }

        var report = mergeSeparatedSplitComplexes();

        assertThat(report.failedSourceRowCount()).isEqualTo(2);
        assertThat(complexRepository.findAll()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM lh_household_target_decisions").query(Long.class).single())
                .isEqualTo(2);
    }

    private String targetFingerprint(long housingTypeId) {
        return transactions.execute(status ->
                fingerprints.target(housingTypeRepository.findById(housingTypeId).orElseThrow()));
    }

    private List<HousingComplex> separatedSplitComplexes() {
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 81, SPLIT_ADDRESS))
        ));
        service.mapAll();
        return complexRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(HousingComplex::getId)).toList();
    }

    private com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport mergeSeparatedSplitComplexes() {
        sourceRepository.deleteAll();
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 80, SPLIT_ADDRESS))
        ));
        return service.mapAll();
    }

    @Test
    void 도로명주소가_다른_원천은_PNU와_이름이_같아도_따로_정제한다() {
        sourceRepository.saveAll(List.of(
                source(split(30699970L, "26", "26.37", 595, 80, "경기도 성남시 분당구 벌말로 10")),
                source(split(30699971L, "26", "26.37", 645, 80, "경기도 성남시 분당구 판교로 607"))
        ));

        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isEqualTo(2);
        assertThat(complexRepository.findAll()).extracting(HousingComplex::getTotalHouseholdCount)
                .containsExactlyInAnyOrder(595, 645);
    }

    @Test
    void 합친_단지의_원천이_서로_다른_묶음으로_갈라지면_기존_단지를_보존하고_재확인을_남긴다() {
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 80, SPLIT_ADDRESS))
        ));
        service.mapAll();
        sourceRepository.deleteAll();
        sourceRepository.saveAll(List.of(
                source(split(30700086L, "26", "26.37", 1367, 80, SPLIT_ADDRESS)),
                source(split(31260572L, "52", "52.74", 13, 81, SPLIT_ADDRESS))
        ));

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isEqualTo(2);
        assertThat(service.findFailures(0, 10)).allSatisfy(failure ->
                assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE));
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getTotalHouseholdCount()).isEqualTo(1380);
            assertThat(complex.getParkingSpaceCount()).isEqualTo(80);
        });
    }

    private MyHomeComplexSourceSnapshot split(
            Long hsmpSn, String styleName, String exclusiveArea, int householdCount, int parkingCount, String address
    ) {
        return new MyHomeComplexSourceSnapshot(
                hsmpSn, "LH대전충남", "30", "대전광역시", "170", "서구", "대전둔산3", address,
                "3017011300102180007", "19930607", householdCount, "영구임대", styleName,
                new BigDecimal(exclusiveArea), new BigDecimal("12.0000"), "아파트", "개별난방", "복도식",
                "전체동 설치", parkingCount, 6_361_000L, 87_890L, null
        );
    }

    private MyHomeComplexSource source(String styleName, String exclusiveArea, String commonArea) {
        return source(data(
                123L, styleName, exclusiveArea, commonArea, "서울주택도시공사", "20200101"
        ));
    }

    private Announcement announcement() {
        return Announcement.create(
                "announcement-1",
                null,
                null,
                "테스트 공고",
                "원공고",
                "국민임대",
                "신규모집",
                "LH",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1",
                null,
                0L,
                ReceptionPlace.create("마이홈", "인터넷", null, "1600-1004", "https://example.com")
        );
    }

    private SupplyRow supplyRow(
            Announcement announcement,
            HousingComplex complex,
            HousingType housingType
    ) {
        return SupplyRow.create(
                announcement,
                complex,
                housingType,
                "supply-row-1",
                1,
                complex.getName(),
                housingType.getName(),
                complex.getAddress().getPnu(),
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                10
        );
    }

    private MyHomeComplexSource source(MyHomeComplexSourceSnapshot data) {
        MyHomeComplexSource source = MyHomeComplexSource.from(data);
        source.markCollectedAt(COLLECTED_AT);
        return source;
    }

    private MyHomeComplexSourceSnapshot data(
            Long hsmpSn,
            String styleName,
            String exclusiveArea,
            String commonArea,
            String provider,
            String completionDate
    ) {
        return dataWith(
                hsmpSn, styleName, exclusiveArea, commonArea, provider, "테스트 단지",
                "국민임대", completionDate, "지역난방", "아파트", "복도식", "전체동 설치"
        );
    }

    private MyHomeComplexSourceSnapshot dataWith(
            Long hsmpSn,
            String styleName,
            String exclusiveArea,
            String commonArea,
            String provider,
            String complexName,
            String supplyType,
            String completionDate,
            String heatingType,
            String housingType,
            String corridorType,
            String elevatorInstalled
    ) {
        return new MyHomeComplexSourceSnapshot(
                hsmpSn,
                provider,
                "11",
                "서울특별시",
                "110",
                "종로구",
                complexName,
                "서울특별시 종로구 테스트로 1",
                "1111010100100010000",
                completionDate,
                100,
                supplyType,
                styleName,
                new BigDecimal(exclusiveArea),
                new BigDecimal(commonArea),
                housingType,
                heatingType,
                corridorType,
                elevatorInstalled,
                80,
                10_000_000L,
                200_000L,
                20_000_000L
        );
    }

    private MyHomeComplexSourceSnapshot withAddress(
            MyHomeComplexSourceSnapshot original, String roadAddress, String pnu
    ) {
        return new MyHomeComplexSourceSnapshot(
                original.hsmpSn(), original.insttNm(), original.brtcCode(), original.brtcNm(),
                original.signguCode(), original.signguNm(), original.hsmpNm(), roadAddress, pnu,
                original.competDe(), original.hshldCo(), original.suplyTyNm(), original.styleNm(),
                original.suplyPrvuseAr(), original.suplyCmnuseAr(), original.houseTyNm(),
                original.heatMthdDetailNm(), original.buldStleNm(), original.elvtrInstlAtNm(),
                original.parkngCo(), original.bassRentGtn(), original.bassMtRntchrg(),
                original.bassCnvrsGtnLmt()
        );
    }

    private MyHomeComplexSourceSnapshot withPrices(
            MyHomeComplexSourceSnapshot original, Long deposit, Long monthlyRent
    ) {
        return new MyHomeComplexSourceSnapshot(
                original.hsmpSn(), original.insttNm(), original.brtcCode(), original.brtcNm(),
                original.signguCode(), original.signguNm(), original.hsmpNm(), original.rnAdres(), original.pnu(),
                original.competDe(), original.hshldCo(), original.suplyTyNm(), original.styleNm(),
                original.suplyPrvuseAr(), original.suplyCmnuseAr(), original.houseTyNm(),
                original.heatMthdDetailNm(), original.buldStleNm(), original.elvtrInstlAtNm(),
                original.parkngCo(), deposit, monthlyRent, original.bassCnvrsGtnLmt()
        );
    }

}
