package com.toadzip.backend.notice;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class LhUnitSupplyValuesTest {

	@Test
	void isEmptyWhenEveryValueIsNull() {
		LhUnitSupplyValues values = new LhUnitSupplyValues(null, null, null, null, null, null, null, null);

		assertThat(values.isEmpty()).isTrue();
	}

	@ParameterizedTest
	@MethodSource("nonEmptyValues")
	void isNotEmptyWhenAnyValueExists(LhUnitSupplyValues values) {
		assertThat(values.isEmpty()).isFalse();
	}

	private static Stream<LhUnitSupplyValues> nonEmptyValues() {
		return Stream.of(new LhUnitSupplyValues("대전 산내", null, null, null, null, null, null, null),
				new LhUnitSupplyValues(null, "36", null, null, null, null, null, null),
				new LhUnitSupplyValues(null, null, new BigDecimal("36.5000"), null, null, null, null, null),
				new LhUnitSupplyValues(null, null, null, new BigDecimal("51.5000"), null, null, null, null),
				new LhUnitSupplyValues(null, null, null, null, 100, null, null, null),
				new LhUnitSupplyValues(null, null, null, null, null, 20, null, null),
				new LhUnitSupplyValues(null, null, null, null, null, null, "10,000,000", null),
				new LhUnitSupplyValues(null, null, null, null, null, null, null, "100,000"));
	}

}
