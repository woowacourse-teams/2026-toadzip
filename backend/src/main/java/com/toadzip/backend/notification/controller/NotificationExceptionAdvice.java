package com.toadzip.backend.notification.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.notification.exception.InvalidNotificationRequestException;
import com.toadzip.backend.notification.exception.NotificationNotFoundException;
import com.toadzip.backend.notification.exception.NotificationDeviceConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NotificationExceptionAdvice {
    @ExceptionHandler(NotificationDeviceConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(NotificationDeviceConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(new ErrorResponse(
                "NOTIFICATION_DEVICE_CONFLICT", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(InvalidNotificationRequestException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidNotificationRequestException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new ErrorResponse(
                "INVALID_NOTIFICATION_REQUEST", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> missing(NotificationNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).body(new ErrorResponse(
                "NOTIFICATION_NOT_FOUND", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
