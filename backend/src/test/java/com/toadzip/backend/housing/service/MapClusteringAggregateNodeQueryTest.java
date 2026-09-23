package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.MapClusteringAggregateNode;
import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.housing.domain.MapClusteringRegionGroup;
import com.toadzip.backend.housing.domain.MapClusteringRegionMembership;
import com.toadzip.backend.housing.domain.MapClusteringRegionPoint;
import com.toadzip.backend.housing.domain.MapClusteringRegionPointPolicy;
import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.housing.repository.MapClusteringAggregateQueryRepository;
import com.toadzip.backend.housing.repository.MapClusteringRegionCountRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MapClusteringAggregateNodeQueryTest {

    private static final MapClusteringGroupKey SERVICE_ZONE_KEY = key("SERVICE_ZONE:SEOUL");
    private static final MapClusteringGroupKey METROPOLITAN_KEY = key("METROPOLITAN:11");
    private static final MapClusteringGroupKey FIRST_BASIC_KEY = key("BASIC_REGION:11110");
    private static final MapClusteringGroupKey SECOND_BASIC_KEY = key("BASIC_REGION:11140");

    @Mock
    private MapClusteringAggregateQueryRepository aggregateRepository;

    @Captor
    private ArgumentCaptor<List<MapClusteringRegionAssignment>> assignmentsCaptor;

    private MapClusteringAggregateNodeQuery query;

    @BeforeEach
    void setUp() {
        MapClusteringRegionPolicy regionPolicy = regionPolicy();
        query = new MapClusteringAggregateNodeQuery(
                aggregateRepository,
                () -> regionPolicy,
                () -> pointPolicy()
        );
    }

    @Test
    void 지역_filter_밖의_node를_제외하고_0건을_채운다() {
        HousingComplexFilterCondition filters = filters(Set.of("11110"));
        when(aggregateRepository.findCounts(any(), any())).thenReturn(List.of());

        List<MapClusteringAggregateNode> nodes = query.find(
                MapClusteringStage.BASIC_REGION,
                bounds(),
                filters
        );

        assertEquals(1, nodes.size());
        assertEquals(FIRST_BASIC_KEY, nodes.getFirst().group().key());
        assertEquals(0L, nodes.getFirst().uniqueComplexCount());
        verifyAssignments(filters, FIRST_BASIC_KEY);
    }

    @Test
    void 대표점이_viewport_밖인_node를_제외한다() {
        HousingComplexFilterCondition filters = filters(Set.of());
        when(aggregateRepository.findCounts(any(), any())).thenReturn(List.of());

        List<MapClusteringAggregateNode> nodes = query.find(
                MapClusteringStage.BASIC_REGION,
                bounds(),
                filters
        );

        assertEquals(List.of(FIRST_BASIC_KEY), groupKeys(nodes));
        verifyAssignments(filters, FIRST_BASIC_KEY);
    }

    @Test
    void viewport_안에_대표점이_없으면_DB를_조회하지_않는다() {
        MapBounds outsideBounds = MapBounds.of(
                decimal("33.0"), decimal("126.0"), decimal("34.0"), decimal("128.0")
        );

        List<MapClusteringAggregateNode> nodes = query.find(
                MapClusteringStage.BASIC_REGION,
                outsideBounds,
                filters(Set.of())
        );

        assertEquals(List.of(), nodes);
        verifyNoInteractions(aggregateRepository);
    }

    @Test
    void count가_1이어도_지역_node로_유지한다() {
        when(aggregateRepository.findCounts(any(), any())).thenReturn(
                List.of(new MapClusteringRegionCountRow(FIRST_BASIC_KEY, 1L))
        );

        List<MapClusteringAggregateNode> nodes = query.find(
                MapClusteringStage.BASIC_REGION,
                bounds(),
                filters(Set.of("11110"))
        );

        assertEquals(1L, nodes.getFirst().uniqueComplexCount());
    }

    private void verifyAssignments(
            HousingComplexFilterCondition filters,
            MapClusteringGroupKey expectedGroupKey
    ) {
        verify(aggregateRepository).findCounts(
                org.mockito.ArgumentMatchers.eq(filters), assignmentsCaptor.capture()
        );
        assertEquals(expectedGroupKey, assignmentsCaptor.getValue().getFirst().groupKey());
    }

    private static List<MapClusteringGroupKey> groupKeys(List<MapClusteringAggregateNode> nodes) {
        return nodes.stream().map(node -> node.group().key()).toList();
    }

    private static MapClusteringRegionPolicy regionPolicy() {
        List<MapClusteringRegionGroup> groups = List.of(
                group(SERVICE_ZONE_KEY, MapClusteringStage.SERVICE_ZONE, Optional.empty()),
                group(METROPOLITAN_KEY, MapClusteringStage.METROPOLITAN, Optional.of(SERVICE_ZONE_KEY)),
                group(FIRST_BASIC_KEY, MapClusteringStage.BASIC_REGION, Optional.of(METROPOLITAN_KEY)),
                group(SECOND_BASIC_KEY, MapClusteringStage.BASIC_REGION, Optional.of(METROPOLITAN_KEY))
        );
        List<MapClusteringRegionMembership> memberships = List.of(
                new MapClusteringRegionMembership("11110", FIRST_BASIC_KEY),
                new MapClusteringRegionMembership("11140", SECOND_BASIC_KEY)
        );
        return MapClusteringRegionPolicy.of(version(), groups, memberships, Set.of("11110", "11140"));
    }

    private static MapClusteringRegionPointPolicy pointPolicy() {
        return MapClusteringRegionPointPolicy.of(version(), List.of(
                point(SERVICE_ZONE_KEY, "37.5", "127.0"),
                point(METROPOLITAN_KEY, "37.5", "127.0"),
                point(FIRST_BASIC_KEY, "37.5", "127.0"),
                point(SECOND_BASIC_KEY, "39.0", "127.0")
        ));
    }

    private static MapClusteringRegionPoint point(
            MapClusteringGroupKey key,
            String latitude,
            String longitude
    ) {
        return new MapClusteringRegionPoint(
                key, new MapCoordinate(decimal(latitude), decimal(longitude))
        );
    }

    private static MapClusteringRegionGroup group(
            MapClusteringGroupKey key,
            MapClusteringStage stage,
            Optional<MapClusteringGroupKey> parentKey
    ) {
        return new MapClusteringRegionGroup(key, key.value(), stage, parentKey);
    }

    private static MapBounds bounds() {
        return MapBounds.of(decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("128.0"));
    }

    private static HousingComplexFilterCondition filters(Set<String> regionCodes) {
        return new HousingComplexFilterCondition(
                null, null, regionCodes, Set.of(), Set.of(), Set.of(), Set.of(),
                null, null, null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 9, 3)
        );
    }

    private static MapClusteringPolicyVersion version() {
        return new MapClusteringPolicyVersion("2026-09-02-v1", "2026-07-01");
    }

    private static MapClusteringGroupKey key(String value) {
        return new MapClusteringGroupKey(value);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
