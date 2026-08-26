package com.toadzip.backend.global.exception;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        List<ValidationError> errors
) {

    public ErrorResponse(String code, String message, String traceId) {
        this(code, message, traceId, List.of());
    }

    public ErrorResponse {
        errors = List.copyOf(errors);
    }

    public record ValidationError(String field, String reason) {
    }
}
