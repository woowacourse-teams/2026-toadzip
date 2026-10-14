package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.dto.MemberNotificationSettingsResponse;
import com.toadzip.backend.interest.dto.NotificationSettingsRequest;
import com.toadzip.backend.interest.dto.NotificationSettingsResponse;
import com.toadzip.backend.interest.service.NotificationSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification-subscriptions")
@RequiredArgsConstructor
public class NotificationSubscriptionController {

    private final NotificationSettingsService service;

    @GetMapping("/me")
    public ResponseEntity<MemberNotificationSettingsResponse> currentUser(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.current(Long.parseLong(authentication.getName())));
    }

    @PostMapping("/me")
    public ResponseEntity<NotificationSettingsResponse> updateCurrentUser(
            @Valid @RequestBody NotificationSettingsRequest request,
            Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.change(Long.parseLong(authentication.getName()), request));
    }
}
