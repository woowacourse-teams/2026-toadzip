package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.domain.MyHomeComplexSourceData;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.dto.GeocodedRoadAddress;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingFailureRepository;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingCandidateRepository;
import com.toadzip.backend.ingest.repository.MyHomeComplexSourceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    @Autowired
    private MyHomeComplexMappingService service;

    @Autowired
    private MyHomeComplexSourceRepository sourceRepository;

    @Autowired
    private MyHomeComplexMappingFailureRepository failureRepository;

    @Autowired
    private HousingComplexRepository complexRepository;

    @Autowired
    private HousingTypeRepository housingTypeRepository;

    @Autowired
    private MyHomeComplexMappingCandidateRepository candidateRepository;

    @MockitoBean
    private RoadAddressGeocodingService geocodingService;

    @BeforeEach
    void setUp() {
        housingTypeRepository.deleteAll();
        complexRepository.deleteAll();
        candidateRepository.deleteAll();
        failureRepository.deleteAll();
        sourceRepository.deleteAll();
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedRoadAddress(
                "서울특별시 종로구 테스트로 1",
                new BigDecimal("37.56620552"),
                new BigDecimal("126.97770648")
        ));
    }

    @Test
    void 좌표_조회_전에는_후보만_저장하고_최종_단지는_저장하지_않는다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source("59A", "59.9500", "24.1000")
        ));

        var report = service.prepare();

        assertThat(report.stagedCandidateCount()).isOne();
        assertThat(candidateRepository.count()).isOne();
        assertThat(complexRepository.count()).isZero();
        assertThat(housingTypeRepository.count()).isZero();
        verify(geocodingService, never()).geocode(anyString());
    }

    @Test
    void 준비된_후보를_좌표와_함께_단지와_주택형으로_승격한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source("59A", "59.9500", "24.1000")
        ));
        service.prepare();

        var report = service.mapNext(100);

        assertThat(report.createdComplexCount()).isOne();
        assertThat(report.createdHousingTypeCount()).isEqualTo(2);
        assertThat(complexRepository.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getAddress().getLatitude()).isEqualByComparingTo("37.566206");
            assertThat(complex.getAddress().getLongitude()).isEqualByComparingTo("126.977706");
        });
    }

    @Test
    void 같은_주소의_여러_단지는_저장된_좌표를_재사용한다() {
        sourceRepository.saveAll(List.of(
                source(data(123L, "46A", "46.8000", "20.2000", "서울주택도시공사", "20200101")),
                source(data(456L, "59A", "59.9500", "24.1000", "경기주택도시공사", "20200101"))
        ));
        service.prepare();

        service.mapNext(1);
        service.mapNext(1);

        assertThat(complexRepository.count()).isEqualTo(2);
        verify(geocodingService, times(1)).geocode(anyString());
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
        verify(geocodingService, times(1)).geocode(anyString());
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
        sourceRepository.save(source("46A", "47.12345", "20.2000"));

        var report = service.mapAll();

        assertThat(report.updatedHousingTypeCount()).isOne();
        assertThat(report.createdHousingTypeCount()).isZero();
        assertThat(report.deletedHousingTypeCount()).isZero();
        assertThat(housingTypeRepository.findAll()).singleElement().satisfies(type -> {
            assertThat(type.getId()).isEqualTo(housingTypeId);
            assertThat(type.getExclusiveArea()).isEqualByComparingTo("47.1235");
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
        assertThat(service.findFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.sourceComplexIdentifier()).isEqualTo("456:NATIONAL_RENTAL");
            assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE);
            assertThat(failure.detail()).contains("단지명");
            assertThat(failure.sourceKey()).isNotBlank();
        });
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
        assertThat(service.findFailures()).isEmpty();
    }

    @Test
    void 매입임대는_후보와_실패_대상에서_제외한다() {
        sourceRepository.save(source(dataWith(
                123L, "46A", "46.8000", "20.2000", "LH서울", "매입임대 주택",
                "매입임대", null, null, null, null, null
        )));

        var report = service.prepare();

        assertThat(report.stagedCandidateCount()).isZero();
        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(candidateRepository.count()).isZero();
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
        assertThat(complexRepository.findAll()).isEmpty();
        assertThat(service.findFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.GEOCODING_ERROR);
            assertThat(failure.detail()).contains("ADDRESS_NOT_FOUND");
        });
    }

    private MyHomeComplexSource source(String styleName, String exclusiveArea, String commonArea) {
        return source(data(
                123L, styleName, exclusiveArea, commonArea, "서울주택도시공사", "20200101"
        ));
    }

    private MyHomeComplexSource source(MyHomeComplexSourceData data) {
        MyHomeComplexSource source = MyHomeComplexSource.from(data);
        source.markCollectedAt(COLLECTED_AT);
        return source;
    }

    private MyHomeComplexSourceData data(
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

    private MyHomeComplexSourceData dataWith(
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
        return new MyHomeComplexSourceData(
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
}
