package com.toadzip.backend.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주택 단지의 주소 값 객체.
 *
 * <p>
 * 유효한 PNU의 앞 10자리로 법정동 코드를 계산한다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

	private static final int PNU_LENGTH = 19;

	private static final int LEGAL_DONG_CODE_LENGTH = 10;

	@Column(name = "road_address", nullable = false)
	private String roadAddress;

	@Column(name = "legal_dong_code", length = LEGAL_DONG_CODE_LENGTH)
	private String legalDongCode;

	/**
	 * 필지고유번호 19자리.
	 */
	@Column(name = "pnu", length = PNU_LENGTH)
	private String pnu;

	@Column(name = "province_code", length = 10)
	private String provinceCode;

	@Column(name = "province_name", length = 30)
	private String provinceName;

	@Column(name = "district_code", length = 10)
	private String districtCode;

	@Column(name = "district_name", length = 30)
	private String districtName;

	public Address(String roadAddress, String pnu, String provinceCode, String provinceName, String districtCode,
			String districtName) {
		if (roadAddress == null || roadAddress.isBlank()) {
			throw new IllegalArgumentException("도로명주소는 필수입니다.");
		}
		this.roadAddress = roadAddress.strip();
		this.pnu = normalizePnu(pnu);
		this.legalDongCode = legalDongCodeOf(this.pnu);
		this.provinceCode = provinceCode;
		this.provinceName = provinceName;
		this.districtCode = districtCode;
		this.districtName = districtName;
	}

	/**
	 * 앞뒤 공백을 제거한 19자리 숫자 PNU만 반환한다.
	 */
	public static String normalizePnu(String raw) {
		if (raw == null) {
			return null;
		}
		String stripped = raw.strip();
		boolean valid = stripped.length() == PNU_LENGTH
				&& stripped.chars().allMatch(digit -> digit >= '0' && digit <= '9');
		if (!valid) {
			return null;
		}
		return stripped;
	}

	private static String legalDongCodeOf(String pnu) {
		if (pnu == null) {
			return null;
		}
		return pnu.substring(0, LEGAL_DONG_CODE_LENGTH);
	}

}
