package com.toadzip.backend.search.controller;

import com.toadzip.backend.global.exception.ErrorResponse;
import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import com.toadzip.backend.search.exception.InvalidSearchRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SearchExceptionAdvice {

    @ExceptionHandler(InvalidSearchRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSearchRequest(
            InvalidSearchRequestException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        "INVALID_SEARCH_REQUEST",
                        exception.getMessage(),
                        RequestTraceIdResolver.resolve(request)
                ));
    }
}
