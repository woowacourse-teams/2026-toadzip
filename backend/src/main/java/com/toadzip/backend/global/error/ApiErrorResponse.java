package com.toadzip.backend.global.error;

public record ApiErrorResponse(String code, String message, String traceId) {
}
