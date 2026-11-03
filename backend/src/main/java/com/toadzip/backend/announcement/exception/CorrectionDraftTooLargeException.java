package com.toadzip.backend.announcement.exception;

public class CorrectionDraftTooLargeException extends RuntimeException {
    public CorrectionDraftTooLargeException() {
        super("초안이 너무 커서 저장하지 못했습니다. 2MB 이하만 저장할 수 있습니다.");
    }
}
