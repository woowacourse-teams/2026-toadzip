package com.toadzip.backend.housing.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class MapClusteringRegionPoints {

    private final Map<MapClusteringGroupKey, MapCoordinate> coordinateByGroupKey;

    MapClusteringRegionPoints(List<MapClusteringRegionPoint> points) {
        requirePoints(points);
        Map<MapClusteringGroupKey, MapCoordinate> indexed = new LinkedHashMap<>();
        points.forEach(point -> addPoint(indexed, point));
        coordinateByGroupKey = Map.copyOf(indexed);
    }

    MapCoordinate requiredCoordinate(MapClusteringGroupKey groupKey) {
        MapCoordinate coordinate = coordinateByGroupKey.get(groupKey);
        if (coordinate != null) {
            return coordinate;
        }
        throw new IllegalArgumentException("Missing representative point: " + groupKey.value());
    }

    void validateGroups(List<MapClusteringRegionGroup> groups) {
        Set<MapClusteringGroupKey> expected = groups.stream()
                .map(MapClusteringRegionGroup::key)
                .collect(Collectors.toUnmodifiableSet());
        if (coordinateByGroupKey.keySet().equals(expected)) {
            return;
        }
        throw new IllegalArgumentException(differenceMessage(expected));
    }

    private String differenceMessage(Set<MapClusteringGroupKey> expected) {
        Set<String> missing = values(expected);
        missing.removeAll(values(coordinateByGroupKey.keySet()));
        Set<String> unknown = values(coordinateByGroupKey.keySet());
        unknown.removeAll(values(expected));
        return "Representative point groups differ: missing=" + missing + ", unknown=" + unknown;
    }

    private Set<String> values(Set<MapClusteringGroupKey> keys) {
        return keys.stream()
                .map(MapClusteringGroupKey::value)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private void addPoint(
            Map<MapClusteringGroupKey, MapCoordinate> indexed,
            MapClusteringRegionPoint point
    ) {
        MapCoordinate previous = indexed.putIfAbsent(point.groupKey(), point.coordinate());
        if (previous == null) {
            return;
        }
        throw new IllegalArgumentException("Duplicate representative point: " + point.groupKey().value());
    }

    private static void requirePoints(List<MapClusteringRegionPoint> points) {
        if (points != null && !points.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering representative points are required");
    }
}
