ALTER TABLE lh_household_enrichment_failures
    DROP CONSTRAINT IF EXISTS lh_household_enrichment_failures_reason_check,
    ADD CONSTRAINT lh_household_enrichment_failures_reason_check CHECK (reason IN (
        'INVALID_SOURCE',
        'COMPLEX_NOT_FOUND',
        'AMBIGUOUS_COMPLEX',
        'DUPLICATE_TARGET_COMPLEX',
        'MANUAL_LINK_REVIEW_REQUIRED',
        'TARGET_DECISION_REVIEW_REQUIRED'
    ));
