package com.toadzip.backend.feedback.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.regex.Pattern;

@Embeddable
public class FeedbackContent {

    public static final int MAX_LENGTH = 2000;

    private static final Pattern SURROUNDING_WHITESPACE = Pattern.compile(
            "^[\\p{javaWhitespace}\\p{Zs}\\uFEFF]+|[\\p{javaWhitespace}\\p{Zs}\\uFEFF]+$"
    );

    @Column(name = "content", nullable = false, length = MAX_LENGTH)
    private String value;

    protected FeedbackContent() {
    }

    private FeedbackContent(String value) {
        this.value = value;
    }

    public static FeedbackContent of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("의견은 1자 이상 2,000자 이하로 입력해야 합니다.");
        }
        String normalized = SURROUNDING_WHITESPACE.matcher(value).replaceAll("");
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("의견은 1자 이상 2,000자 이하로 입력해야 합니다.");
        }
        return new FeedbackContent(normalized);
    }

    public String value() {
        return value;
    }
}
