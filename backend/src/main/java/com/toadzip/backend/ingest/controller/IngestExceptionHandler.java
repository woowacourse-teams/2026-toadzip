package com.toadzip.backend.ingest.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.toadzip.backend.global.error.ApiErrorResponse;
import com.toadzip.backend.ingest.dto.InvalidIngestRequestException;

@RestControllerAdvice(basePackages = "com.toadzip.backend.ingest.controller")
public class IngestExceptionHandler {

    private static final String INVALID_INGEST_REQUEST = "INVALID_INGEST_REQUEST";

    @ExceptionHandler(InvalidIngestRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidIngestRequest(
            InvalidIngestRequestException exception,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                INVALID_INGEST_REQUEST,
                exception.getMessage(),
                traceIdOf(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                INVALID_INGEST_REQUEST,
                invalidRequestMessage(exception),
                traceIdOf(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    private String invalidRequestMessage(Exception exception) {
        if (exception instanceof MethodArgumentTypeMismatchException mismatchException) {
            return "요청 파라미터 형식이 올바르지 않습니다: " + mismatchException.getName();
        }
        if (exception instanceof MissingServletRequestParameterException missingException) {
            return "필수 요청 파라미터가 없습니다: " + missingException.getParameterName();
        }
        return "요청 파라미터가 허용 범위를 벗어났습니다.";
    }

    private String traceIdOf(HttpServletRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }
}
