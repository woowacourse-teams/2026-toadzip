package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexRegionSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexRegionSourceRepository extends JpaRepository<MyHomeComplexRegionSource, Long> {

    Optional<MyHomeComplexRegionSource> findByProvinceCodeAndDistrictCode(String provinceCode, String districtCode);
}
