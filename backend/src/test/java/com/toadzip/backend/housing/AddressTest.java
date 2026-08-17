package com.toadzip.backend.housing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressTest {

	@Test
	@DisplayName("유효한 PNU에서 법정동 코드를 추출한다")
	void derivesLegalDongCodeFromValidPnu() {
		Address address = new Address(" 대전광역시 동구 산내로 123 ", " 3011013600101900001 ", "30", "대전광역시", "110", "동구");

		assertThat(address.getRoadAddress()).isEqualTo("대전광역시 동구 산내로 123");
		assertThat(address.getPnu()).isEqualTo("3011013600101900001");
		assertThat(address.getLegalDongCode()).isEqualTo("3011013600");
	}

	@Test
	@DisplayName("유효하지 않은 PNU는 버린다")
	void discardsInvalidPnu() {
		Address address = new Address("대전광역시 동구 산내로 123", "301101360010190000A", "30", "대전광역시", "110", "동구");

		assertThat(address.getPnu()).isNull();
		assertThat(address.getLegalDongCode()).isNull();
	}

	@Test
	@DisplayName("ASCII 숫자가 아닌 PNU는 버린다")
	void discardsNonAsciiDigits() {
		Address address = new Address("대전광역시 동구 산내로 123", "１".repeat(19), "30", "대전광역시", "110", "동구");

		assertThat(address.getPnu()).isNull();
		assertThat(address.getLegalDongCode()).isNull();
	}

	@Test
	@DisplayName("길이가 올바르지 않은 PNU는 버린다")
	void discardsPnuWithWrongLength() {
		Address shortPnu = new Address("대전광역시 동구 산내로 123", "1".repeat(18), "30", "대전광역시", "110", "동구");
		Address longPnu = new Address("대전광역시 동구 산내로 123", "1".repeat(20), "30", "대전광역시", "110", "동구");

		assertThat(shortPnu.getPnu()).isNull();
		assertThat(longPnu.getPnu()).isNull();
	}

	@Test
	@DisplayName("도로명 주소가 비어 있으면 생성할 수 없다")
	void rejectsBlankRoadAddress() {
		assertThatThrownBy(() -> new Address(" ", null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("도로명주소는 필수입니다.");
	}

}
