package com.toadzip.backend.ingest;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConstructionRentalPolicyTest {

	private final ConstructionRentalPolicy policy = new ConstructionRentalPolicy();

	@Test
	@DisplayName("건설형 공공임대 유형만 허용한다")
	void allowsConstructionRentalTypes() {
		List<String> allowed = List.of("영구임대", "국민임대", "행복주택", "통합공공임대", "5년임대", "10년임대", "50년임대");

		assertThat(allowed).allSatisfy(label -> assertThat(policy.rejectSupplyType(label)).isEmpty());
		assertThat(policy.rejectSupplyType(" 국민임대 ")).isEmpty();
	}

	@Test
	@DisplayName("알려진 비지원 임대 유형은 비지원 사유로 제외한다")
	void rejectsUnsupportedTypes() {
		assertThat(policy.rejectSupplyType("매입임대")).contains(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
		assertThat(policy.rejectSupplyType("전세임대")).contains(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
		assertThat(policy.rejectSupplyType("장기전세")).contains(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
	}

	@Test
	@DisplayName("비어 있거나 처음 보는 임대 유형은 알 수 없는 유형으로 제외한다")
	void rejectsUnknownTypes() {
		assertThat(policy.rejectSupplyType(null)).contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
		assertThat(policy.rejectSupplyType(" ")).contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
		assertThat(policy.rejectSupplyType("청년안심주택")).contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
	}

	@Test
	@DisplayName("아파트이거나 준공일이 유효하면 건설형 주택으로 판단한다")
	void detectsConstructionHousingEvidence() {
		assertThat(policy.hasConstructionEvidence("아파트", null)).isTrue();
		assertThat(policy.hasConstructionEvidence("다세대주택", "20201230")).isTrue();
		assertThat(policy.hasConstructionEvidence("다세대주택", "")).isFalse();
		assertThat(policy.hasConstructionEvidence("다세대주택", "잘못된 날짜")).isFalse();
	}

}
