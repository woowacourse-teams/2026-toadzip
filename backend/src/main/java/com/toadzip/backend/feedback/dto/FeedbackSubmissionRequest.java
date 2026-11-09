package com.toadzip.backend.feedback.dto;

import com.toadzip.backend.feedback.domain.FeedbackContent;
import com.toadzip.backend.feedback.domain.FeedbackSurveyAnswer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackSubmissionRequest(
        @Size(max = FeedbackContent.MAX_LENGTH, message = "의견은 2,000자 이하로 입력해 주세요") String content,
        @NotNull(message = "설문 답변을 선택해 주세요") FeedbackSurveyAnswer surveyAnswer
) {
}
