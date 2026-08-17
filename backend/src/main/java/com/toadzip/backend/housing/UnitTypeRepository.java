package com.toadzip.backend.housing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitTypeRepository extends JpaRepository<UnitType, Long> {

	/**
	 * 주택형 자연키로 조회한다.
	 */
	Optional<UnitType> findByHousingComplexAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
			HousingComplex housingComplex, String typeName, BigDecimal exclusiveArea, BigDecimal residentialCommonArea);

	/**
	 * 한 단지의 주택형 후보 전체를 조회한다.
	 */
	List<UnitType> findByHousingComplex(HousingComplex housingComplex);

}
