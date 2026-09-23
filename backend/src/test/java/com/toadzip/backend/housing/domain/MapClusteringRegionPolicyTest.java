package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapClusteringRegionPolicyTest {

    private static final MapClusteringGroupKey SERVICE_ZONE_KEY = key("SERVICE_ZONE:CAPITAL");
    private static final MapClusteringGroupKey METROPOLITAN_KEY = key("METROPOLITAN:41");
    private static final MapClusteringGroupKey BASIC_REGION_KEY = key("BASIC_REGION:41130");

    @Test
    void canonical_지역_코드를_각_클러스터링_단계의_그룹으로_해석한다() {
        MapClusteringRegionPolicy policy = policy();

        assertEquals(SERVICE_ZONE_KEY, policy.groupOf("41131", MapClusteringStage.SERVICE_ZONE).orElseThrow().key());
        assertEquals(METROPOLITAN_KEY, policy.groupOf("41131", MapClusteringStage.METROPOLITAN).orElseThrow().key());
        assertEquals(BASIC_REGION_KEY, policy.groupOf("41131", MapClusteringStage.BASIC_REGION).orElseThrow().key());
    }

    @Test
    void 일반구는_상위_시와_같은_기초_지역_그룹에_속한다() {
        MapClusteringRegionPolicy policy = policy();

        assertEquals(
                policy.groupOf("41130", MapClusteringStage.BASIC_REGION),
                policy.groupOf("41131", MapClusteringStage.BASIC_REGION)
        );
    }

    @Test
    void 개별_마커_단계와_알_수_없는_지역에는_지역_그룹이_없다() {
        MapClusteringRegionPolicy policy = policy();

        assertEquals(Optional.empty(), policy.groupOf("41131", MapClusteringStage.INDIVIDUAL));
        assertEquals(Optional.empty(), policy.groupOf("99999", MapClusteringStage.BASIC_REGION));
        assertEquals(List.of(), policy.groupsAt(MapClusteringStage.INDIVIDUAL));
    }

    @Test
    void canonical_지역_코드와_단계별_그룹의_집계_소속을_제공한다() {
        MapClusteringRegionPolicy policy = policy();

        assertEquals(
                List.of(
                        assignment("41130", BASIC_REGION_KEY),
                        assignment("41131", BASIC_REGION_KEY)
                ),
                policy.assignmentsAt(MapClusteringStage.BASIC_REGION)
        );
        assertEquals(
                List.of(
                        assignment("41130", METROPOLITAN_KEY),
                        assignment("41131", METROPOLITAN_KEY)
                ),
                policy.assignmentsAt(MapClusteringStage.METROPOLITAN)
        );
    }

    @Test
    void 개별_마커_단계에는_지역_집계_소속이_없다() {
        assertEquals(List.of(), policy().assignmentsAt(MapClusteringStage.INDIVIDUAL));
    }

    @Test
    void 모든_canonical_지역_코드가_membership에_있어야_한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(List.of(membership("41130")), Set.of("41130", "41131"))
        );
    }

    @Test
    void group_key가_중복되면_정책을_생성하지_않는다() {
        List<MapClusteringRegionGroup> duplicated = List.of(
                group(SERVICE_ZONE_KEY, "수도권", MapClusteringStage.SERVICE_ZONE, Optional.empty()),
                group(SERVICE_ZONE_KEY, "중복", MapClusteringStage.SERVICE_ZONE, Optional.empty())
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringRegionPolicy.of(version(), duplicated, List.of(), Set.of())
        );
    }

    @Test
    void 바로_위_단계가_아닌_parent를_거부한다() {
        List<MapClusteringRegionGroup> invalidGroups = List.of(
                group(SERVICE_ZONE_KEY, "수도권", MapClusteringStage.SERVICE_ZONE, Optional.empty()),
                group(BASIC_REGION_KEY, "성남시", MapClusteringStage.BASIC_REGION, Optional.of(SERVICE_ZONE_KEY))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringRegionPolicy.of(version(), invalidGroups, List.of(), Set.of())
        );
    }

    @Test
    void 서비스_권역에_parent가_있으면_거부한다() {
        List<MapClusteringRegionGroup> invalidGroups = List.of(
                group(SERVICE_ZONE_KEY, "수도권", MapClusteringStage.SERVICE_ZONE, Optional.of(SERVICE_ZONE_KEY))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringRegionPolicy.of(version(), invalidGroups, List.of(), Set.of())
        );
    }

    @Test
    void 하위_그룹이_없는_상위_그룹을_거부한다() {
        List<MapClusteringRegionGroup> incompleteGroups = List.of(
                group(SERVICE_ZONE_KEY, "수도권", MapClusteringStage.SERVICE_ZONE, Optional.empty()),
                group(METROPOLITAN_KEY, "경기", MapClusteringStage.METROPOLITAN, Optional.of(SERVICE_ZONE_KEY))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringRegionPolicy.of(version(), incompleteGroups, List.of(), Set.of())
        );
    }

    @Test
    void membership은_기초_지역_그룹만_가리킬_수_있다() {
        MapClusteringRegionMembership invalidMembership = new MapClusteringRegionMembership(
                "41130",
                METROPOLITAN_KEY
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> policy(List.of(invalidMembership), Set.of("41130"))
        );
    }

    @Test
    void 모든_기초_지역_그룹에_membership이_있어야_한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(List.of(), Set.of())
        );
    }

    @Test
    void canonical_지역_코드의_membership이_중복되면_거부한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(List.of(membership("41130"), membership("41130")), Set.of("41130"))
        );
    }

    @Test
    void 단계와_형식이_맞지_않는_group_key를_거부한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> group(SERVICE_ZONE_KEY, "경기", MapClusteringStage.METROPOLITAN, Optional.empty())
        );
    }

    private static MapClusteringRegionPolicy policy() {
        return policy(
                List.of(membership("41130"), membership("41131")),
                Set.of("41130", "41131")
        );
    }

    private static MapClusteringRegionPolicy policy(
            List<MapClusteringRegionMembership> memberships,
            Set<String> canonicalRegionCodes
    ) {
        return MapClusteringRegionPolicy.of(version(), groups(), memberships, canonicalRegionCodes);
    }

    private static List<MapClusteringRegionGroup> groups() {
        return List.of(
                group(SERVICE_ZONE_KEY, "수도권", MapClusteringStage.SERVICE_ZONE, Optional.empty()),
                group(METROPOLITAN_KEY, "경기", MapClusteringStage.METROPOLITAN, Optional.of(SERVICE_ZONE_KEY)),
                group(BASIC_REGION_KEY, "성남시", MapClusteringStage.BASIC_REGION, Optional.of(METROPOLITAN_KEY))
        );
    }

    private static MapClusteringRegionGroup group(
            MapClusteringGroupKey key,
            String label,
            MapClusteringStage stage,
            Optional<MapClusteringGroupKey> parentKey
    ) {
        return new MapClusteringRegionGroup(key, label, stage, parentKey);
    }

    private static MapClusteringRegionMembership membership(String canonicalRegionCode) {
        return new MapClusteringRegionMembership(canonicalRegionCode, BASIC_REGION_KEY);
    }

    private static MapClusteringRegionAssignment assignment(
            String canonicalRegionCode,
            MapClusteringGroupKey groupKey
    ) {
        return new MapClusteringRegionAssignment(canonicalRegionCode, groupKey);
    }

    private static MapClusteringGroupKey key(String value) {
        return new MapClusteringGroupKey(value);
    }

    private static MapClusteringPolicyVersion version() {
        return new MapClusteringPolicyVersion("2026-09-02-v1", "2026-07-01");
    }
}
