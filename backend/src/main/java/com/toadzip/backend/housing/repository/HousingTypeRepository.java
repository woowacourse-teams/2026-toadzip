package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HousingTypeRepository extends JpaRepository<HousingType, Long> {

    List<HousingType> findAllByHousingComplex(HousingComplex housingComplex);

    @Query("select t.housingComplex.id as complexId, count(t) as count from HousingType t "
            + "where t.housingComplex.id in :complexIds group by t.housingComplex.id")
    List<ComplexTypeCount> countByComplexIds(Collection<Long> complexIds);

    interface ComplexTypeCount {
        Long getComplexId();

        long getCount();
    }
}
