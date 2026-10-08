package com.toadzip.backend.streetview.domain;

import com.toadzip.backend.streetview.exception.InvalidStreetViewRequestException;
import java.util.UUID;

public record StreetViewEvent(UUID attemptId, long complexId, long policyRevision, Type type, Phase phase,
        ReasonCode reasonCode, long durationMs) {
    public enum Type { STARTED, READY, FAILED, CANCELLED }
    public enum Phase { DOCUMENT, SDK, PANORAMA }
    public enum ReasonCode {
        DOCUMENT_TIMEOUT, SDK_LOAD_FAILED, SDK_AUTH_FAILED, SDK_UNAVAILABLE,
        PANORAMA_QUERY_FAILED, INITIALIZATION_TIMEOUT, USER_CLOSED, TARGET_CHANGED
    }

    public StreetViewEvent {
        if (attemptId == null || complexId <= 0 || policyRevision < 0 || type == null || phase == null
                || durationMs < 0 || durationMs > 600_000) {
            throw new InvalidStreetViewRequestException("event", "이벤트 값이 올바르지 않습니다.");
        }
        boolean valid = switch (type) {
            case STARTED -> phase == Phase.DOCUMENT && reasonCode == null && durationMs == 0;
            case READY -> phase == Phase.PANORAMA && reasonCode == null;
            case FAILED -> validFailure(phase, reasonCode);
            case CANCELLED -> reasonCode == ReasonCode.USER_CLOSED || reasonCode == ReasonCode.TARGET_CHANGED;
        };
        if (!valid) {
            throw new InvalidStreetViewRequestException("event", "유형, 단계, 사유, 소요 시간 조합이 올바르지 않습니다.");
        }
    }

    private static boolean validFailure(Phase phase, ReasonCode reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case DOCUMENT_TIMEOUT -> phase == Phase.DOCUMENT;
            case SDK_LOAD_FAILED, SDK_AUTH_FAILED, SDK_UNAVAILABLE -> phase == Phase.SDK;
            case PANORAMA_QUERY_FAILED -> phase == Phase.PANORAMA;
            case INITIALIZATION_TIMEOUT -> true;
            case USER_CLOSED, TARGET_CHANGED -> false;
        };
    }
}
