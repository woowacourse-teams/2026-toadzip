package com.toadzip.backend.ingest.myhome;

import java.util.regex.Pattern;

public record MyHomeRegion(String provinceCode, String districtCode, String provinceName, String districtName) {

	private static final Pattern PROVINCE_CODE = Pattern.compile("\\d{2}");

	private static final Pattern DISTRICT_CODE = Pattern.compile("\\d{3}");

	public MyHomeRegion {
		if (provinceCode == null || !PROVINCE_CODE.matcher(provinceCode).matches()) {
			throw new IllegalArgumentException("시도 코드는 두 자리 숫자여야 합니다.");
		}
		if (districtCode == null || !DISTRICT_CODE.matcher(districtCode).matches()) {
			throw new IllegalArgumentException("시군구 코드는 세 자리 숫자여야 합니다.");
		}
		if (provinceName == null || provinceName.isBlank() || districtName == null || districtName.isBlank()) {
			throw new IllegalArgumentException("시도명과 시군구명은 필수입니다.");
		}
	}

	public String fullCode() {
		return provinceCode + districtCode;
	}

}
