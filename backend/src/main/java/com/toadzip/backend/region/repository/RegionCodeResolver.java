package com.toadzip.backend.region.repository;

import java.util.Optional;
import java.util.Set;

public interface RegionCodeResolver {

    Optional<String> resolve(String provinceCode, String cityCountyDistrictCode);

    Optional<Set<String>> equivalentCodes(String regionCode);
}
