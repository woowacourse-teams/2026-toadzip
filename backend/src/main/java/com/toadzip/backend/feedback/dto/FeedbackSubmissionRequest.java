package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.FeedbackContent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FeedbackSubmissionRequest(
        @NotNull(message = "의견 내용을 입력해 주세요")
        @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{Zs}\\uFEFF].*", message = "의견 내용을 입력해 주세요")
        @Size(max = FeedbackContent.MAX_LENGTH, message = "의견은 2,000자 이하로 입력해 주세요") String content
) {
}
