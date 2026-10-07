package com.toadzip.backend.streetview.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.streetview.dto.StreetViewEventRequest;
import com.toadzip.backend.streetview.service.StreetViewEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StreetViewEventController {
    private final StreetViewEventService service;

    public StreetViewEventController(StreetViewEventService service) {
        this.service = service;
    }

    @Operation(summary = "거리뷰 최초 초기화 결과 수집", description = "익명 요청에도 CSRF 토큰 필요. "
            + "GET /api/auth/csrf의 토큰과 headerName을 사용합니다. "
            + "STARTED는 DOCUMENT/0ms/사유 없음, READY는 PANORAMA/사유 없음. "
            + "DOCUMENT_TIMEOUT은 DOCUMENT, SDK_LOAD_FAILED/SDK_AUTH_FAILED/SDK_UNAVAILABLE은 SDK, "
            + "PANORAMA_QUERY_FAILED는 PANORAMA 단계입니다. INITIALIZATION_TIMEOUT은 모든 단계에서 허용합니다. "
            + "CANCELLED는 USER_CLOSED 또는 TARGET_CHANGED. 최초 시작과 최초 종료만 집계하며 "
            + "종료가 먼저 도착해도 허용합니다. 기존 정책 버전/비활성화 이후 결과도 받습니다. "
            + "본문 최대 4096바이트. 중복 판별은 프로세스별 최초 수신 후 10분. "
            + "수집 실패는 화면을 막지 않으며 자동 재전송하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "접수 또는 중복 무시", content = @Content),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST / VALIDATION_FAILED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: CSRF 토큰 누락 또는 불일치",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404",
                    description = "COMPLEX_NOT_FOUND",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "STREET_VIEW_EVENT_ATTEMPT_CONFLICT",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "STREET_VIEW_EVENT_PAYLOAD_TOO_LARGE",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "STREET_VIEW_EVENT_RATE_LIMITED; Retry-After: 1",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "STREET_VIEW_EVENT_COLLECTION_UNAVAILABLE; Retry-After: 60",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/api/v1/street-view/events", consumes = "application/json")
    public ResponseEntity<Void> collect(@Valid @RequestBody StreetViewEventRequest request) {
        service.collect(request);
        return ResponseEntity.noContent().build();
    }
}
