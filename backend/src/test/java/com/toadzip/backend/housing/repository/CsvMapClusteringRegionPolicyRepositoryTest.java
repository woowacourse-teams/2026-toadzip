package com.toadzip.backend.housing.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.domain.MapClusteringTransition;
import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class CsvMapClusteringRegionPolicyRepositoryTest {

    private static final String GROUP_HEADER = "policyVersion,regionDatasetVersion,stage,groupKey,groupLabel,"
            + "parentGroupKey";
    private static final String MEMBERSHIP_HEADER = "policyVersion,regionDatasetVersion,canonicalRegionCode,"
            + "basicRegionGroupKey";
    private static final String REGION_HEADER = "regionCode,sido,sigungu,name";
    private static final Map<String, String> SERVICE_ZONE_BY_METROPOLITAN_CODE = Map.ofEntries(
            Map.entry("11", "SEOUL"),
            Map.entry("12", "GWANGJU_JEONNAM"),
            Map.entry("26", "BUSAN_ULSAN_GYEONGNAM"),
            Map.entry("27", "DAEGU_GYEONGBUK"),
            Map.entry("28", "INCHEON"),
            Map.entry("30", "DAEJEON_SEJONG_CHUNGNAM"),
            Map.entry("31", "BUSAN_ULSAN_GYEONGNAM"),
            Map.entry("36", "DAEJEON_SEJONG_CHUNGNAM"),
            Map.entry("41", "GYEONGGI"),
            Map.entry("43", "CHUNGBUK"),
            Map.entry("44", "DAEJEON_SEJONG_CHUNGNAM"),
            Map.entry("47", "DAEGU_GYEONGBUK"),
            Map.entry("48", "BUSAN_ULSAN_GYEONGNAM"),
            Map.entry("50", "JEJU"),
            Map.entry("51", "GANGWON"),
            Map.entry("52", "JEONBUK")
    );

    @Test
    void CSV에서_지역_계층과_membership을_읽는다() {
        MapClusteringRegionPolicy policy = repository(groupCsv(), membershipCsv(), regionCsv()).current();

        assertEquals("2026-09-02-v1", policy.policyVersion());
        assertEquals("2026-07-01", policy.regionDatasetVersion());
        assertEquals(1, policy.groupsAt(MapClusteringStage.SERVICE_ZONE).size());
        assertEquals(
                "BASIC_REGION:41130",
                policy.groupOf("41131", MapClusteringStage.BASIC_REGION).orElseThrow().key().value()
        );
    }

    @Test
    void 배포용_지역_정책은_현재_snapshot과_핵심_예외를_보존한다() {
        MapClusteringRegionPolicy policy = productionRepository().current();

        assertEquals(11, policy.groupsAt(MapClusteringStage.SERVICE_ZONE).size());
        assertEquals(16, policy.groupsAt(MapClusteringStage.METROPOLITAN).size());
        assertEquals(230, policy.groupsAt(MapClusteringStage.BASIC_REGION).size());
        assertGroupKey(policy, "41131", MapClusteringStage.BASIC_REGION, "BASIC_REGION:41130");
        assertGroupKey(policy, "12210", MapClusteringStage.SERVICE_ZONE, "SERVICE_ZONE:GWANGJU_JEONNAM");
        assertGroupKey(policy, "36110", MapClusteringStage.BASIC_REGION, "BASIC_REGION:36110");
        assertGroupKey(policy, "28125", MapClusteringStage.BASIC_REGION, "BASIC_REGION:28125");
    }

    @Test
    void 같은_서울_표시명도_단계별로_서로_다른_identity를_가진다() {
        MapClusteringRegionPolicy policy = productionRepository().current();

        assertGroupKey(policy, "11680", MapClusteringStage.SERVICE_ZONE, "SERVICE_ZONE:SEOUL");
        assertGroupKey(policy, "11680", MapClusteringStage.METROPOLITAN, "METROPOLITAN:11");
        assertEquals("서울", policy.groupOf("11680", MapClusteringStage.SERVICE_ZONE).orElseThrow().label());
        assertEquals("서울", policy.groupOf("11680", MapClusteringStage.METROPOLITAN).orElseThrow().label());
    }

    @Test
    void 전체_canonical_코드의_단계별_소속이_현재_정책과_일치한다() throws IOException {
        MapClusteringRegionPolicy policy = productionRepository().current();
        List<String> canonicalRegionCodes = canonicalRegionCodes();
        Set<String> basicGroupKeys = basicGroupKeys(policy);

        canonicalRegionCodes.forEach(code -> assertCanonicalHierarchy(policy, basicGroupKeys, code));
        assertEquals(39, collapsedGeneralDistrictCount(policy, canonicalRegionCodes));
        assertEquals(13, collapsedParentCityCount(policy, canonicalRegionCodes));
    }

    @ParameterizedTest
    @CsvSource({
            "other, 2026-07-01",
            "2026-09-02-v1, 2026-06-01"
    })
    void zoom_정책과_version이_다르면_거부한다(String policyVersion, String regionDatasetVersion) {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(groupCsv(), membershipCsv(), regionCsv(), policyVersion, regionDatasetVersion)
        );

        assertTrue(exception.getMessage().contains("zoom policy"));
        assertTrue(exception.getMessage().contains("policyVersion"));
    }

    @Test
    void group과_membership_CSV의_version이_다르면_거부한다() {
        String changedVersion = membershipCsv().replace("2026-09-02-v1", "2026-09-03-v1");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(groupCsv(), changedVersion, regionCsv())
        );

        assertTrue(exception.getMessage().contains("group and membership"));
        assertTrue(exception.getMessage().contains("policyVersion"));
    }

    @Test
    void canonical_지역_CSV의_effective_date가_다르면_거부한다() {
        String changedDate = regionCsv().replace("2026-07-01", "2026-06-01");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(groupCsv(), membershipCsv(), changedDate)
        );

        assertTrue(exception.getMessage().contains("effectiveDate"));
    }

    @Test
    void membership_행마다_version이_다르면_행_번호와_함께_거부한다() {
        String inconsistent = membershipCsv().replaceFirst(
                "2026-09-02-v1,2026-07-01,41131",
                "other,2026-07-01,41131"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(groupCsv(), inconsistent, regionCsv())
        );

        assertTrue(exception.getMessage().contains("line 3"));
        assertTrue(exception.getMessage().contains("policyVersion"));
    }

    @Test
    void group_행마다_version이_다르면_행_번호와_함께_거부한다() {
        String inconsistent = groupCsv().replaceFirst(
                "2026-09-02-v1,2026-07-01,2",
                "other,2026-07-01,2"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> repository(inconsistent, membershipCsv(), regionCsv())
        );

        assertTrue(exception.getMessage().contains("line 3"));
        assertTrue(exception.getMessage().contains("policyVersion"));
    }

    private static CsvMapClusteringRegionPolicyRepository repository(
            String groups,
            String memberships,
            String regions
    ) {
        return repository(groups, memberships, regions, "2026-09-02-v1", "2026-07-01");
    }

    private static CsvMapClusteringRegionPolicyRepository productionRepository() {
        CsvMapClusteringZoomPolicyRepository zoomRepository = new CsvMapClusteringZoomPolicyRepository(
                new ClassPathResource("map-clustering/stage-transitions.csv")
        );
        return new CsvMapClusteringRegionPolicyRepository(
                new ClassPathResource("map-clustering/groups.csv"),
                new ClassPathResource("map-clustering/memberships.csv"),
                new ClassPathResource("region/regions.csv"),
                zoomRepository
        );
    }

    private static void assertGroupKey(
            MapClusteringRegionPolicy policy,
            String canonicalRegionCode,
            MapClusteringStage stage,
            String expectedKey
    ) {
        assertEquals(expectedKey, policy.groupOf(canonicalRegionCode, stage).orElseThrow().key().value());
    }

    private static void assertCanonicalHierarchy(
            MapClusteringRegionPolicy policy,
            Set<String> basicGroupKeys,
            String canonicalRegionCode
    ) {
        String metropolitanCode = canonicalRegionCode.substring(0, 2);
        assertGroupKey(policy, canonicalRegionCode, MapClusteringStage.METROPOLITAN,
                "METROPOLITAN:" + metropolitanCode);
        assertGroupKey(policy, canonicalRegionCode, MapClusteringStage.SERVICE_ZONE,
                "SERVICE_ZONE:" + SERVICE_ZONE_BY_METROPOLITAN_CODE.get(metropolitanCode));
        assertGroupKey(policy, canonicalRegionCode, MapClusteringStage.BASIC_REGION,
                expectedBasicGroupKey(canonicalRegionCode, basicGroupKeys));
    }

    private static Set<String> basicGroupKeys(MapClusteringRegionPolicy policy) {
        return policy.groupsAt(MapClusteringStage.BASIC_REGION).stream()
                .map(group -> group.key().value())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String expectedBasicGroupKey(String canonicalRegionCode, Set<String> basicGroupKeys) {
        String ownGroupKey = "BASIC_REGION:" + canonicalRegionCode;
        if (basicGroupKeys.contains(ownGroupKey)) {
            return ownGroupKey;
        }
        return "BASIC_REGION:" + canonicalRegionCode.substring(0, 4) + "0";
    }

    private static long collapsedGeneralDistrictCount(
            MapClusteringRegionPolicy policy,
            List<String> canonicalRegionCodes
    ) {
        return canonicalRegionCodes.stream()
                .filter(code -> !resolvedBasicGroupKey(policy, code).equals("BASIC_REGION:" + code))
                .count();
    }

    private static long collapsedParentCityCount(
            MapClusteringRegionPolicy policy,
            List<String> canonicalRegionCodes
    ) {
        return canonicalRegionCodes.stream()
                .filter(code -> !resolvedBasicGroupKey(policy, code).equals("BASIC_REGION:" + code))
                .map(code -> resolvedBasicGroupKey(policy, code))
                .distinct()
                .count();
    }

    private static String resolvedBasicGroupKey(MapClusteringRegionPolicy policy, String canonicalRegionCode) {
        return policy.groupOf(canonicalRegionCode, MapClusteringStage.BASIC_REGION)
                .orElseThrow()
                .key()
                .value();
    }

    private static List<String> canonicalRegionCodes() throws IOException {
        ClassPathResource resource = new ClassPathResource("region/regions.csv");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .skip(1)
                    .filter(line -> !line.startsWith("#"))
                    .map(line -> line.split(",", -1)[0])
                    .toList();
        }
    }

    private static CsvMapClusteringRegionPolicyRepository repository(
            String groups,
            String memberships,
            String regions,
            String zoomPolicyVersion,
            String zoomRegionDatasetVersion
    ) {
        return new CsvMapClusteringRegionPolicyRepository(
                resource(groups),
                resource(memberships),
                resource(regions),
                zoomRepository(zoomPolicyVersion, zoomRegionDatasetVersion)
        );
    }

    private static MapClusteringZoomPolicyRepository zoomRepository(
            String policyVersion,
            String regionDatasetVersion
    ) {
        MapClusteringZoomPolicy policy = MapClusteringZoomPolicy.of(
                new MapClusteringPolicyVersion(policyVersion, regionDatasetVersion),
                transitions()
        );
        return () -> policy;
    }

    private static List<MapClusteringTransition> transitions() {
        return List.of(
                transition(MapClusteringStage.SERVICE_ZONE, MapClusteringStage.METROPOLITAN, "7.50", "8.50"),
                transition(MapClusteringStage.METROPOLITAN, MapClusteringStage.BASIC_REGION, "10.00", "11.00"),
                transition(MapClusteringStage.BASIC_REGION, MapClusteringStage.INDIVIDUAL, "13.00", "14.00")
        );
    }

    private static MapClusteringTransition transition(
            MapClusteringStage from,
            MapClusteringStage to,
            String boundaryZoom,
            String expansionZoom
    ) {
        return new MapClusteringTransition(
                from,
                to,
                decimal(boundaryZoom),
                decimal("0.20"),
                decimal(expansionZoom)
        );
    }

    private static String groupCsv() {
        return GROUP_HEADER + "\n"
                + "2026-09-02-v1,2026-07-01,1,SERVICE_ZONE:CAPITAL,수도권,\n"
                + "2026-09-02-v1,2026-07-01,2,METROPOLITAN:41,경기,SERVICE_ZONE:CAPITAL\n"
                + "2026-09-02-v1,2026-07-01,3,BASIC_REGION:41130,성남시,METROPOLITAN:41\n";
    }

    private static String membershipCsv() {
        return MEMBERSHIP_HEADER + "\n"
                + "2026-09-02-v1,2026-07-01,41130,BASIC_REGION:41130\n"
                + "2026-09-02-v1,2026-07-01,41131,BASIC_REGION:41130\n";
    }

    private static String regionCsv() {
        return REGION_HEADER + "\n"
                + "# effectiveDate=2026-07-01\n"
                + "41130,경기도,성남시,경기도 성남시\n"
                + "41131,경기도,성남시 수정구,경기도 성남시 수정구\n";
    }

    private static ByteArrayResource resource(String contents) {
        return new ByteArrayResource(contents.getBytes(StandardCharsets.UTF_8));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
