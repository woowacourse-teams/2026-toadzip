package com.toadzip.backend.housing.service;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.MapClusteringAggregateNode;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import com.toadzip.backend.housing.dto.response.HousingMapAggregateNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapIndividualNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.housing.repository.HousingComplexSearchCondition;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class HousingMapResponseFactory {

    private final MapClusteringAggregateNodeQuery aggregateNodeQuery;
    private final ComplexSummaryQueryRepository complexRepository;
    private final HousingComplexSummaryMapper summaryMapper;

    HousingMapResponseFactory(
            MapClusteringAggregateNodeQuery aggregateNodeQuery,
            ComplexSummaryQueryRepository complexRepository,
            HousingComplexSummaryMapper summaryMapper
    ) {
        this.aggregateNodeQuery = aggregateNodeQuery;
        this.complexRepository = complexRepository;
        this.summaryMapper = summaryMapper;
    }

    HousingMapNodeResult create(
            MapClusteringStage stage,
            MapBounds bounds,
            HousingComplexFilterCondition filters,
            MapClusteringZoomPolicy zoomPolicy
    ) {
        if (stage == MapClusteringStage.INDIVIDUAL) {
            return individualNodes(bounds, filters);
        }
        return aggregateNodes(stage, bounds, filters, zoomPolicy);
    }

    private HousingMapNodeResult individualNodes(
            MapBounds bounds,
            HousingComplexFilterCondition filters
    ) {
        HousingComplexSearchCondition condition = new HousingComplexSearchCondition(bounds, filters);
        List<HousingMapNodeResponse> nodes = complexRepository.findAll(condition).stream()
                .map(summaryMapper::toMapItem)
                .map(HousingMapIndividualNodeResponse::new)
                .map(HousingMapNodeResponse.class::cast)
                .toList();
        return new HousingMapNodeResult(HousingMapRepresentation.INDIVIDUAL, nodes);
    }

    private HousingMapNodeResult aggregateNodes(
            MapClusteringStage stage,
            MapBounds bounds,
            HousingComplexFilterCondition filters,
            MapClusteringZoomPolicy zoomPolicy
    ) {
        BigDecimal expansionZoom = requiredExpansionZoom(stage, zoomPolicy);
        int nextStage = stage.next().orElseThrow().number();
        List<HousingMapNodeResponse> nodes = aggregateNodeQuery.find(stage, bounds, filters).stream()
                .map(node -> aggregateNode(node, nextStage, expansionZoom))
                .map(HousingMapNodeResponse.class::cast)
                .toList();
        return new HousingMapNodeResult(HousingMapRepresentation.AGGREGATE, nodes);
    }

    private HousingMapAggregateNodeResponse aggregateNode(
            MapClusteringAggregateNode node,
            int nextStage,
            BigDecimal expansionZoom
    ) {
        return new HousingMapAggregateNodeResponse(
                node.group().key().value(),
                node.group().label(),
                node.representativePoint().latitude(),
                node.representativePoint().longitude(),
                node.uniqueComplexCount(),
                nextStage,
                expansionZoom
        );
    }

    private BigDecimal requiredExpansionZoom(
            MapClusteringStage stage,
            MapClusteringZoomPolicy zoomPolicy
    ) {
        return zoomPolicy.expansionZoom(stage)
                .orElseThrow(() -> new IllegalStateException("Missing expansion zoom for " + stage));
    }
}
