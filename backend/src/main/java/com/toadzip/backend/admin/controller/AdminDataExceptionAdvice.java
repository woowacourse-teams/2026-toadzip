package com.toadzip.backend.admin.controller;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.exception.AdminDataStaleException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@RestControllerAdvice
@Order(0)
public class AdminDataExceptionAdvice {
    @ExceptionHandler({AdminDataConflictException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException exception) {
        // 다시 조회하면 풀리는 충돌과 규칙 위반을 화면이 구분해 안내하도록 코드를 나눈다.
        if (exception instanceof ObjectOptimisticLockingFailureException) {
            return response("ADMIN_DATA_STALE", "데이터가 변경되었습니다. 새로 조회한 뒤 다시 시도해 주세요.");
        }
        if (exception instanceof AdminDataStaleException) {
            return response("ADMIN_DATA_STALE", exception.getMessage());
        }
        return response("ADMIN_DATA_CONFLICT", exception.getMessage());
    }

    private ResponseEntity<Map<String, String>> response(String code, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", code, "message", message));
    }
}
