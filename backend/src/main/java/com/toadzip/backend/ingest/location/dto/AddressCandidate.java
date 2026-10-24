package com.toadzip.backend.ingest.location.dto;

import java.math.BigDecimal;

/** 관리자 주소 검색 결과 한 건. 단지 등록 요청의 주소 값과 같은 모양이며, 좌표를 못 구하면 위도·경도가 비어 있다. */
public record AddressCandidate(
        String roadAddress,
        String jibunAddress,
        String pnu,
        String legalDongCode,
        String provinceCode,
        String cityCountyDistrictCode,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
