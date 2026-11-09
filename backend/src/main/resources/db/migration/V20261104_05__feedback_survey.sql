ALTER TABLE feedback
    ADD COLUMN survey_answer VARCHAR(30),
    DROP CONSTRAINT feedback_content_check,
    ADD CONSTRAINT ck_feedback_survey_answer CHECK (survey_answer IN (
        'VERY_DISAPPOINTED', 'SOMEWHAT_DISAPPOINTED', 'NOT_DISAPPOINTED', 'NO_LONGER_USING'
    )),
    ADD CONSTRAINT ck_feedback_response CHECK (survey_answer IS NOT NULL OR length(btrim(content)) > 0);
