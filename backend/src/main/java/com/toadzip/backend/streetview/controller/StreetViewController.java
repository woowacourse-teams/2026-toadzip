package com.toadzip.backend.streetview.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.streetview.dto.StreetViewResponse;
import com.toadzip.backend.streetview.service.StreetViewQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StreetViewController {
    private final StreetViewQueryService service;

    public StreetViewController(StreetViewQueryService service) {
        this.service = service;
    }

    @Operation(summary = "단지 거리뷰 실행 정보 조회", description = "정책 허용 여부와 WGS84 출입구 좌표를 반환합니다. "
            + "실제 촬영 데이터의 존재나 외관 가시성을 보장하지 않으며 pan 계산은 브라우저 SDK가 수행합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "실행 정보"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "COMPLEX_NOT_FOUND",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "STREET_VIEW_POLICY_UNAVAILABLE",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/v1/complexes/{complexId}/street-view")
    public ResponseEntity<ApiResponse<StreetViewResponse>> get(@PathVariable long complexId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(service.get(complexId)));
    }
}
