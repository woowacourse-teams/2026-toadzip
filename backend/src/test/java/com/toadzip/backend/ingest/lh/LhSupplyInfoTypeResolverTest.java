package com.toadzip.backend.ingest.lh;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LhSupplyInfoTypeResolverTest {

	@Test
	@DisplayName("건설임대 공급유형을 LH 공급정보 코드로 변환한다")
	void resolvesConstructionRentalType() {
		LhSupplyInfoTypeResolver resolver = new LhSupplyInfoTypeResolver();

		assertThat(resolver.resolve("국민임대")).contains("062");
		assertThat(resolver.resolve("행복주택")).contains("063");
	}

	@Test
	@DisplayName("지원하지 않는 공급유형은 빈 결과를 반환한다")
	void returnsEmptyForUnsupportedType() {
		assertThat(new LhSupplyInfoTypeResolver().resolve("통합공공임대")).isEmpty();
	}
}
