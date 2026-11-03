package com.toadzip.backend.announcement.exception;

import java.time.Instant;

/** 화면이 본 revision과 저장된 초안이 다르다. currentRevision이 null이면 그사이 초안이 지워졌다. */
public class CorrectionDraftConflictException extends RuntimeException {
    private final Long currentRevision;
    private final Instant currentUpdatedAt;

    public CorrectionDraftConflictException(Long currentRevision, Instant currentUpdatedAt) {
        super("다른 화면에서 초안이 바뀌었습니다. 최신 초안을 확인한 뒤 다시 저장해 주세요.");
        this.currentRevision = currentRevision;
        this.currentUpdatedAt = currentUpdatedAt;
    }

    public Long currentRevision() {
        return currentRevision;
    }

    public Instant currentUpdatedAt() {
        return currentUpdatedAt;
    }
}
