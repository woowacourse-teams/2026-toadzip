package com.toadzip.backend.streetview.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.streetview.exception.InvalidStreetViewRequestException;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import com.toadzip.backend.streetview.exception.StreetViewPolicyUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StreetViewExceptionAdvice {
    @ExceptionHandler(StreetViewPolicyUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(StreetViewPolicyUnavailableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(503).body(new ErrorResponse("STREET_VIEW_POLICY_UNAVAILABLE",
                exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(StreetViewCollectionException.class)
    public ResponseEntity<ErrorResponse> collection(StreetViewCollectionException exception,
            HttpServletRequest request) {
        var response = switch (exception.getReason()) {
            case RATE_LIMIT -> ResponseEntity.status(429).header("Retry-After", "1");
            case PAYLOAD_TOO_LARGE -> ResponseEntity.status(413);
            case CAPACITY -> ResponseEntity.status(503).header("Retry-After", "60");
            case ATTEMPT_CONFLICT -> ResponseEntity.status(409);
        };
        String code = switch (exception.getReason()) {
            case RATE_LIMIT -> "STREET_VIEW_EVENT_RATE_LIMITED";
            case PAYLOAD_TOO_LARGE -> "STREET_VIEW_EVENT_PAYLOAD_TOO_LARGE";
            case CAPACITY -> "STREET_VIEW_EVENT_COLLECTION_UNAVAILABLE";
            case ATTEMPT_CONFLICT -> "STREET_VIEW_EVENT_ATTEMPT_CONFLICT";
        };
        return response.body(new ErrorResponse(code, exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(InvalidStreetViewRequestException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidStreetViewRequestException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", "요청값이 올바르지 않습니다.",
                RequestTraceIdResolver.resolve(request),
                List.of(new ErrorResponse.ValidationError(exception.getField(), exception.getMessage()))));
    }
}
