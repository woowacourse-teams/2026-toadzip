package com.toadzip.backend.housing.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MapClusteringRegionPolicy {

    private final MapClusteringPolicyVersion version;
    private final MapClusteringRegionGroups groups;
    private final MapClusteringRegionMemberships memberships;

    private MapClusteringRegionPolicy(
            MapClusteringPolicyVersion version,
            MapClusteringRegionGroups groups,
            MapClusteringRegionMemberships memberships
    ) {
        this.version = version;
        this.groups = groups;
        this.memberships = memberships;
    }

    public static MapClusteringRegionPolicy of(
            MapClusteringPolicyVersion version,
            List<MapClusteringRegionGroup> groups,
            List<MapClusteringRegionMembership> memberships,
            Set<String> canonicalRegionCodes
    ) {
        Objects.requireNonNull(version, "version");
        MapClusteringRegionGroups regionGroups = new MapClusteringRegionGroups(groups);
        MapClusteringRegionMemberships regionMemberships = new MapClusteringRegionMemberships(
                memberships,
                canonicalRegionCodes,
                regionGroups
        );
        return new MapClusteringRegionPolicy(version, regionGroups, regionMemberships);
    }

    public String policyVersion() {
        return version.policyVersion();
    }

    public String regionDatasetVersion() {
        return version.regionDatasetVersion();
    }

    public List<MapClusteringRegionGroup> groupsAt(MapClusteringStage stage) {
        Objects.requireNonNull(stage, "stage");
        return groups.at(stage);
    }

    public Optional<MapClusteringRegionGroup> groupOf(
            String canonicalRegionCode,
            MapClusteringStage stage
    ) {
        Objects.requireNonNull(stage, "stage");
        if (stage == MapClusteringStage.INDIVIDUAL) {
            return Optional.empty();
        }
        return memberships.basicGroupKeyOf(canonicalRegionCode)
                .map(groupKey -> groups.ancestorAt(groupKey, stage));
    }
}
