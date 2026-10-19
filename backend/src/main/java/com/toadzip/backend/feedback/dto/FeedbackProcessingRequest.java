package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.FeedbackStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record FeedbackProcessingRequest(@NotNull @PositiveOrZero Long version, @NotNull FeedbackStatus status,
        @NotNull @Size(max = 255) String assignee, @NotNull @Size(max = 5000) String note,
        @NotNull @Pattern(regexp = "|COMPLEX|ANNOUNCEMENT") String relatedResource, @Positive Long relatedId) { }
