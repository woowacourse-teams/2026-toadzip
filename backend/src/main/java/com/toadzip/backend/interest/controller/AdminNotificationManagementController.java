package com.toadzip.backend.interest.controller;

import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.interest.domain.AdminNotificationStatus;
import com.toadzip.backend.interest.domain.AdminNotificationReview;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.AdminNotificationFilter;
import com.toadzip.backend.interest.dto.AdminNotificationPage;
import com.toadzip.backend.interest.dto.AdminNotificationTargets;
import com.toadzip.backend.interest.dto.AdminNotificationTrend;
import com.toadzip.backend.interest.service.AdminNotificationQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminNotificationManagementController {

    private final AdminNotificationQueryService service;

    @GetMapping("/api/admin/notification-subscriptions")
    public ResponseEntity<ApiResponse<AdminNotificationPage>> search(
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(required = false) @Min(1) Long userId,
            @RequestParam(required = false) NotificationTargetType targetType,
            @RequestParam(defaultValue = "") @Pattern(regexp = "^$|[0-9]{1,19}") String targetId,
            @RequestParam(defaultValue = "ACTIVE") AdminNotificationStatus status,
            @RequestParam(defaultValue = "ALL") AdminNotificationReview review,
            @RequestParam(defaultValue = "false") boolean groupTarget,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(new AdminNotificationFilter(keyword, userId, targetType, targetId, status, review, groupTarget),
                page, size);
    }

    @GetMapping("/api/admin/users/{userId}/notification-subscriptions")
    public ResponseEntity<ApiResponse<AdminNotificationPage>> member(
            @PathVariable @Min(1) long userId,
            @RequestParam(defaultValue = "ALL") AdminNotificationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(new AdminNotificationFilter("", userId, null, "", status, AdminNotificationReview.ALL, false),
                page, size);
    }

    @GetMapping("/api/admin/notification-subscriptions/targets")
    public ResponseEntity<ApiResponse<AdminNotificationTargets>> targets(
            @RequestParam(required = false) NotificationTargetType targetType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(service.targets(targetType, page, size)));
    }

    @GetMapping("/api/admin/notification-subscriptions/trend")
    public ResponseEntity<ApiResponse<AdminNotificationTrend>> trend(
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(service.trend(days)));
    }

    private ResponseEntity<ApiResponse<AdminNotificationPage>> response(
            AdminNotificationFilter filter, int page, int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(service.search(filter, page, size)));
    }
}
