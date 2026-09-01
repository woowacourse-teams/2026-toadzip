package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.LhCatalogSource;
import com.toadzip.backend.ingest.domain.LhCatalogSourceData;
import com.toadzip.backend.ingest.repository.LhCatalogSourceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LhHousingTypeHouseholdEnrichmentServiceTest {

    @Autowired
    private LhHousingTypeHouseholdEnrichmentService service;

    @Autowired
    private LhCatalogSourceRepository sourceRepository;

    @Autowired
    private HousingComplexRepository complexRepository;

    @Autowired
    private HousingTypeRepository housingTypeRepository;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 마이홈_단지의_주택형별_세대수만_LH_카탈로그로_보강한다() {
        HousingComplex complex = saveComplex("동삼2", "NATIONAL_RENTAL", 120);
        HousingType small = saveType(complex, "46A", "46.8000");
        HousingType large = saveType(complex, "59B", "59.8000");
        saveCatalog(0, "국민임대", "46.8", 120, 70);
        saveCatalog(1, "국민임대", "59.8", 120, 50);

        var report = service.enrichAll();

        assertThat(report.sourceComplexCount()).isOne();
        assertThat(report.matchedComplexCount()).isOne();
        assertThat(report.updatedHousingTypeCount()).isEqualTo(2);
        assertThat(housingTypeRepository.findById(small.getId()).orElseThrow().getTotalHouseholdCount())
                .isEqualTo(70);
        assertThat(housingTypeRepository.findById(large.getId()).orElseThrow().getTotalHouseholdCount())
                .isEqualTo(50);
        assertThat(complexRepository.findById(complex.getId()).orElseThrow()).satisfies(stored -> {
            assertThat(stored.getName()).isEqualTo("동삼2");
            assertThat(stored.getSupplyType()).isEqualTo("NATIONAL_RENTAL");
            assertThat(stored.getTotalHouseholdCount()).isEqualTo(120);
            assertThat(stored.getSourceComplexIdentifier()).isEqualTo("100:NATIONAL_RENTAL");
        });
    }

    @Test
    void 일반_공공임대도_CSV_없이_마이홈의_세부_공급유형을_유지하며_보강한다() {
        HousingComplex complex = saveComplex("공공임대단지", "PUBLIC_RENTAL_10Y", 100);
        HousingType type = saveType(complex, "46A", "46.8000");
        saveCatalog(0, "공공임대", "46.8", 100, 100, "공공임대단지");

        var report = service.enrichAll();

        assertThat(report.updatedHousingTypeCount()).isOne();
        assertThat(housingTypeRepository.findById(type.getId()).orElseThrow().getTotalHouseholdCount())
                .isEqualTo(100);
        assertThat(complexRepository.findById(complex.getId()).orElseThrow().getSupplyType())
                .isEqualTo("PUBLIC_RENTAL_10Y");
    }

    @Test
    void LH와_마이홈의_단지명이_부분적으로_같아도_주택형_세대수를_보강한다() {
        HousingComplex complex = saveComplex("강릉송정주공아파트", "NATIONAL_RENTAL", 623);
        HousingType type = saveType(complex, "46A", "46.8000");
        saveCatalog(0, "국민임대", "46.8", 623, 100, "강릉송정");

        var report = service.enrichAll();

        assertThat(report.matchedComplexCount()).isOne();
        assertThat(housingTypeRepository.findById(type.getId()).orElseThrow().getTotalHouseholdCount())
                .isEqualTo(100);
    }

    @Test
    void 같은_단지_후보가_여러_개면_어느_주택형도_보강하지_않는다() {
        HousingComplex first = saveComplex("동삼2", "NATIONAL_RENTAL", 120);
        HousingComplex second = saveComplex("동삼2", "NATIONAL_RENTAL", 120);
        HousingType firstType = saveType(first, "46A", "46.8000");
        HousingType secondType = saveType(second, "46A", "46.8000");
        saveCatalog(0, "국민임대", "46.8", 120, 120);

        var report = service.enrichAll();

        assertThat(report.failedSourceComplexCount()).isOne();
        assertThat(housingTypeRepository.findAllById(java.util.List.of(firstType.getId(), secondType.getId())))
                .allSatisfy(type -> assertThat(type.getTotalHouseholdCount()).isNull());
    }

    @Test
    void 여러_LH_원천_그룹이_같은_단지에_매칭되면_어느_값도_보강하지_않는다() {
        HousingComplex complex = saveComplex("동삼2주공아파트", "NATIONAL_RENTAL", 120);
        HousingType type = saveType(complex, "46A", "46.8000");
        saveCatalog(0, "국민임대", "46.8", 120, 70, "동삼2");
        saveCatalog(1, "국민임대", "46.8", 120, 80, "동삼2단지");

        var report = service.enrichAll();

        assertThat(report.sourceComplexCount()).isEqualTo(2);
        assertThat(report.matchedComplexCount()).isZero();
        assertThat(report.updatedHousingTypeCount()).isZero();
        assertThat(report.failedSourceComplexCount()).isEqualTo(2);
        assertThat(housingTypeRepository.findById(type.getId()).orElseThrow().getTotalHouseholdCount())
                .isNull();
    }

    @Test
    void 일치하지_않는_LH_주택형은_건너뛰고_일치하는_주택형만_보강한다() {
        HousingComplex complex = saveComplex("동삼2", "NATIONAL_RENTAL", 120);
        HousingType matched = saveType(complex, "46A", "46.8000");
        saveCatalog(0, "국민임대", "46.8", 120, 70);
        saveCatalog(1, "국민임대", "59.8", 120, 50);

        var report = service.enrichAll();

        assertThat(report.updatedHousingTypeCount()).isOne();
        assertThat(report.unmatchedHousingTypeCount()).isOne();
        assertThat(housingTypeRepository.findById(matched.getId()).orElseThrow().getTotalHouseholdCount())
                .isEqualTo(70);
    }

    @Test
    void 같은_LH_값으로_다시_보강하면_변경하지_않는다() {
        HousingComplex complex = saveComplex("동삼2", "NATIONAL_RENTAL", 120);
        saveType(complex, "46A", "46.8000");
        saveCatalog(0, "국민임대", "46.8", 120, 120);
        service.enrichAll();

        var report = service.enrichAll();

        assertThat(report.updatedHousingTypeCount()).isZero();
        assertThat(report.unchangedHousingTypeCount()).isOne();
    }

    private HousingComplex saveComplex(String name, String supplyType, int totalHouseholdCount) {
        String identifier = (100 + complexRepository.count()) + ":" + supplyType;
        Address address = Address.create(
                "서울특별시 종로구 테스트로 1",
                "1111010100100010000",
                "1111010100",
                "11",
                "11110",
                new BigDecimal("37.566206"),
                new BigDecimal("126.977706")
        );
        return complexRepository.save(HousingComplex.createFromMyHome(
                name, identifier, supplyType, address, totalHouseholdCount, "LH", null,
                "DISTRICT", "APARTMENT", "CORRIDOR", true, 80
        ));
    }

    private HousingType saveType(HousingComplex complex, String name, String area) {
        return housingTypeRepository.save(HousingType.createFromMyHome(
                complex,
                complex.getSourceComplexIdentifier() + ":" + name,
                name,
                new BigDecimal(area),
                null
        ));
    }

    private void saveCatalog(
            int order,
            String supplyType,
            String area,
            int complexCount,
            int typeCount
    ) {
        saveCatalog(order, supplyType, area, complexCount, typeCount, "동삼2");
    }

    private void saveCatalog(
            int order,
            String supplyType,
            String area,
            int complexCount,
            int typeCount,
            String complexName
    ) {
        LhCatalogSource source = new LhCatalogSource(order, new LhCatalogSourceData(
                "서울", supplyType, complexName, String.valueOf(complexCount), area,
                String.valueOf(typeCount), null, null
        ));
        source.markCollectedAt(Instant.parse("2026-09-01T00:00:00Z"));
        sourceRepository.save(source);
    }

    private void cleanUp() {
        sourceRepository.deleteAll();
        housingTypeRepository.deleteAll();
        complexRepository.deleteAll();
    }
}
