package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.domain.MyHomeComplexSourceData;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingFailureRepository;
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

    @BeforeEach
    void setUp() {
        housingTypeRepository.deleteAll();
        complexRepository.deleteAll();
        failureRepository.deleteAll();
        sourceRepository.deleteAll();
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
            assertThat(complex.getSourceComplexIdentifier()).isEqualTo("123");
            assertThat(complex.getProvider()).isEqualTo("서울주택도시공사");
            assertThat(complex.getAddress().getLegalDongCode()).isEqualTo("1111010100");
            assertThat(complex.getAddress().getCityCountyDistrictCode()).isEqualTo("11110");
            assertThat(complex.getAddress().getLatitude()).isNull();
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
            assertThat(complex.getProvider()).isEqualTo("경기주택도시공사");
        });
    }

    @Test
    void 변경된_주택형_원천_스냅샷을_중복_없이_반영한다() {
        sourceRepository.save(source("46A", "46.8000", "20.2000"));
        service.mapAll();
        sourceRepository.deleteAll();
        sourceRepository.save(source("46A", "47.12345", "20.2000"));

        var report = service.mapAll();

        assertThat(report.createdHousingTypeCount()).isOne();
        assertThat(report.deletedHousingTypeCount()).isOne();
        assertThat(housingTypeRepository.findAll()).singleElement().satisfies(type ->
                assertThat(type.getExclusiveArea()).isEqualByComparingTo("47.1235")
        );
    }

    @Test
    void 변환에_실패한_단지는_건너뛰고_원천과_실패_사유를_기록한다() {
        sourceRepository.saveAll(List.of(
                source("46A", "46.8000", "20.2000"),
                source(data(456L, "59A", "59.9500", "24.1000", "부산도시공사", null))
        ));

        var report = service.mapAll();

        assertThat(report.createdComplexCount()).isOne();
        assertThat(report.failedSourceRowCount()).isOne();
        assertThat(complexRepository.findAll())
                .extracting(complex -> complex.getSourceComplexIdentifier())
                .containsExactly("123");
        assertThat(service.findFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.sourceComplexIdentifier()).isEqualTo("456");
            assertThat(failure.reason()).isEqualTo(MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE);
            assertThat(failure.detail()).contains("준공일");
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
                null
        ));
        sourceRepository.save(source);
        service.mapAll();
        source.replaceWith(data(
                123L,
                "46A",
                "46.8000",
                "20.2000",
                "서울주택도시공사",
                "20200101"
        ));
        sourceRepository.save(source);

        var report = service.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(service.findFailures()).isEmpty();
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
        return new MyHomeComplexSourceData(
                hsmpSn,
                provider,
                "11",
                "서울특별시",
                "110",
                "종로구",
                "테스트 단지",
                "서울특별시 종로구 테스트로 1",
                "1111010100100010000",
                completionDate,
                100,
                "국민임대",
                styleName,
                new BigDecimal(exclusiveArea),
                new BigDecimal(commonArea),
                "아파트",
                "지역난방",
                "복도식",
                "전체동 설치",
                80,
                10_000_000L,
                200_000L,
                20_000_000L
        );
    }
}
