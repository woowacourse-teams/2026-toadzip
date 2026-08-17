package com.toadzip.backend.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceValuesTest {

	@Test
	@DisplayName("빈 문자열은 null로 바꾸고 나머지 문자열은 앞뒤 공백을 제거한다")
	void normalizesSourceText() {
		assertThat(SourceValues.trimToNull(null)).isNull();
		assertThat(SourceValues.trimToNull(" ")).isNull();
		assertThat(SourceValues.trimToNull(" 국민임대 ")).isEqualTo("국민임대");
	}

	@Test
	@DisplayName("지원하는 두 날짜 형식을 변환한다")
	void convertsSupportedDateFormats() {
		assertThat(SourceValues.toDate("20260907")).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(SourceValues.toDate("2026.09.07")).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(SourceValues.toDate("2026-09-07")).isNull();
		assertThat(SourceValues.toDate("20260230")).isNull();
		assertThat(SourceValues.toDate(" ")).isNull();
	}

	@Test
	@DisplayName("숫자 주변의 구분자와 단위를 제거해 정수로 변환한다")
	void convertsSourceInteger() {
		assertThat(SourceValues.toInt("1,000호")).isEqualTo(1_000);
		assertThat(SourceValues.toInt("없음")).isNull();
		assertThat(SourceValues.toInt("999999999999999999999")).isNull();
		assertThat(SourceValues.toInt(" ")).isNull();
	}

	@Test
	@DisplayName("금액과 면적 문자열을 각각 정수와 소수로 변환한다")
	void convertsSourceNumbers() {
		assertThat(SourceValues.toLong("37,224,000원")).isEqualTo(37_224_000L);
		assertThat(SourceValues.toLong("공고문 참조")).isNull();
		assertThat(SourceValues.toDecimal("39.9541㎡")).isEqualByComparingTo(new BigDecimal("39.9541"));
		assertThat(SourceValues.toDecimal("없음")).isNull();
	}

	@Test
	@DisplayName("지원하는 연월 형식을 변환한다")
	void convertsSupportedYearMonthFormats() {
		assertThat(SourceValues.toYearMonth("202610")).isEqualTo(YearMonth.of(2026, 10));
		assertThat(SourceValues.toYearMonth("2026.10")).isEqualTo(YearMonth.of(2026, 10));
		assertThat(SourceValues.toYearMonth("202613")).isNull();
	}

}
