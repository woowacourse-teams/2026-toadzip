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
import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IngestExceptionAdvice {

    private static final String INVALID_INGEST_REQUEST = "INVALID_INGEST_REQUEST";

    private static final String INGEST_ALREADY_RUNNING = "INGEST_ALREADY_RUNNING";

    private static final String DATA_PIPELINE_EXECUTION_NOT_FOUND =
            "DATA_PIPELINE_EXECUTION_NOT_FOUND";

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

    @ExceptionHandler(DataPipelineExecutionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDataPipelineExecutionNotFound(
            DataPipelineExecutionNotFoundException exception,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(
                DATA_PIPELINE_EXECUTION_NOT_FOUND,
                exception.getMessage(),
                traceIdOf(request)
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    private String traceIdOf(HttpServletRequest request) {
        return RequestTraceIdResolver.resolve(request);
    }
}
