package com.toadzip.backend.ingest.location.domain;

/** 주소 검색 API가 돌려준 주소 한 건. 좌표 조회와 PNU 계산에 필요한 식별값을 함께 담는다. */
public record JusoAddress(
        String roadAddress,
        String jibunAddress,
        String legalDongCode,
        String roadNameCode,
        boolean underground,
        int buildingMainNumber,
        int buildingSubNumber,
        boolean mountain,
        int lotMainNumber,
        int lotSubNumber
) {

    public String pnu() {
        return LandParcelNumber.of(legalDongCode, mountain, lotMainNumber, lotSubNumber);
    }
}
