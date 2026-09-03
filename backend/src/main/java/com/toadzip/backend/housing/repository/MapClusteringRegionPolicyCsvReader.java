package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import org.springframework.core.io.Resource;

final class MapClusteringRegionPolicyCsvReader {

    MapClusteringRegionPolicy read(
            Resource groupResource,
            Resource membershipResource,
            Resource canonicalRegionResource
    ) {
        MapClusteringRegionGroupCsvRows groupRows = MapClusteringRegionGroupCsvRows.read(groupResource);
        MapClusteringRegionMembershipCsvRows membershipRows =
                MapClusteringRegionMembershipCsvRows.read(membershipResource);
        validateResourceVersions(groupRows.version(), membershipRows.version(), membershipResource);
        CanonicalRegionCsvCatalog canonicalCatalog = new CanonicalRegionCsvReader().read(canonicalRegionResource);
        validateEffectiveDate(groupRows.version(), canonicalCatalog, canonicalRegionResource);
        return createPolicy(groupRows, membershipRows, canonicalCatalog);
    }

    private MapClusteringRegionPolicy createPolicy(
            MapClusteringRegionGroupCsvRows groupRows,
            MapClusteringRegionMembershipCsvRows membershipRows,
            CanonicalRegionCsvCatalog canonicalCatalog
    ) {
        try {
            return MapClusteringRegionPolicy.of(
                    groupRows.version(),
                    groupRows.groups(),
                    membershipRows.memberships(),
                    canonicalCatalog.regionCodes()
            );
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidPolicy(groupRows.resource(), exception);
        }
    }

    private void validateResourceVersions(
            MapClusteringPolicyVersion groupVersion,
            MapClusteringPolicyVersion membershipVersion,
            Resource membershipResource
    ) {
        if (groupVersion.equals(membershipVersion)) {
            return;
        }
        throw new IllegalStateException("Map clustering group and membership policyVersion or "
                + "regionDatasetVersion differs in " + membershipResource.getDescription());
    }

    private void validateEffectiveDate(
            MapClusteringPolicyVersion version,
            CanonicalRegionCsvCatalog catalog,
            Resource canonicalRegionResource
    ) {
        if (version.regionDatasetVersion().equals(catalog.effectiveDate())) {
            return;
        }
        throw new IllegalStateException("Canonical region effectiveDate differs from regionDatasetVersion in "
                + canonicalRegionResource.getDescription());
    }
}
