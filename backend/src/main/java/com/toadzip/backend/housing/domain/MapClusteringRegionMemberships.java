package com.toadzip.backend.housing.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class MapClusteringRegionMemberships {

    private final Map<String, MapClusteringGroupKey> groupKeyByRegionCode;

    MapClusteringRegionMemberships(
            List<MapClusteringRegionMembership> memberships,
            Set<String> canonicalRegionCodes,
            MapClusteringRegionGroups groups
    ) {
        requireInputs(memberships, canonicalRegionCodes, groups);
        groupKeyByRegionCode = index(memberships);
        validateCanonicalRegionCodes(canonicalRegionCodes);
        groups.validateMembershipTargets(Set.copyOf(groupKeyByRegionCode.values()));
    }

    Optional<MapClusteringGroupKey> basicGroupKeyOf(String canonicalRegionCode) {
        if (canonicalRegionCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(groupKeyByRegionCode.get(canonicalRegionCode));
    }

    List<MapClusteringRegionAssignment> assignmentsAt(
            MapClusteringStage stage,
            MapClusteringRegionGroups groups
    ) {
        if (stage == MapClusteringStage.INDIVIDUAL) {
            return List.of();
        }
        return groupKeyByRegionCode.entrySet().stream()
                .sorted(Entry.comparingByKey())
                .map(entry -> assignmentAt(entry, stage, groups))
                .toList();
    }

    private MapClusteringRegionAssignment assignmentAt(
            Entry<String, MapClusteringGroupKey> entry,
            MapClusteringStage stage,
            MapClusteringRegionGroups groups
    ) {
        MapClusteringGroupKey groupKey = groups.ancestorAt(entry.getValue(), stage).key();
        return new MapClusteringRegionAssignment(entry.getKey(), groupKey);
    }

    private Map<String, MapClusteringGroupKey> index(List<MapClusteringRegionMembership> memberships) {
        Map<String, MapClusteringGroupKey> indexed = new LinkedHashMap<>();
        memberships.forEach(membership -> addMembership(indexed, membership));
        return Map.copyOf(indexed);
    }

    private void addMembership(
            Map<String, MapClusteringGroupKey> indexed,
            MapClusteringRegionMembership membership
    ) {
        MapClusteringGroupKey previous = indexed.putIfAbsent(
                membership.canonicalRegionCode(),
                membership.basicRegionGroupKey()
        );
        if (previous == null) {
            return;
        }
        throw new IllegalArgumentException("Duplicate canonical region membership: "
                + membership.canonicalRegionCode());
    }

    private void validateCanonicalRegionCodes(Set<String> canonicalRegionCodes) {
        canonicalRegionCodes.forEach(MapClusteringRegionMemberships::validateCanonicalRegionCode);
        if (groupKeyByRegionCode.keySet().equals(canonicalRegionCodes)) {
            return;
        }
        throw new IllegalArgumentException(differenceMessage(canonicalRegionCodes));
    }

    private String differenceMessage(Set<String> canonicalRegionCodes) {
        Set<String> missing = new TreeSet<>(canonicalRegionCodes);
        missing.removeAll(groupKeyByRegionCode.keySet());
        Set<String> unknown = new TreeSet<>(groupKeyByRegionCode.keySet());
        unknown.removeAll(canonicalRegionCodes);
        return "Canonical region memberships differ: missing=" + missing + ", unknown=" + unknown;
    }

    private static void validateCanonicalRegionCode(String canonicalRegionCode) {
        if (canonicalRegionCode != null && canonicalRegionCode.matches("\\d{5}")) {
            return;
        }
        throw new IllegalArgumentException("Canonical region code must be exactly five digits");
    }

    private static void requireInputs(
            List<MapClusteringRegionMembership> memberships,
            Set<String> canonicalRegionCodes,
            MapClusteringRegionGroups groups
    ) {
        if (memberships == null || canonicalRegionCodes == null || groups == null) {
            throw new IllegalArgumentException("Map clustering region membership inputs are required");
        }
    }
}
