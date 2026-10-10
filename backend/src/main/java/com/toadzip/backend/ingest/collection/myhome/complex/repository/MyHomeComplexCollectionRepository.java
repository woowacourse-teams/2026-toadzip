package com.toadzip.backend.ingest.collection.myhome.complex.repository;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyHomeComplexCollectionRepository extends JpaRepository<MyHomeComplexSource, Long> {

    @Override
    @EntityGraph(attributePaths = {"rows", "region"})
    List<MyHomeComplexSource> findAll();

    @EntityGraph(attributePaths = {"rows", "region"})
    List<MyHomeComplexSource> findAllByRegion_ProvinceCodeAndRegion_DistrictCode(
            String provinceCode, String districtCode
    );

    @EntityGraph(attributePaths = {"rows", "region"})
    List<MyHomeComplexSource> findAllByHsmpSnIn(Collection<Long> hsmpSns);
}
