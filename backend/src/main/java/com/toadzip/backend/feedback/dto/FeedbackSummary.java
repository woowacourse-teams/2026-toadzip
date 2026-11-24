package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.Feedback;
import com.toadzip.backend.feedback.domain.FeedbackStatus;
import java.time.Instant;

public record FeedbackSummary(long id, String content, Instant createdAt, long version, FeedbackStatus status,
        String note, String relatedResource, Long relatedId, String actor, Instant processedAt) {
    public static FeedbackSummary from(Feedback feedback) {
        return new FeedbackSummary(feedback.getId(), feedback.getContent(), feedback.getCreatedAt(),
                feedback.getVersion(), feedback.getStatus(), feedback.getNote(),
                feedback.getRelatedResource(), feedback.getRelatedId(), feedback.getActor(), feedback.getProcessedAt());
    }
}
