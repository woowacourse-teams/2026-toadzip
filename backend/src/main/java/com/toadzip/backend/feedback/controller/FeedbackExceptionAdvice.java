package com.toadzip.backend.feedback.controller;

import com.toadzip.backend.feedback.exception.FeedbackTargetNotFoundException;
import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.ErrorResponse.ValidationError;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class FeedbackExceptionAdvice {

    @ExceptionHandler(FeedbackTargetNotFoundException.class)
    public ResponseEntity<ErrorResponse> targetNotFound(FeedbackTargetNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_FAILED",
                "요청값이 올바르지 않습니다.", RequestTraceIdResolver.resolve(request),
                List.of(new ValidationError("relatedId", exception.getMessage()))));
    }
}
