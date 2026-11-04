package com.toadzip.backend.feedback.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum FeedbackSurveyAnswer {
    VERY_DISAPPOINTED,
    SOMEWHAT_DISAPPOINTED,
    NOT_DISAPPOINTED,
    NO_LONGER_USING;

    @JsonCreator
    public static FeedbackSurveyAnswer from(String value) {
        return valueOf(value);
    }
}
