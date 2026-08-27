package com.toadzip.backend.region.repository;

import java.util.Optional;

public interface RegionCodeResolver {

    Optional<String> resolve(String provinceCode, String cityCountyDistrictCode);
}
