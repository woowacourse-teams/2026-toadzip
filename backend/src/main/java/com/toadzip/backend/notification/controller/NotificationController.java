package com.toadzip.backend.notification.controller;

import com.toadzip.backend.notification.dto.NotificationPageResponse;
import com.toadzip.backend.notification.dto.NotificationReadAllRequest;
import com.toadzip.backend.notification.dto.NotificationUnreadCountResponse;
import com.toadzip.backend.notification.service.NotificationQueryService;
import com.toadzip.backend.notification.service.NotificationReadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationQueryService queries;
    private final NotificationReadService reads;

    @GetMapping
    public ResponseEntity<NotificationPageResponse> list(Authentication authentication,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.findForUser(Long.parseLong(authentication.getName()), cursor, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<NotificationUnreadCountResponse> count(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.countUnread(Long.parseLong(authentication.getName())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(Authentication authentication, @PathVariable long id) {
        reads.markRead(Long.parseLong(authentication.getName()), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(Authentication authentication, @Valid @RequestBody NotificationReadAllRequest request) {
        reads.markAllRead(Long.parseLong(authentication.getName()), request.readThrough());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
