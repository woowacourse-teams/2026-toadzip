package com.toadzip.backend.announcement.controller;

import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementCursorException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.exception.InvalidRegionCodeException;
import com.toadzip.backend.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AnnouncementExceptionAdvice {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String INVALID_REQUEST_MESSAGE = "요청 값을 확인해 주세요.";
    private static final String INVALID_CURSOR = "INVALID_CURSOR";
    private static final String INVALID_CURSOR_MESSAGE = "커서 값을 확인해 주세요.";
    private static final String INVALID_REGION_CODE = "INVALID_REGION_CODE";
    private static final String INVALID_REGION_CODE_MESSAGE = "지역 코드를 확인해 주세요.";
    private static final String ANNOUNCEMENT_NOT_FOUND = "ANNOUNCEMENT_NOT_FOUND";
    private static final String ANNOUNCEMENT_NOT_FOUND_MESSAGE = "모집 공고를 찾을 수 없습니다.";

    @ExceptionHandler(InvalidAnnouncementRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(HttpServletRequest request) {
        return badRequest(INVALID_REQUEST, INVALID_REQUEST_MESSAGE, request);
    }

    @ExceptionHandler(InvalidAnnouncementCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursor(HttpServletRequest request) {
        return badRequest(INVALID_CURSOR, INVALID_CURSOR_MESSAGE, request);
    }

    @ExceptionHandler(InvalidRegionCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRegionCode(HttpServletRequest request) {
        return badRequest(INVALID_REGION_CODE, INVALID_REGION_CODE_MESSAGE, request);
    }

    @ExceptionHandler(AnnouncementNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAnnouncementNotFound(HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                ANNOUNCEMENT_NOT_FOUND,
                ANNOUNCEMENT_NOT_FOUND_MESSAGE,
                traceIdOf(request)
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    private ResponseEntity<ErrorResponse> badRequest(
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
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
