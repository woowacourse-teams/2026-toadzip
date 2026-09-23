package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapClusteringRegionCodeMappingsTest {

    @Test
    void canonical_지역_소속을_DB에_저장될_수_있는_legacy_코드까지_확장한다() {
        MapClusteringGroupKey groupKey = new MapClusteringGroupKey("BASIC_REGION:12110");
        List<MapClusteringRegionAssignment> assignments = List.of(
                new MapClusteringRegionAssignment("12210", groupKey)
        );
        RegionCodeResolver resolver = resolver(Map.of("12210", Set.of("12210", "29110")));

        MapClusteringRegionCodeMappings mappings = MapClusteringRegionCodeMappings.from(assignments, resolver);

        assertEquals(
                List.of(
                        new MapClusteringRegionCodeMapping("12210", groupKey),
                        new MapClusteringRegionCodeMapping("29110", groupKey)
                ),
                mappings.values()
        );
    }

    @Test
    void 하나의_저장_코드가_여러_그룹에_속하면_거부한다() {
        List<MapClusteringRegionAssignment> assignments = List.of(
                assignment("11110", "BASIC_REGION:11110"),
                assignment("11140", "BASIC_REGION:11140")
        );
        RegionCodeResolver resolver = resolver(Map.of(
                "11110", Set.of("11110", "99999"),
                "11140", Set.of("11140", "99999")
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringRegionCodeMappings.from(assignments, resolver)
        );
    }

    @Test
    void canonical_코드의_동등_코드를_해석할_수_없으면_거부한다() {
        List<MapClusteringRegionAssignment> assignments = List.of(
                assignment("11110", "BASIC_REGION:11110")
        );

        assertThrows(
                IllegalStateException.class,
                () -> MapClusteringRegionCodeMappings.from(assignments, resolver(Map.of()))
        );
    }

    private static MapClusteringRegionAssignment assignment(String regionCode, String groupKey) {
        return new MapClusteringRegionAssignment(regionCode, new MapClusteringGroupKey(groupKey));
    }

    private static RegionCodeResolver resolver(Map<String, Set<String>> equivalentCodes) {
        return new StubRegionCodeResolver(equivalentCodes);
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
