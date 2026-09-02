package com.toadzip.backend.ingest.exception.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IngestExceptionAdvice {

    private static final String INVALID_INGEST_REQUEST = "INVALID_INGEST_REQUEST";

    private static final String INGEST_ALREADY_RUNNING = "INGEST_ALREADY_RUNNING";

    @ExceptionHandler(InvalidIngestRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIngestRequest(
            InvalidIngestRequestException exception,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(
                INVALID_INGEST_REQUEST,
                exception.getMessage(),
                traceIdOf(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(IngestAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleIngestAlreadyRunning(
            IngestAlreadyRunningException exception,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(
                INGEST_ALREADY_RUNNING,
                exception.getMessage(),
                traceIdOf(request)
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    private String traceIdOf(HttpServletRequest request) {
        return RequestTraceIdResolver.resolve(request);
    }
}
