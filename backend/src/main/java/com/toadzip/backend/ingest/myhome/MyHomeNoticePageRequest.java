package com.toadzip.backend.ingest.myhome;

import java.util.Objects;

public record MyHomeNoticePageRequest(MyHomeNoticeSupplyType supplyType, int page, int pageSize) {

	public MyHomeNoticePageRequest {
		Objects.requireNonNull(supplyType, "공급유형은 필수입니다.");
		if (page < 1 || pageSize < 1) {
			throw new IllegalArgumentException("페이지 번호와 크기는 양수여야 합니다.");
		}
	}

}
