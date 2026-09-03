package com.toadzip.backend.housing.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class MapClusteringRegionGroups {

    private static final Map<MapClusteringStage, MapClusteringStage> PARENT_STAGES = Map.of(
            MapClusteringStage.METROPOLITAN, MapClusteringStage.SERVICE_ZONE,
            MapClusteringStage.BASIC_REGION, MapClusteringStage.METROPOLITAN
    );

    private final List<MapClusteringRegionGroup> values;
    private final Map<MapClusteringGroupKey, MapClusteringRegionGroup> valuesByKey;

    MapClusteringRegionGroups(List<MapClusteringRegionGroup> groups) {
        requireGroups(groups);
        values = List.copyOf(groups);
        valuesByKey = index(values);
        values.forEach(this::validateParent);
        values.stream()
                .filter(this::requiresChild)
                .forEach(this::validateChild);
    }

    List<MapClusteringRegionGroup> at(MapClusteringStage stage) {
        return values.stream()
                .filter(group -> group.stage() == stage)
                .toList();
    }

    List<MapClusteringRegionGroup> values() {
        return values;
    }

    MapClusteringRegionGroup ancestorAt(MapClusteringGroupKey key, MapClusteringStage stage) {
        MapClusteringRegionGroup group = requiredGroup(key);
        if (group.stage() == stage) {
            return group;
        }
        return ancestorAt(group.parentKey().orElseThrow(), stage);
    }

    void validateMembershipTargets(Set<MapClusteringGroupKey> keys) {
        keys.forEach(this::validateMembershipTarget);
        basicGroupKeys().forEach(key -> validateMembershipExists(key, keys));
    }

    private Map<MapClusteringGroupKey, MapClusteringRegionGroup> index(
            List<MapClusteringRegionGroup> groups
    ) {
        Map<MapClusteringGroupKey, MapClusteringRegionGroup> indexed = new LinkedHashMap<>();
        groups.forEach(group -> addGroup(indexed, group));
        return Map.copyOf(indexed);
    }

    private void addGroup(
            Map<MapClusteringGroupKey, MapClusteringRegionGroup> indexed,
            MapClusteringRegionGroup group
    ) {
        MapClusteringRegionGroup previous = indexed.putIfAbsent(group.key(), group);
        if (previous == null) {
            return;
        }
        throw new IllegalArgumentException("Duplicate map clustering group key: " + group.key().value());
    }

    private void validateParent(MapClusteringRegionGroup group) {
        if (group.stage() == MapClusteringStage.SERVICE_ZONE) {
            validateRoot(group);
            return;
        }
        validateParentStage(group, requiredParent(group));
    }

    private void validateRoot(MapClusteringRegionGroup group) {
        if (group.parentKey().isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("Service zone cannot have a parent: " + group.key().value());
    }

    private MapClusteringRegionGroup requiredParent(MapClusteringRegionGroup group) {
        MapClusteringGroupKey parentKey = group.parentKey()
                .orElseThrow(() -> new IllegalArgumentException("Missing parent for " + group.key().value()));
        return requiredGroup(parentKey);
    }

    private MapClusteringRegionGroup requiredGroup(MapClusteringGroupKey key) {
        return Optional.ofNullable(valuesByKey.get(key))
                .orElseThrow(() -> new IllegalArgumentException("Unknown map clustering group key: " + key.value()));
    }

    private void validateParentStage(
            MapClusteringRegionGroup group,
            MapClusteringRegionGroup parent
    ) {
        MapClusteringStage expected = PARENT_STAGES.get(group.stage());
        if (parent.stage() == expected) {
            return;
        }
        throw new IllegalArgumentException("Parent stage must be " + expected + " for " + group.key().value());
    }

    private boolean requiresChild(MapClusteringRegionGroup group) {
        return group.stage() != MapClusteringStage.BASIC_REGION;
    }

    private void validateChild(MapClusteringRegionGroup group) {
        if (values.stream().anyMatch(candidate -> hasParent(candidate, group.key()))) {
            return;
        }
        throw new IllegalArgumentException("Map clustering group has no child: " + group.key().value());
    }

    private boolean hasParent(MapClusteringRegionGroup group, MapClusteringGroupKey parentKey) {
        return group.parentKey().filter(parentKey::equals).isPresent();
    }

    private Set<MapClusteringGroupKey> basicGroupKeys() {
        return valuesByKey.entrySet().stream()
                .filter(entry -> entry.getValue().stage() == MapClusteringStage.BASIC_REGION)
                .map(Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void validateMembershipTarget(MapClusteringGroupKey key) {
        MapClusteringRegionGroup group = requiredGroup(key);
        if (group.stage() == MapClusteringStage.BASIC_REGION) {
            return;
        }
        throw new IllegalArgumentException("Membership target must be a basic region: " + key.value());
    }

    private void validateMembershipExists(
            MapClusteringGroupKey key,
            Set<MapClusteringGroupKey> membershipKeys
    ) {
        if (membershipKeys.contains(key)) {
            return;
        }
        throw new IllegalArgumentException("Basic region has no membership: " + key.value());
    }

    private static void requireGroups(List<MapClusteringRegionGroup> groups) {
        if (groups != null && !groups.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering region groups are required");
    }
}
