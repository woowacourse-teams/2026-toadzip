package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HousingTypeRepository extends JpaRepository<HousingType, Long> {

    List<HousingType> findAllByHousingComplex(HousingComplex housingComplex);
}
