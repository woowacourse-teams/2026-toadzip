package com.toadzip.backend.streetview.controller;

import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StreetViewPolicyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StreetViewPolicyExceptionAdvice {
    @ExceptionHandler(AdminDataConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(AdminDataConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse("ADMIN_DATA_CONFLICT", exception.getMessage(),
                RequestTraceIdResolver.resolve(request)));
    }
}
