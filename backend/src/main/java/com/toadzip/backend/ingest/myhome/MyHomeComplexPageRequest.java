package com.toadzip.backend.ingest.myhome;

public record MyHomeComplexPageRequest(String provinceCode, String districtCode, int page, int pageSize) {

	public MyHomeComplexPageRequest {
		if (page < 1 || pageSize < 1) {
			throw new IllegalArgumentException("페이지 번호와 크기는 양수여야 합니다.");
		}
	}

}
