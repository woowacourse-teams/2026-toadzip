package com.toadzip.backend.ingest.location.dto;

import java.math.BigDecimal;
import java.util.List;

/** 관리자 주소 검색 결과 한 건. 단지 등록 요청의 주소 값과 같은 모양이며, 좌표를 못 구하면 위도·경도가 비어 있다. */
public record AddressCandidate(
        String roadAddress,
        String jibunAddress,
        String pnu,
        String legalDongCode,
        String provinceCode,
        String cityCountyDistrictCode,
        BigDecimal latitude,
        BigDecimal longitude,
        /** 검색어가 이 후보와 같은 도로명주소·지번을 가리킨다. 이런 후보가 하나뿐이면 관리자 화면이 고르지 않고 바로 채운다. */
        boolean matchesKeyword,
        /** 같은 필지(PNU)이거나 같은 (시도, 도로명, 건물번호)인 휴지통 밖 단지. 저장된 주소 표기가 달라도 찾는다. */
        List<RegisteredComplex> registeredComplexes
) {
}
