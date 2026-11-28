package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.FeedbackStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 관련 단지·공고는 사용자가 의견을 보낼 때만 붙으므로 처리 요청으로 바꾸지 않는다. */
public record FeedbackProcessingRequest(@NotNull @PositiveOrZero Long version, @NotNull FeedbackStatus status,
        @NotNull @Size(max = 5000) String note) { }
