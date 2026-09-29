package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import com.toadzip.backend.interest.service.NotificationInterestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification-subscriptions")
@RequiredArgsConstructor
public class NotificationSubscriptionController {

    private final NotificationInterestService service;

    @GetMapping("/me")
    public NotificationSubscriptionResponse currentUser(Authentication authentication) {
        return service.findForUser(Long.parseLong(authentication.getName()));
    }
}
