package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.exception.AdminLoginAttemptLimitExceededException;
import com.toadzip.backend.admin.exception.InvalidAdminCredentialsException;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AdminAuthenticationExceptionAdvice {

    @ExceptionHandler(InvalidAdminCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAdminCredentials(
            InvalidAdminCredentialsException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_CREDENTIALS", exception.getMessage(), request);
    }

    @ExceptionHandler(AdminLoginAttemptLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleLoginAttemptLimitExceeded(
            AdminLoginAttemptLimitExceededException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_ATTEMPTS_EXCEEDED", exception.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(code, message, RequestTraceIdResolver.resolve(request));
        return ResponseEntity.status(status).body(errorResponse);
    }
}
