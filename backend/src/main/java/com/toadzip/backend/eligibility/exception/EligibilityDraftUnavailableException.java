package com.toadzip.backend.eligibility.exception;

/** 지금 상태로는 AI 추출 결과에서 신청자격 초안을 만들 수 없다. 관리자가 할 일을 문구로 알린다(409). */
public class EligibilityDraftUnavailableException extends EligibilityConflictException {
    public EligibilityDraftUnavailableException(String message) {
        super(message);
    }
}
