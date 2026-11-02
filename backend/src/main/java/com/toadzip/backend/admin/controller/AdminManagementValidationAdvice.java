package com.toadzip.backend.admin.controller;

import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
// 대상 컨트롤러는 @AdminManagementApi로 표시한다. admin이 각 기능의 컨트롤러를 알지 않게 한다.
@RestControllerAdvice(annotations = AdminManagementApi.class)
public class AdminManagementValidationAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
    }
}
