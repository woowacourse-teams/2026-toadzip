package com.toadzip.backend.notice;

import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YearMonthAttributeConverterTest {

	private final YearMonthAttributeConverter converter = new YearMonthAttributeConverter();

	@Test
	@DisplayName("YearMonth를 데이터베이스 값으로 변환한다")
	void convertsYearMonthToDatabaseValue() {
		assertThat(this.converter.convertToDatabaseColumn(YearMonth.of(2026, 8))).isEqualTo("2026-08");
	}

	@Test
	@DisplayName("데이터베이스 값을 YearMonth로 변환한다")
	void convertsDatabaseValueToYearMonth() {
		assertThat(this.converter.convertToEntityAttribute("2026-08")).isEqualTo(YearMonth.of(2026, 8));
	}

	@Test
	@DisplayName("null 변환 값은 null로 유지한다")
	void preservesNullValues() {
		assertThat(this.converter.convertToDatabaseColumn(null)).isNull();
		assertThat(this.converter.convertToEntityAttribute(null)).isNull();
	}

}
