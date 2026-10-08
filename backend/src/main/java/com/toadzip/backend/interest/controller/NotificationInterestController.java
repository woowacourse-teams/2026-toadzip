package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationInterestResponse;
import com.toadzip.backend.interest.service.NotificationInterestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification-interest-events")
@RequiredArgsConstructor
public class NotificationInterestController {

    private final NotificationInterestService service;

    @PostMapping
    public NotificationInterestResponse record(
            @Valid @RequestBody NotificationInterestRequest request, Authentication authentication) {
        Long userId = null;
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_USER".equals(authority.getAuthority()))) {
            userId = Long.valueOf(authentication.getName());
        }
        return service.record(request, userId);
    }
}
