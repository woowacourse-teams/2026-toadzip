package com.toadzip.backend.notification.controller;

import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.global.response.ApiResponse;
import com.toadzip.backend.notification.dto.AdminNotificationDelivery;
import com.toadzip.backend.notification.service.AdminNotificationDeliveryQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminNotificationDeliveryController {
    private final AdminNotificationDeliveryQueryService deliveries;

    @GetMapping("/api/admin/notification-deliveries")
    public ResponseEntity<ApiResponse<AdminPage<AdminNotificationDelivery>>> search(
            @RequestParam(required = false) @Min(1) Long userId,
            @RequestParam(required = false) @Min(1) Long announcementId,
            @RequestParam(defaultValue = "") @Pattern(regexp = "|PENDING|SENDING|RETRY|SENT|SKIPPED|FAILED|NO_DELIVERY")
            String status,
            @RequestParam(defaultValue = "") @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(deliveries.search(userId, announcementId, status, keyword, page, size)));
    }
}
