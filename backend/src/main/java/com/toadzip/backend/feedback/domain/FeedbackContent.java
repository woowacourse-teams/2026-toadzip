package com.toadzip.backend.feedback.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class FeedbackContent {

    public static final int MAX_LENGTH = 2000;

    @Column(name = "content", nullable = false, length = MAX_LENGTH)
    private String value;

    protected FeedbackContent() {
    }

    private FeedbackContent(String value) {
        this.value = value;
    }

    public static FeedbackContent of(String value) {
        if (value == null || value.isBlank() || value.strip().length() > MAX_LENGTH) {
            throw new IllegalArgumentException("의견은 1자 이상 2,000자 이하로 입력해야 합니다.");
        }
        return new FeedbackContent(value.strip());
    }

    public String value() {
        return value;
    }
}
