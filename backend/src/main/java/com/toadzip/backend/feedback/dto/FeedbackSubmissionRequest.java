package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.FeedbackContent;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FeedbackSubmissionRequest(
        @NotNull(message = "의견 내용을 입력해 주세요")
        @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{Zs}\\uFEFF].*", message = "의견 내용을 입력해 주세요")
        @Size(max = FeedbackContent.MAX_LENGTH, message = "의견은 2,000자 이하로 입력해 주세요") String content,
        @Pattern(regexp = "COMPLEX|ANNOUNCEMENT", message = "관련 대상 종류가 올바르지 않습니다") String relatedResource,
        @Positive(message = "관련 대상 ID가 올바르지 않습니다") Long relatedId
) {
    @AssertTrue(message = "관련 대상의 종류와 ID를 함께 보내 주세요")
    public boolean isRelatedTargetPaired() {
        return (relatedResource == null) == (relatedId == null);
    }
}
