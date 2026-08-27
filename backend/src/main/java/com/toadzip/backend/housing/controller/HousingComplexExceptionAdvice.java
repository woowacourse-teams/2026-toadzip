package com.toadzip.backend.housing.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidMapBoundsException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class HousingComplexExceptionAdvice {

    private static final String INVALID_MAP_BOUNDS = "INVALID_MAP_BOUNDS";

    private static final String INVALID_CURSOR = "INVALID_CURSOR";

    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    private static final String COMPLEX_NOT_FOUND = "COMPLEX_NOT_FOUND";

    @ExceptionHandler(InvalidMapBoundsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMapBounds(
            InvalidMapBoundsException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, INVALID_MAP_BOUNDS, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidComplexCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidComplexCursor(
            InvalidComplexCursorException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, INVALID_CURSOR, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidComplexRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidComplexRequest(
            InvalidComplexRequestException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, INVALID_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(HousingComplexNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleHousingComplexNotFound(
            HousingComplexNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, COMPLEX_NOT_FOUND, exception.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, traceIdOf(request)));
    }

    private String traceIdOf(HttpServletRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }
}
