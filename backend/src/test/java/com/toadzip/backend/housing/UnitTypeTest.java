package com.toadzip.backend.housing;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnitTypeTest {

	@Test
	void updatesBaseRentTermsOnlyWhenValuesChange() {
		UnitType unitType = unitType();
		BaseRentTerms terms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);

		assertThat(unitType.updateBaseRentTerms(terms)).isTrue();
		assertThat(unitType.updateBaseRentTerms(new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L))).isFalse();
		assertThat(unitType.updateBaseRentTerms(new BaseRentTerms(11_000_000L, 100_000L, 3_000_000L))).isTrue();
	}

	@Test
	void updatesTotalUnitCountOnlyWhenValueChanges() {
		UnitType unitType = unitType();

		assertThat(unitType.updateTotalUnitCount(50)).isTrue();
		assertThat(unitType.updateTotalUnitCount(50)).isFalse();
		assertThat(unitType.updateTotalUnitCount(60)).isTrue();
		assertThat(unitType.getTotalUnitCount()).isEqualTo(60);
	}

	private UnitType unitType() {
		HousingComplex complex = new HousingComplex("대전 산내", address(), "complex-1", "국민임대", 100, "LH대전충남");
		return new UnitType(complex, "36", new BigDecimal("36.5000"), new BigDecimal("15.0000"));
	}

	private Address address() {
		return new Address("대전광역시 동구 산내로 123", "3011013600101900001", "30", "대전광역시", "110", "동구");
	}

}
