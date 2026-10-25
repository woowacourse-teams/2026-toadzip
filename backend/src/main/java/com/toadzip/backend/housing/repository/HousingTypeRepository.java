package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HousingTypeRepository extends JpaRepository<HousingType, Long> {

    List<HousingType> findAllByHousingComplex(HousingComplex housingComplex);

    List<HousingType> findAllByHousingComplexIdIn(Collection<Long> complexIds);

    /** 휴지통 주택형까지 포함한다. 원천 동기화가 같은 원천 주택형을 다시 만들지 않게 할 때와 관리자 상세에서만 쓴다. */
    @Query(value = "SELECT * FROM housing_types WHERE housing_complex_id = :complexId ORDER BY id", nativeQuery = true)
    List<HousingType> findAllIncludingTrashByHousingComplexId(long complexId);

    @Query(value = "SELECT * FROM housing_types WHERE id = :id", nativeQuery = true)
    Optional<HousingType> findIncludingTrashById(long id);
}
