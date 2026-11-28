package com.toadzip.backend.feedback.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.toadzip.backend.feedback.domain.FeedbackStatus;
import java.util.Map;

public record FeedbackStatusCounts(
        @JsonProperty("OPEN") long open,
        @JsonProperty("IN_PROGRESS") long inProgress,
        @JsonProperty("ON_HOLD") long onHold,
        @JsonProperty("DONE") long done,
        long total) {
    public static FeedbackStatusCounts from(Map<FeedbackStatus, Long> counts) {
        long open = counts.getOrDefault(FeedbackStatus.OPEN, 0L);
        long inProgress = counts.getOrDefault(FeedbackStatus.IN_PROGRESS, 0L);
        long onHold = counts.getOrDefault(FeedbackStatus.ON_HOLD, 0L);
        long done = counts.getOrDefault(FeedbackStatus.DONE, 0L);
        return new FeedbackStatusCounts(open, inProgress, onHold, done, counts.values().stream().mapToLong(Long::longValue).sum());
    }
}
