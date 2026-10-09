package com.toadzip.backend.interest.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.exception.NotificationInterestConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NotificationInterestExceptionAdvice {

    @ExceptionHandler(NotificationInterestConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            NotificationInterestConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "NOTIFICATION_INTEREST_CONFLICT", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(InvalidNotificationInterestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTarget(
            InvalidNotificationInterestException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                "INVALID_NOTIFICATION_INTEREST", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
