package com.toadzip.backend.interest.controller;

import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.service.NotificationInterestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notification-interest-events")
@RequiredArgsConstructor
public class NotificationInterestController {

    private final NotificationInterestService service;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@Valid @RequestBody NotificationInterestRequest request) {
        service.record(request);
    }
}
