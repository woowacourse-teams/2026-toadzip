package com.toadzip.backend.housing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {

	Optional<HousingComplex> findBySourceComplexIdAndSupplyTypeName(String sourceComplexId, String supplyTypeName);

	/**
	 * 같은 필지와 공급 유형에 여러 단지가 연결될 수 있어 후보 전체를 조회한다.
	 */
	List<HousingComplex> findAllByAddressPnuAndSupplyTypeName(String pnu, String supplyTypeName);

}
