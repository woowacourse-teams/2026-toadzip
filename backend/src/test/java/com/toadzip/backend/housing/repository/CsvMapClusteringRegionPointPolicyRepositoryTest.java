package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionCandidate;
import com.toadzip.backend.housing.domain.MapClusteringRegionGroup;
import com.toadzip.backend.housing.domain.MapClusteringRegionMembership;
import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class CsvMapClusteringRegionPointPolicyRepositoryTest {

    private static final String HEADER =
            "policyVersion,regionDatasetVersion,groupKey,latitude,longitude";
    private static final MapClusteringGroupKey SERVICE_ZONE_KEY =
            new MapClusteringGroupKey("SERVICE_ZONE:CAPITAL");
    private static final MapClusteringGroupKey METROPOLITAN_KEY =
            new MapClusteringGroupKey("METROPOLITAN:41");
    private static final MapClusteringGroupKey BASIC_REGION_KEY =
            new MapClusteringGroupKey("BASIC_REGION:41130");

    @Test
    void CSV의_고정_대표점으로_viewport_안과_경계의_지역만_고른다() {
        MapClusteringRegionPolicy regionPolicy = regionPolicy();
        CsvMapClusteringRegionPointPolicyRepository repository = repository(validCsv(), regionPolicy);
        MapBounds bounds = MapBounds.of(
                decimal("37.0"), decimal("127.0"), decimal("37.5"), decimal("127.5")
        );

        List<MapClusteringRegionCandidate> candidates = repository.current().candidates(
                regionPolicy.groupsAt(MapClusteringStage.BASIC_REGION),
                bounds
        );

        assertEquals(1, candidates.size());
        assertEquals(BASIC_REGION_KEY, candidates.getFirst().group().key());
        assertEquals(decimal("37.5"), candidates.getFirst().representativePoint().latitude());
    }

    @Test
    void 지역_정책과_version이_다르면_거부한다() {
        String mismatched = validCsv().replace("2026-09-02-v1", "other-version");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(mismatched, regionPolicy())
        );

        assertTrue(exception.getMessage().contains("policyVersion"));
    }

    @Test
    void 지역_정책의_group_대표점이_누락되면_거부한다() {
        String incomplete = validCsv().replace(
                "2026-09-02-v1,2026-07-01,BASIC_REGION:41130,37.5,127.5\n",
                ""
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(incomplete, regionPolicy())
        );

        assertTrue(exception.getMessage().contains("missing"));
        assertTrue(exception.getMessage().contains("BASIC_REGION:41130"));
    }

    @Test
    void 위도와_경도_범위를_벗어난_대표점을_거부한다() {
        String invalid = validCsv().replace(",37.5,127.5", ",91.0,127.5");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(invalid, regionPolicy())
        );

        assertTrue(exception.getMessage().contains("line 4"));
        assertTrue(exception.getMessage().contains("latitude"));
    }

    @Test
    void 배포용_CSV는_지역_정책의_257개_group을_모두_포함한다() {
        CsvMapClusteringZoomPolicyRepository zoomRepository =
                new CsvMapClusteringZoomPolicyRepository(classpath("map-clustering/stage-transitions.csv"));
        CsvMapClusteringRegionPolicyRepository regionRepository = new CsvMapClusteringRegionPolicyRepository(
                classpath("map-clustering/groups.csv"),
                classpath("map-clustering/memberships.csv"),
                classpath("region/regions.csv"),
                zoomRepository
        );
        CsvMapClusteringRegionPointPolicyRepository pointRepository =
                new CsvMapClusteringRegionPointPolicyRepository(
                        classpath("map-clustering/representative-points.csv"), regionRepository
                );

        List<MapClusteringRegionCandidate> candidates = pointRepository.current().candidates(
                regionRepository.current().groups(),
                MapBounds.of(decimal("30"), decimal("120"), decimal("45"), decimal("135"))
        );

        assertEquals(257, candidates.size());
    }

    private static CsvMapClusteringRegionPointPolicyRepository repository(
            String csv,
            MapClusteringRegionPolicy regionPolicy
    ) {
        return new CsvMapClusteringRegionPointPolicyRepository(
                new ByteArrayResource(csv.getBytes()),
                () -> regionPolicy
        );
    }

    private static MapClusteringRegionPolicy regionPolicy() {
        List<MapClusteringRegionGroup> groups = List.of(
                group(SERVICE_ZONE_KEY, MapClusteringStage.SERVICE_ZONE, Optional.empty()),
                group(METROPOLITAN_KEY, MapClusteringStage.METROPOLITAN, Optional.of(SERVICE_ZONE_KEY)),
                group(BASIC_REGION_KEY, MapClusteringStage.BASIC_REGION, Optional.of(METROPOLITAN_KEY))
        );
        return MapClusteringRegionPolicy.of(
                version(), groups, List.of(new MapClusteringRegionMembership("41130", BASIC_REGION_KEY)),
                Set.of("41130")
        );
    }

    private static MapClusteringRegionGroup group(
            MapClusteringGroupKey key,
            MapClusteringStage stage,
            Optional<MapClusteringGroupKey> parentKey
    ) {
        return new MapClusteringRegionGroup(key, key.value(), stage, parentKey);
    }

    private static MapClusteringPolicyVersion version() {
        return new MapClusteringPolicyVersion("2026-09-02-v1", "2026-07-01");
    }

    private static String validCsv() {
        return HEADER + "\n"
                + "2026-09-02-v1,2026-07-01,SERVICE_ZONE:CAPITAL,36.5,127.0\n"
                + "2026-09-02-v1,2026-07-01,METROPOLITAN:41,37.0,127.0\n"
                + "2026-09-02-v1,2026-07-01,BASIC_REGION:41130,37.5,127.5\n";
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static ClassPathResource classpath(String path) {
        return new ClassPathResource(path);
    }
}
