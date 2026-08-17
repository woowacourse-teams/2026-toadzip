package com.toadzip.backend.housing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaseRentTermsTest {

	@Test
	void comparesEveryAmount() {
		BaseRentTerms terms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);
		BaseRentTerms sameTerms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);

		assertThat(BaseRentTerms.sameValues(terms, sameTerms)).isTrue();
		assertThat(BaseRentTerms.sameValues(terms, new BaseRentTerms(11_000_000L, 100_000L, 3_000_000L))).isFalse();
		assertThat(BaseRentTerms.sameValues(terms, new BaseRentTerms(10_000_000L, 120_000L, 3_000_000L))).isFalse();
		assertThat(BaseRentTerms.sameValues(terms, new BaseRentTerms(10_000_000L, 100_000L, 4_000_000L))).isFalse();
	}

	@Test
	void treatsTwoNullTermsAsSame() {
		assertThat(BaseRentTerms.sameValues(null, null)).isTrue();
	}

	@Test
	void treatsOneNullTermAsDifferent() {
		BaseRentTerms terms = new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L);

		assertThat(BaseRentTerms.sameValues(terms, null)).isFalse();
	}

}
