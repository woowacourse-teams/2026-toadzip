package com.toadzip.backend.housing.service;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.MapClusteringAggregateNode;
import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.housing.domain.MapClusteringRegionCandidate;
import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.housing.repository.MapClusteringAggregateQueryRepository;
import com.toadzip.backend.housing.repository.MapClusteringRegionCountRow;
import com.toadzip.backend.housing.repository.MapClusteringRegionPointPolicyRepository;
import com.toadzip.backend.housing.repository.MapClusteringRegionPolicyRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
final class MapClusteringAggregateNodeQuery {

    private final MapClusteringAggregateQueryRepository aggregateRepository;
    private final MapClusteringRegionPolicyRepository regionPolicyRepository;
    private final MapClusteringRegionPointPolicyRepository pointPolicyRepository;

    MapClusteringAggregateNodeQuery(
            MapClusteringAggregateQueryRepository aggregateRepository,
            MapClusteringRegionPolicyRepository regionPolicyRepository,
            MapClusteringRegionPointPolicyRepository pointPolicyRepository
    ) {
        this.aggregateRepository = aggregateRepository;
        this.regionPolicyRepository = regionPolicyRepository;
        this.pointPolicyRepository = pointPolicyRepository;
    }

    List<MapClusteringAggregateNode> find(
            MapClusteringStage stage,
            MapBounds bounds,
            HousingComplexFilterCondition filters
    ) {
        MapClusteringRegionPolicy policy = regionPolicyRepository.current();
        List<MapClusteringRegionCandidate> candidates = candidates(policy, stage, bounds, filters);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<MapClusteringRegionAssignment> assignments = assignments(policy, stage, candidates);
        return merge(candidates, aggregateRepository.findCounts(filters, assignments));
    }

    private List<MapClusteringRegionCandidate> candidates(
            MapClusteringRegionPolicy policy,
            MapClusteringStage stage,
            MapBounds bounds,
            HousingComplexFilterCondition filters
    ) {
        return pointPolicyRepository.current().candidates(
                policy.groupsAt(stage, filters.cityCountyDistrictCodes()), bounds
        );
    }

    private List<MapClusteringRegionAssignment> assignments(
            MapClusteringRegionPolicy policy,
            MapClusteringStage stage,
            List<MapClusteringRegionCandidate> candidates
    ) {
        Set<MapClusteringGroupKey> candidateKeys = candidates.stream()
                .map(candidate -> candidate.group().key())
                .collect(Collectors.toUnmodifiableSet());
        return policy.assignmentsAt(stage).stream()
                .filter(assignment -> candidateKeys.contains(assignment.groupKey()))
                .toList();
    }

    private List<MapClusteringAggregateNode> merge(
            List<MapClusteringRegionCandidate> candidates,
            List<MapClusteringRegionCountRow> counts
    ) {
        Map<MapClusteringGroupKey, Long> countByGroupKey = counts.stream()
                .collect(Collectors.toUnmodifiableMap(
                        MapClusteringRegionCountRow::groupKey,
                        MapClusteringRegionCountRow::uniqueComplexCount
                ));
        return candidates.stream()
                .map(candidate -> node(candidate, countByGroupKey))
                .toList();
    }

    private MapClusteringAggregateNode node(
            MapClusteringRegionCandidate candidate,
            Map<MapClusteringGroupKey, Long> countByGroupKey
    ) {
        long count = countByGroupKey.getOrDefault(candidate.group().key(), 0L);
        return new MapClusteringAggregateNode(
                candidate.group(), candidate.representativePoint(), count
        );
    }
}
