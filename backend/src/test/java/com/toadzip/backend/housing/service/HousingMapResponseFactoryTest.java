package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.MapClusteringAggregateNode;
import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringRegionGroup;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.housing.dto.response.AgencyResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapItemResponse;
import com.toadzip.backend.housing.dto.response.HousingMapAggregateNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapIndividualNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.housing.repository.CsvMapClusteringZoomPolicyRepository;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(MockitoExtension.class)
class HousingMapResponseFactoryTest {

    @Mock
    private MapClusteringAggregateNodeQuery aggregateNodeQuery;

    @Mock
    private ComplexSummaryQueryRepository complexRepository;

    @Mock
    private HousingComplexSummaryMapper summaryMapper;

    private HousingMapResponseFactory factory;

    @BeforeEach
    void setUp() {
        factory = new HousingMapResponseFactory(aggregateNodeQuery, complexRepository, summaryMapper);
    }

    @Test
    void 집계_node에_정확히_다음_단계와_확대_zoom을_더한다() {
        MapClusteringRegionGroup group = new MapClusteringRegionGroup(
                new MapClusteringGroupKey("BASIC_REGION:41130"),
                "성남시",
                MapClusteringStage.BASIC_REGION,
                Optional.of(new MapClusteringGroupKey("METROPOLITAN:41"))
        );
        when(aggregateNodeQuery.find(any(), any(), any())).thenReturn(List.of(
                new MapClusteringAggregateNode(
                        group, new MapCoordinate(decimal("37.4"), decimal("127.1")), 1L
                )
        ));

        HousingMapNodeResult result = factory.create(
                MapClusteringStage.BASIC_REGION, bounds(), filters(), zoomPolicy()
        );
        HousingMapAggregateNodeResponse node = (HousingMapAggregateNodeResponse) result.nodes().getFirst();

        assertEquals(HousingMapRepresentation.AGGREGATE, result.representation());
        assertEquals("AGGREGATE", node.type());
        assertEquals(1L, node.uniqueComplexCount());
        assertEquals(4, node.nextStage());
        assertEquals(decimal("14.00"), node.expansionZoom());
    }

    @Test
    void 개별_단계는_같은_좌표의_모든_단지를_별도_node로_반환한다() {
        ComplexSummaryRow firstRow = row(1L, "첫 단지");
        ComplexSummaryRow secondRow = row(2L, "둘째 단지");
        when(complexRepository.findAll(any())).thenReturn(List.of(firstRow, secondRow));
        when(summaryMapper.toMapItem(firstRow)).thenReturn(item(1L, "첫 단지"));
        when(summaryMapper.toMapItem(secondRow)).thenReturn(item(2L, "둘째 단지"));

        HousingMapNodeResult result = factory.create(
                MapClusteringStage.INDIVIDUAL, bounds(), filters(), zoomPolicy()
        );

        assertEquals(HousingMapRepresentation.INDIVIDUAL, result.representation());
        assertEquals(2, result.nodes().size());
        assertEquals(1L, ((HousingMapIndividualNodeResponse) result.nodes().get(0)).complexId());
        assertEquals(2L, ((HousingMapIndividualNodeResponse) result.nodes().get(1)).complexId());
    }

    private static ComplexSummaryRow row(long complexId, String name) {
        return new ComplexSummaryRow(
                complexId, name, null, "41", "41130", "행복주택", "LH",
                decimal("37.4"), decimal("127.1"), null, null, null, null,
                null, null, null, null, null, null, null, null
        );
    }

    private static HousingComplexMapItemResponse item(long complexId, String name) {
        return new HousingComplexMapItemResponse(
                complexId, name, decimal("37.4"), decimal("127.1"), "HAPPY_HOUSING",
                new AgencyResponse("LH", "한국토지주택공사"), null, null,
                null, null, null, null
        );
    }

    private static com.toadzip.backend.housing.domain.MapClusteringZoomPolicy zoomPolicy() {
        return new CsvMapClusteringZoomPolicyRepository(
                new ClassPathResource("map-clustering/stage-transitions.csv")
        ).current();
    }

    private static MapBounds bounds() {
        return MapBounds.of(decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("128.0"));
    }

    private static HousingComplexFilterCondition filters() {
        return new HousingComplexFilterCondition(
                null, null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                null, null, null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 9, 3)
        );
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
