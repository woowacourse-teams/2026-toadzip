package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringRegionAssignment;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MapClusteringRegionCodeMappings {

    private final List<MapClusteringRegionCodeMapping> values;

    private MapClusteringRegionCodeMappings(List<MapClusteringRegionCodeMapping> values) {
        Map<String, MapClusteringRegionCodeMapping> indexed = new LinkedHashMap<>();
        values.forEach(mapping -> addMapping(indexed, mapping));
        this.values = List.copyOf(indexed.values());
    }

    static MapClusteringRegionCodeMappings from(
            List<MapClusteringRegionAssignment> assignments,
            RegionCodeResolver regionCodeResolver
    ) {
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(regionCodeResolver, "regionCodeResolver");
        List<MapClusteringRegionCodeMapping> mappings = assignments.stream()
                .flatMap(assignment -> expand(assignment, regionCodeResolver).stream())
                .toList();
        return new MapClusteringRegionCodeMappings(mappings);
    }

    List<MapClusteringRegionCodeMapping> values() {
        return values;
    }

    private static List<MapClusteringRegionCodeMapping> expand(
            MapClusteringRegionAssignment assignment,
            RegionCodeResolver regionCodeResolver
    ) {
        Set<String> storedCodes = regionCodeResolver.equivalentCodes(assignment.canonicalRegionCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown canonical region code: " + assignment.canonicalRegionCode()
                ));
        return storedCodes.stream()
                .sorted()
                .map(code -> new MapClusteringRegionCodeMapping(code, assignment.groupKey()))
                .toList();
    }

    private static void addMapping(
            Map<String, MapClusteringRegionCodeMapping> indexed,
            MapClusteringRegionCodeMapping mapping
    ) {
        MapClusteringRegionCodeMapping previous = indexed.putIfAbsent(mapping.storedRegionCode(), mapping);
        if (previous == null) {
            return;
        }
        throw new IllegalArgumentException("Duplicate stored region code: " + mapping.storedRegionCode());
    }
}
