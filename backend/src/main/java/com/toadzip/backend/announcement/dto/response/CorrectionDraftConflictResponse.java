package com.toadzip.backend.announcement.dto.response;

import java.time.Instant;

/**
 * 초안 revision 충돌 응답. 화면은 덮어쓰지 않고 충돌을 보여 준다.
 *
 * @param currentRevision 지금 저장된 초안의 revision. 초안이 지워졌으면 null이다.
 * @param currentUpdatedAt 지금 저장된 초안의 마지막 저장 시각. 초안이 지워졌으면 null이다.
 */
public record CorrectionDraftConflictResponse(String code, String message, String traceId, Long currentRevision,
        Instant currentUpdatedAt) {
}
