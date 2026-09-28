package com.toadzip.backend.ingest.mapping.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.ingest.mapping.exception.ComplexMergeConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ComplexMergeExceptionAdvice {

    @ExceptionHandler(ComplexMergeConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ComplexMergeConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ErrorResponse(
                "COMPLEX_MERGE_CONFLICT", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
