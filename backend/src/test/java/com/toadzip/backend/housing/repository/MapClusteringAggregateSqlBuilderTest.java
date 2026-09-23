package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapClusteringAggregateSqlBuilderTest {

    @Test
    void 지역_코드와_그룹_key는_binding하고_viewport_없이_고유_단지를_집계한다() {
        MapClusteringAggregateSqlBuilder builder = builder(Map.of(
                "12210", Set.of("12210", "29110")
        ));
        MapClusteringRegionAssignment assignment = new MapClusteringRegionAssignment(
                "12210",
                new MapClusteringGroupKey("BASIC_REGION:12110")
        );

        MapClusteringAggregateSqlQuery query = builder.build(filters(), List.of(assignment));

        assertAll(
                () -> assertTrue(query.sql().contains("COUNT(DISTINCT housing_complex.id)")),
                () -> assertTrue(query.sql().contains("(:storedRegionCode0, :groupKey0)")),
                () -> assertTrue(query.sql().contains("(:storedRegionCode1, :groupKey1)")),
                () -> assertFalse(query.sql().contains("12210")),
                () -> assertFalse(query.sql().contains("BASIC_REGION:12110")),
                () -> assertFalse(query.parameters().containsKey("southWestLat")),
                () -> assertEquals("12210", query.parameters().get("storedRegionCode0")),
                () -> assertEquals("BASIC_REGION:12110", query.parameters().get("groupKey0")),
                () -> assertEquals("29110", query.parameters().get("storedRegionCode1")),
                () -> assertTrue(query.parameters().containsKey("rentalTypeValues"))
        );
    }

    private static MapClusteringAggregateSqlBuilder builder(Map<String, Set<String>> equivalentCodes) {
        return new MapClusteringAggregateSqlBuilder(
                new HousingComplexFilterPredicateBuilder(),
                new StubRegionCodeResolver(equivalentCodes)
        );
    }

    private static HousingComplexFilterCondition filters() {
        return new HousingComplexFilterCondition(
                null,
                null,
                Set.of(),
                Set.of(RentalType.HAPPY_HOUSING),
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

    private static final class StubRegionCodeResolver implements RegionCodeResolver {

        private final Map<String, Set<String>> equivalentCodes;

        private StubRegionCodeResolver(Map<String, Set<String>> equivalentCodes) {
            this.equivalentCodes = equivalentCodes;
        }

        @Override
        public Optional<String> resolve(String provinceCode, String cityCountyDistrictCode) {
            return Optional.empty();
        }

        @Override
        public Optional<Set<String>> equivalentCodes(String regionCode) {
            return Optional.ofNullable(equivalentCodes.get(regionCode));
        }
    }
}
