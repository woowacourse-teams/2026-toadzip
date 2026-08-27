package com.toadzip.backend.region.repository;

import java.util.Optional;
import java.util.Set;

@FunctionalInterface
public interface RegionCodeResolver {

    Optional<String> resolve(String provinceCode, String cityCountyDistrictCode);

    default Optional<Set<String>> equivalentProvinceCodes(String provinceCode) {
        return Optional.empty();
    }

    default Optional<Set<String>> equivalentCodes(String regionCode) {
        return Optional.empty();
    }
}
