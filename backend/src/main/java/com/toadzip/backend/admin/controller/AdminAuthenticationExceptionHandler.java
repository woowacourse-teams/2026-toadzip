package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.service.AdminLoginAttemptLimitExceededException;
import com.toadzip.backend.admin.service.InvalidAdminCredentialsException;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminAuthenticationController.class)
public class AdminAuthenticationExceptionHandler {

    @ExceptionHandler(InvalidAdminCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAdminCredentials(HttpServletRequest request) {
        return response(
                HttpStatus.UNAUTHORIZED,
                "INVALID_ADMIN_CREDENTIALS",
                "관리자 로그인 정보가 올바르지 않습니다.",
                request
        );
    }

    @ExceptionHandler(AdminLoginAttemptLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleLoginAttemptLimitExceeded(HttpServletRequest request) {
        return response(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_ATTEMPTS_EXCEEDED",
                "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                request
        );
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
