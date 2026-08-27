package com.toadzip.backend.ingest.domain;

public record RoadAddressCandidate(
        String roadAddress,
        String roadAddressWithoutReference,
        JusoAddressCode addressCode
) {

    public RoadAddressCandidate {
        if (roadAddress == null || roadAddress.isBlank()) {
            throw new IllegalArgumentException("검색된 도로명주소는 필수입니다.");
        }
        if (addressCode == null) {
            throw new IllegalArgumentException("검색된 주소 코드는 필수입니다.");
        }
    }

    public boolean matches(NormalizedRoadAddress source) {
        return source.matches(roadAddress) || source.matches(roadAddressWithoutReference);
    }
}
