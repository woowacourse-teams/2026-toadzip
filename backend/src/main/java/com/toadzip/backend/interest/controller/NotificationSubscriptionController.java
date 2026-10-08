package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationInterestResponse;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.service.NotificationInterestService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @PostMapping("/me")
    public NotificationInterestResponse updateCurrentUser(@Valid @RequestBody NotificationInterestRequest request,
            Authentication authentication) {
        if (request.eventType() != NotificationEventType.CONFIRMED
                && request.eventType() != NotificationEventType.CANCELLED) {
            throw new InvalidNotificationInterestException();
        }
        return service.record(request, Long.parseLong(authentication.getName()));
    }

    @GetMapping("/guest")
    public NotificationSubscriptionResponse guest(@RequestHeader("X-Notification-Client-Id") UUID clientId) {
        return service.findForClient(clientId);
    }
}
