package com.toadzip.backend.housing;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HousingComplexTest {

	@Test
	@DisplayName("필수 문자열의 앞뒤 공백을 제거한다")
	void normalizesRequiredTextValues() {
		HousingComplex complex = new HousingComplex("대전 산내", address(), "complex-1", " 국민임대 ", 100, " LH대전충남 ");

		assertThat(complex.getSupplyTypeName()).isEqualTo("국민임대");
		assertThat(complex.getSupplyInstitutionName()).isEqualTo("LH대전충남");
	}

	@Test
	@DisplayName("필수 문자열이 비어 있으면 생성할 수 없다")
	void rejectsBlankRequiredTextValues() {
		assertThatThrownBy(() -> new HousingComplex("대전 산내", address(), "complex-1", " ", 100, "LH"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("supplyTypeName는 필수입니다.");
		assertThatThrownBy(() -> new HousingComplex("대전 산내", address(), "complex-1", "국민임대", 100, " "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("supplyInstitutionName는 필수입니다.");
	}

	@Test
	@DisplayName("카탈로그 상세 정보가 변경된 경우에만 갱신한다")
	void updatesCatalogDetailsOnlyWhenValuesChange() {
		HousingComplex complex = complex();
		CatalogDetails details = new CatalogDetails(LocalDate.of(2020, 3, 15), "지역난방", 120, "복도식", "전체동 설치", "아파트");

		assertThat(complex.updateCatalogDetails(details)).isTrue();
		assertThat(complex.getCompletionYear()).isEqualTo(2020);
		assertThat(complex.currentCatalogDetails()).isEqualTo(details);
		assertThat(complex.updateCatalogDetails(details)).isFalse();
	}

	@Test
	@DisplayName("공급 정보가 변경된 경우에만 갱신한다")
	void updatesSupplyDetailsOnlyWhenValuesChange() {
		HousingComplex complex = complex();

		assertThat(complex.updateSupplyDetails(100, " LH대전충남 ")).isFalse();
		assertThat(complex.updateSupplyDetails(120, " LH대전충남지역본부 ")).isTrue();
		assertThat(complex.getUnitCount()).isEqualTo(120);
		assertThat(complex.getSupplyInstitutionName()).isEqualTo("LH대전충남지역본부");
	}

	private HousingComplex complex() {
		return new HousingComplex("대전 산내", address(), "complex-1", "국민임대", 100, "LH대전충남");
	}

	private Address address() {
		return new Address("대전광역시 동구 산내로 123", "3011013600101900001", "30", "대전광역시", "110", "동구");
	}

}
