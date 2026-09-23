package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.CsvRegionCodeResolver;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        MapClusteringAggregateQueryRepository.class,
        MapClusteringAggregateSqlBuilder.class,
        HousingComplexFilterPredicateBuilder.class,
        CsvRegionCodeResolver.class
})
class MapClusteringAggregateQueryRepositoryTest {

    private static final MapClusteringGroupKey SEONGNAM_GROUP_KEY =
            new MapClusteringGroupKey("BASIC_REGION:41130");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MapClusteringAggregateQueryRepository repository;

    @Test
    void 서로_다른_일반구의_단지를_상위_시_지역의_고유_단지로_집계한다() {
        persistComplex("수정구 단지", "41131", "37.450000", "127.140000", "행복주택");
        persistComplex("분당구 단지", "41135", "35.000000", "127.110000", "행복주택");
        entityManager.flush();

        List<MapClusteringRegionCountRow> counts = repository.findCounts(filters(), assignments());

        assertEquals(List.of(new MapClusteringRegionCountRow(SEONGNAM_GROUP_KEY, 2L)), counts);
    }

    @Test
    void 지역과_임대_유형_필터를_집계_전에_적용한다() {
        persistComplex("조건 일치 단지", "41131", "37.450000", "127.140000", "행복주택");
        persistComplex("지역 불일치 단지", "41135", "37.350000", "127.110000", "행복주택");
        persistComplex("임대 유형 불일치 단지", "41131", "37.460000", "127.150000", "국민임대");
        entityManager.flush();

        List<MapClusteringRegionCountRow> counts = repository.findCounts(
                filters(Set.of("41131"), Set.of(RentalType.HAPPY_HOUSING)),
                assignments()
        );

        assertEquals(List.of(new MapClusteringRegionCountRow(SEONGNAM_GROUP_KEY, 1L)), counts);
    }

    @Test
    void legacy_지역_코드로_저장된_단지도_canonical_지역에_집계한다() {
        persistComplex("구 광주 동구 단지", "29110", "35.150000", "126.920000", "행복주택");
        entityManager.flush();
        MapClusteringGroupKey groupKey = new MapClusteringGroupKey("BASIC_REGION:12210");
        List<MapClusteringRegionAssignment> assignments = List.of(
                new MapClusteringRegionAssignment("12210", groupKey)
        );

        List<MapClusteringRegionCountRow> counts = repository.findCounts(filters(), assignments);

        assertEquals(List.of(new MapClusteringRegionCountRow(groupKey, 1L)), counts);
    }

    private HousingComplex persistComplex(
            String name,
            String cityCountyDistrictCode,
            String latitude,
            String longitude,
            String supplyType
    ) {
        HousingComplex complex = HousingComplex.create(
                name,
                "source-" + name,
                supplyType,
                address(cityCountyDistrictCode, latitude, longitude),
                100,
                "LH",
                LocalDate.of(2020, 1, 1),
                null,
                null,
                null,
                true,
                50,
                null,
                null
        );
        entityManager.persist(complex);
        return complex;
    }

    private Address address(String regionCode, String latitude, String longitude) {
        return Address.create(
                "경기도 성남시 두꺼비로 1",
                "4113110100100010000",
                "4113110100",
                regionCode.substring(0, 2),
                regionCode,
                new BigDecimal(latitude),
                new BigDecimal(longitude)
        );
    }

    private List<MapClusteringRegionAssignment> assignments() {
        return List.of(
                new MapClusteringRegionAssignment("41131", SEONGNAM_GROUP_KEY),
                new MapClusteringRegionAssignment("41135", SEONGNAM_GROUP_KEY)
        );
    }

    private HousingComplexFilterCondition filters() {
        return filters(Set.of(), Set.of());
    }

    private HousingComplexFilterCondition filters(
            Set<String> cityCountyDistrictCodes,
            Set<RentalType> rentalTypes
    ) {
        return new HousingComplexFilterCondition(
                null,
                null,
                cityCountyDistrictCodes,
                rentalTypes,
                Set.of(),
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 9, 3)
        );
    }
}
