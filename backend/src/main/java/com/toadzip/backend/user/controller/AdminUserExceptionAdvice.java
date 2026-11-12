package com.toadzip.backend.user.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.user.exception.AdminUserNotFoundException;
import com.toadzip.backend.user.exception.LastAdminRevokeException;
import com.toadzip.backend.user.exception.SelfAdminRevokeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AdminUserExceptionAdvice {

    @ExceptionHandler(AdminUserNotFoundException.class)
    public ResponseEntity<ErrorResponse> userNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                "USER_NOT_FOUND", "회원을 찾을 수 없습니다.", RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(SelfAdminRevokeException.class)
    public ResponseEntity<ErrorResponse> selfRevoke(SelfAdminRevokeException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "SELF_ADMIN_REVOKE", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(LastAdminRevokeException.class)
    public ResponseEntity<ErrorResponse> lastAdminRevoke(LastAdminRevokeException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "LAST_ADMIN_REVOKE", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
