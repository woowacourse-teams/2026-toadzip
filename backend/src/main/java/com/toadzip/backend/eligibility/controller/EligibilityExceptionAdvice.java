package com.toadzip.backend.eligibility.controller;

import com.toadzip.backend.eligibility.exception.EligibilityConflictException;
import com.toadzip.backend.eligibility.exception.EligibilityNotFoundException;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;
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
public class EligibilityExceptionAdvice {
    @ExceptionHandler(InvalidEligibilityException.class)
    public ResponseEntity<ErrorResponse> invalid(
            InvalidEligibilityException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "BASIC_COMPARISON_INVALID",
                                exception.getMessage(),
                                RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(EligibilityConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(
            EligibilityConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new ErrorResponse(
                                "BASIC_COMPARISON_CONFLICT",
                                exception.getMessage(),
                                RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(EligibilityNotFoundException.class)
    public ResponseEntity<ErrorResponse> missing(
            EligibilityNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        new ErrorResponse(
                                "BASIC_COMPARISON_NOT_FOUND",
                                exception.getMessage(),
                                RequestTraceIdResolver.resolve(request)));
    }
}
