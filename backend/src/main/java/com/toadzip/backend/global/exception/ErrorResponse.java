package com.toadzip.backend.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        List<ValidationError> errors
) {

    public ErrorResponse {
        if (errors != null) {
            errors = List.copyOf(errors);
        }
    }

    public record ValidationError(String field, String reason) {
    }
}
