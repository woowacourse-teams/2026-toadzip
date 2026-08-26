package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.service.AdminLoginAttemptLimitExceededException;
import com.toadzip.backend.admin.service.InvalidAdminCredentialsException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminAuthenticationController.class)
public class AdminAuthenticationExceptionHandler {

    @ExceptionHandler(InvalidAdminCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidAdminCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "code", "INVALID_ADMIN_CREDENTIALS",
                        "message", "관리자 로그인 정보가 올바르지 않습니다."
                ));
    }

    @ExceptionHandler(AdminLoginAttemptLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleLoginAttemptLimitExceeded() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "code", "LOGIN_ATTEMPTS_EXCEEDED",
                        "message", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."
                ));
    }
}
