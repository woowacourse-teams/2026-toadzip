package com.toadzip.backend.ingest.failure.service;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class IngestExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(IngestExecutionContext.class);

    private IngestExecutionContext() {
    }

    public static Optional<UUID> currentExecutionId() {
        String executionId = MDC.get("executionId");
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(executionId));
        }
        catch (IllegalArgumentException exception) {
            log.warn("실행 로그 맥락의 executionId가 UUID 형식이 아니어서 실패 이력 연결을 생략합니다.");
            return Optional.empty();
        }
    }
}
