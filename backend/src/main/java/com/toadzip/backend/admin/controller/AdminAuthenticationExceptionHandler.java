package com.toadzip.backend.admin.controller;

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
}
