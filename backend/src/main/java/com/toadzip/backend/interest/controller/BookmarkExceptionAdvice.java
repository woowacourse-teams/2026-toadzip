package com.toadzip.backend.interest.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.interest.exception.BookmarkTargetUnavailableException;
import com.toadzip.backend.interest.exception.BookmarkUserMissingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class BookmarkExceptionAdvice {
    @ExceptionHandler(BookmarkTargetUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(BookmarkTargetUnavailableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                "BOOKMARK_TARGET_UNAVAILABLE", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }

    @ExceptionHandler(BookmarkUserMissingException.class)
    public ResponseEntity<ErrorResponse> missingUser(BookmarkUserMissingException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                "UNAUTHORIZED", exception.getMessage(), RequestTraceIdResolver.resolve(request)));
    }
}
