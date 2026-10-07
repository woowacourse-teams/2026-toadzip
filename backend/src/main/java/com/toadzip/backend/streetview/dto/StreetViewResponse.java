package com.toadzip.backend.streetview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record StreetViewResponse(
        @Schema(description = "alias를 해석한 실제 단지 ID") long complexId,
        @Schema(allowableValues = "NAVER") String provider,
        @Schema(description = "정책상 실행 허용 여부. 촬영 데이터 존재나 외관 가시성을 보증하지 않음") boolean enabled,
        @Schema(nullable = true, allowableValues = {"POLICY_DISABLED", "INVALID_COORDINATES"}) String disabledReason,
        @Schema(description = "전체 정책 버전. 단지 좌표의 변경 버전이 아님") long policyRevision,
        @Schema(nullable = true, description = "비활성 상태에서는 null") StreetViewInitialization initialization
) {
    public record StreetViewInitialization(StreetViewPosition searchPosition, StreetViewPosition lookAtPosition,
            int tilt, int fov) {
    }

    public record StreetViewPosition(
            @Schema(description = "WGS84 위도, 도 단위", minimum = "-90", maximum = "90") BigDecimal latitude,
            @Schema(description = "WGS84 경도, 도 단위", minimum = "-180", maximum = "180") BigDecimal longitude
    ) {
    }
}
