package com.toadzip.backend.notification.controller;

import com.toadzip.backend.notification.dto.NotificationDeviceBinding;
import com.toadzip.backend.notification.dto.NotificationPushDeviceRequest;
import com.toadzip.backend.notification.dto.NotificationPushDeviceResponse;
import com.toadzip.backend.notification.dto.NotificationPushDisableRequest;
import com.toadzip.backend.notification.service.NotificationPushDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@RequestMapping("/api/v1/notification-push-devices")
public class NotificationPushDeviceController {
    private final NotificationPushDeviceService service;
    private final boolean secureCookie;

    public NotificationPushDeviceController(NotificationPushDeviceService service,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        this.service = service;
        this.secureCookie = secureCookie;
    }

    @PostMapping
    public ResponseEntity<NotificationPushDeviceResponse> register(Authentication authentication,
            @CookieValue(name = "notification-device", required = false) UUID installation,
            @Valid @RequestBody NotificationPushDeviceRequest body, HttpServletRequest request) {
        UUID id = installation;
        if (id == null) {
            id = UUID.randomUUID();
        }
        var session = request.getSession();
        long userId = Long.parseLong(authentication.getName());
        synchronized (WebUtils.getSessionMutex(session)) {
            session.getAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE);
            var result = service.register(userId, id, body);
            session.setAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE,
                    new NotificationDeviceBinding(id, userId, Long.parseLong(result.bindingVersion())));
            String cookie = ResponseCookie.from("notification-device", id.toString()).httpOnly(true)
                    .secure(secureCookie).sameSite("Lax").path("/").maxAge(Duration.ofDays(365)).build().toString();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE, cookie)
                    .body(result);
        }
    }

    @GetMapping("/current")
    public ResponseEntity<NotificationPushDeviceResponse> current(Authentication authentication,
            @CookieValue(name = "notification-device", required = false) UUID installation, HttpServletRequest request) {
        long userId = Long.parseLong(authentication.getName());
        var session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            var existing = session.getAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE);
            var result = service.current(userId, installation);
            if (result.enabled() && existing == null) {
                session.setAttribute(NotificationDeviceBinding.SESSION_ATTRIBUTE,
                        new NotificationDeviceBinding(installation, userId, Long.parseLong(result.bindingVersion())));
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        }
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> disable(Authentication authentication,
            @CookieValue(name = "notification-device", required = false) UUID installation,
            @Valid @RequestBody NotificationPushDisableRequest body) {
        service.disable(Long.parseLong(authentication.getName()), installation, body);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
