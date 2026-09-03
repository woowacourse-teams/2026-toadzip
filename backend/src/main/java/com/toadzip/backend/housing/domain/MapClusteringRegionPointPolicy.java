package com.toadzip.backend.housing.domain;

import java.util.List;
import java.util.Objects;

public final class MapClusteringRegionPointPolicy {

    private final MapClusteringPolicyVersion version;
    private final MapClusteringRegionPoints points;

    private MapClusteringRegionPointPolicy(
            MapClusteringPolicyVersion version,
            MapClusteringRegionPoints points
    ) {
        this.version = version;
        this.points = points;
    }

    public static MapClusteringRegionPointPolicy of(
            MapClusteringPolicyVersion version,
            List<MapClusteringRegionPoint> points
    ) {
        Objects.requireNonNull(version, "version");
        return new MapClusteringRegionPointPolicy(version, new MapClusteringRegionPoints(points));
    }

    public List<MapClusteringRegionCandidate> candidates(
            List<MapClusteringRegionGroup> groups,
            MapBounds bounds
    ) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(bounds, "bounds");
        return groups.stream()
                .map(this::candidate)
                .filter(candidate -> bounds.contains(candidate.representativePoint()))
                .toList();
    }

    public void validateGroups(List<MapClusteringRegionGroup> groups) {
        Objects.requireNonNull(groups, "groups");
        points.validateGroups(groups);
    }

    public String policyVersion() {
        return version.policyVersion();
    }

    public String regionDatasetVersion() {
        return version.regionDatasetVersion();
    }

    private MapClusteringRegionCandidate candidate(MapClusteringRegionGroup group) {
        return new MapClusteringRegionCandidate(group, points.requiredCoordinate(group.key()));
    }
}
