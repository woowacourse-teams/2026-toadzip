package com.toadzip.backend.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RentTermsTest {

	@Test
	@DisplayName("모든 임대 조건 금액을 값으로 비교한다")
	void comparesEveryAmountByValue() {
		RentTerms terms = new RentTerms(10_000_000L, 1_000_000L, 9_000_000L, 100_000L);

		assertThat(RentTerms.sameValues(terms, new RentTerms(10_000_000L, 1_000_000L, 9_000_000L, 100_000L))).isTrue();
		assertThat(RentTerms.sameValues(terms, new RentTerms(11_000_000L, 1_000_000L, 9_000_000L, 100_000L))).isFalse();
		assertThat(RentTerms.sameValues(terms, new RentTerms(10_000_000L, 2_000_000L, 9_000_000L, 100_000L))).isFalse();
		assertThat(RentTerms.sameValues(terms, new RentTerms(10_000_000L, 1_000_000L, 8_000_000L, 100_000L))).isFalse();
		assertThat(RentTerms.sameValues(terms, new RentTerms(10_000_000L, 1_000_000L, 9_000_000L, 200_000L))).isFalse();
	}

	@Test
	@DisplayName("null 임대 조건을 안전하게 비교한다")
	void comparesNullValuesSafely() {
		RentTerms terms = new RentTerms(10_000_000L, 1_000_000L, 9_000_000L, 100_000L);

		assertThat(RentTerms.sameValues(null, null)).isTrue();
		assertThat(RentTerms.sameValues(terms, null)).isFalse();
		assertThat(RentTerms.sameValues(null, terms)).isFalse();
	}

}
