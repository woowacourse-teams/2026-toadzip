package com.toadzip.backend.basiccomparison.controller;

import com.toadzip.backend.basiccomparison.exception.ComparisonConflictException;
import com.toadzip.backend.basiccomparison.exception.ComparisonNotFoundException;
import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;
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
public class ComparisonExceptionAdvice {
    @ExceptionHandler(InvalidComparisonException.class)
    public ResponseEntity<ErrorResponse> invalid(
            InvalidComparisonException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "BASIC_COMPARISON_INVALID",
                                exception.getMessage(),
                                RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(ComparisonConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(
            ComparisonConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new ErrorResponse(
                                "BASIC_COMPARISON_CONFLICT",
                                exception.getMessage(),
                                RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(ComparisonNotFoundException.class)
    public ResponseEntity<ErrorResponse> missing(
            ComparisonNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        new ErrorResponse(
                                "BASIC_COMPARISON_NOT_FOUND",
                                exception.getMessage(),
                                RequestTraceIdResolver.resolve(request)));
    }
}
