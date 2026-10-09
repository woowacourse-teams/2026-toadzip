package com.toadzip.backend.privacy.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.privacy.exception.PrivacyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PrivacyExceptionAdvice {

    @ExceptionHandler(PrivacyException.class)
    public ResponseEntity<ErrorResponse> handle(PrivacyException exception, HttpServletRequest request) {
        int status = switch (exception.getCode()) {
            case "INVALID_PRIVACY_CHOICE" -> 400;
            case "UNAUTHORIZED" -> 401;
            case "FORBIDDEN", "ANALYTICS_CONSENT_REQUIRED" -> 403;
            case "PRIVACY_NOTICE_NOT_FOUND" -> 404;
            default -> 409;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(
                exception.getCode(), exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
