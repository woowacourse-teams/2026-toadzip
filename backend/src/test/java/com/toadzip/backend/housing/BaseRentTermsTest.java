package com.toadzip.backend.housing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseRentTermsTest {

	@Test
	@DisplayName("모든 기본 임대 조건 금액을 값으로 비교한다")
	void comparesEveryAmount() {
		BaseRentTerms terms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);
		BaseRentTerms sameTerms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);

		assertThat(BaseRentTerms.sameValues(terms, sameTerms)).isTrue();
		assertThat(BaseRentTerms.sameValues(terms, new BaseRentTerms(11_000_000L, 100_000L, 3_000_000L))).isFalse();
		assertThat(BaseRentTerms.sameValues(terms, new BaseRentTerms(10_000_000L, 120_000L, 3_000_000L))).isFalse();
		assertThat(BaseRentTerms.sameValues(terms, new BaseRentTerms(10_000_000L, 100_000L, 4_000_000L))).isFalse();
	}

	@Test
	@DisplayName("두 임대 조건이 모두 null이면 같다")
	void treatsTwoNullTermsAsSame() {
		assertThat(BaseRentTerms.sameValues(null, null)).isTrue();
	}

	@Test
	@DisplayName("한쪽 임대 조건만 null이면 다르다")
	void treatsOneNullTermAsDifferent() {
		BaseRentTerms terms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);

		assertThat(BaseRentTerms.sameValues(terms, null)).isFalse();
	}

}
