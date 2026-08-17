package com.toadzip.backend.notice;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YearMonthAttributeConverterTest {

	private final YearMonthAttributeConverter converter = new YearMonthAttributeConverter();

	@Test
	void convertsYearMonthToDatabaseValue() {
		assertThat(this.converter.convertToDatabaseColumn(YearMonth.of(2026, 8))).isEqualTo("2026-08");
	}

	@Test
	void convertsDatabaseValueToYearMonth() {
		assertThat(this.converter.convertToEntityAttribute("2026-08")).isEqualTo(YearMonth.of(2026, 8));
	}

	@Test
	void preservesNullValues() {
		assertThat(this.converter.convertToDatabaseColumn(null)).isNull();
		assertThat(this.converter.convertToEntityAttribute(null)).isNull();
	}

}
