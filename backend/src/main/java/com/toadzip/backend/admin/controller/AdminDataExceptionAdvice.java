package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@RestControllerAdvice
@Order(0)
public class AdminDataExceptionAdvice {
    @ExceptionHandler({AdminDataConflictException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException exception) {
        String message = "데이터가 변경되었습니다. 새로 조회한 뒤 다시 시도해 주세요.";
        if (exception instanceof AdminDataConflictException) { message = exception.getMessage(); }
        return ResponseEntity.status(409).body(Map.of("code", "ADMIN_DATA_CONFLICT", "message", message));
    }
}
