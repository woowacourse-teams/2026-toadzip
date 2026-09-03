package com.toadzip.backend.housing.domain;

public record MapClusteringPolicyVersion(
        String policyVersion,
        String regionDatasetVersion
) {

    public MapClusteringPolicyVersion {
        requireValue(policyVersion, "policyVersion");
        requireValue(regionDatasetVersion, "regionDatasetVersion");
    }

    private static void requireValue(String value, String fieldName) {
        if (value != null && !value.isBlank()) {
            return;
        }
        throw new IllegalArgumentException(fieldName + " is required");
    }
}
