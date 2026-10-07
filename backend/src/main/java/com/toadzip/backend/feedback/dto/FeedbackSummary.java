package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.Feedback;
import java.time.Instant;

public record FeedbackSummary(long id, String content, Instant createdAt) {

    public static FeedbackSummary from(Feedback feedback) {
        return new FeedbackSummary(feedback.getId(), feedback.getContent(), feedback.getCreatedAt());
    }
}
