package com.toadzip.backend.housing;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class HousingRepositoryTest {

	@Autowired
	private HousingComplexRepository housingComplexRepository;

	@Autowired
	private UnitTypeRepository unitTypeRepository;

	@Test
	void findsComplexBySourceIdAndSupplyType() {
		HousingComplex complex = this.housingComplexRepository.save(complex("complex-1", "3011013600101900001"));

		assertThat(this.housingComplexRepository.findBySourceComplexIdAndSupplyTypeName("complex-1", "국민임대"))
			.contains(complex);
	}

	@Test
	void returnsEveryComplexCandidateWithSamePnuAndSupplyType() {
		HousingComplex first = this.housingComplexRepository.save(complex("complex-1", "3011013600101900001"));
		HousingComplex second = this.housingComplexRepository.save(complex("complex-2", "3011013600101900001"));

		assertThat(this.housingComplexRepository.findAllByAddressPnuAndSupplyTypeName("3011013600101900001", "국민임대"))
			.containsExactlyInAnyOrder(first, second);
	}

	@Test
	void findsUnitTypeByNaturalKey() {
		HousingComplex complex = this.housingComplexRepository.save(complex("complex-1", "3011013600101900001"));
		BigDecimal exclusiveArea = new BigDecimal("36.5000");
		BigDecimal commonArea = new BigDecimal("15.0000");
		UnitType unitType = this.unitTypeRepository.save(new UnitType(complex, "36", exclusiveArea, commonArea));

		assertThat(this.unitTypeRepository.findByHousingComplexAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
				complex, "36", exclusiveArea, commonArea))
			.contains(unitType);
	}

	private HousingComplex complex(String sourceComplexId, String pnu) {
		Address address = new Address("대전광역시 동구 산내로 123", pnu, "30", "대전광역시", "110", "동구");
		return new HousingComplex("대전 산내", address, sourceComplexId, "국민임대", 100, "LH대전충남");
	}

}
