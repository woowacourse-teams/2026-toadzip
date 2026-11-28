package com.toadzip.backend.feedback.exception;

public class FeedbackTargetNotFoundException extends RuntimeException {
    public FeedbackTargetNotFoundException() {
        super("의견을 남길 공고나 단지를 찾을 수 없습니다.");
    }
}
