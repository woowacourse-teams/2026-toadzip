package com.toadzip.backend.streetview.controller;

import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.streetview.dto.StreetViewPolicyResponse;
import com.toadzip.backend.streetview.dto.StreetViewPolicyUpdateRequest;
import com.toadzip.backend.streetview.service.StreetViewPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/street-view-policy")
public class StreetViewPolicyController {
    private final StreetViewPolicyService service;

    public StreetViewPolicyController(StreetViewPolicyService service) {
        this.service = service;
    }

    @Operation(summary = "전체 거리뷰 정책 조회", description = "ADMIN 권한 필요. 초기 정책은 비활성입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "전체 정책"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTHENTICATION_REQUIRED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ACCESS_DENIED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "STREET_VIEW_POLICY_UNAVAILABLE",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ApiResponse<StreetViewPolicyResponse> get() {
        return new ApiResponse<>(service.get());
    }

    @Operation(summary = "전체 거리뷰 정책 변경", description = "ADMIN 세션과 CSRF 토큰 필요. "
            + "현재 version을 전송하며 충돌 시 409 ADMIN_DATA_CONFLICT를 반환합니다. "
            + "enabled와 정규화한 reason이 모두 같으면 버전과 이력을 변경하지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "변경된 정책 또는 동일 요청의 현재 정책"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "INVALID_REQUEST / VALIDATION_FAILED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTHENTICATION_REQUIRED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ACCESS_DENIED: 권한 또는 CSRF 토큰 오류",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ADMIN_DATA_CONFLICT",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "STREET_VIEW_POLICY_UNAVAILABLE",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public ApiResponse<StreetViewPolicyResponse> update(@Valid @RequestBody StreetViewPolicyUpdateRequest request,
            Principal principal) {
        return new ApiResponse<>(service.update(request, principal.getName()));
    }

    @Operation(summary = "거리뷰 정책 변경 이력", description = "ADMIN 권한 필요. 페이지는 0부터, 최신순 20건입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "최신순 변경 이력 최대 20건"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "VALIDATION_FAILED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "AUTHENTICATION_REQUIRED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ACCESS_DENIED",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/changes")
    public ApiResponse<List<AdminChangeResponse>> history(@RequestParam(defaultValue = "0") int page) {
        return new ApiResponse<>(service.history(page));
    }
}
