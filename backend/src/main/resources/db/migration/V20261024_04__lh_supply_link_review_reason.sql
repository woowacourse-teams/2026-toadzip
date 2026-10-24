ALTER TABLE lh_announcement_enrichment_failures
    DROP CONSTRAINT IF EXISTS lh_announcement_enrichment_failures_reason_check,
    ADD CONSTRAINT lh_announcement_enrichment_failures_reason_check CHECK (reason IN (
        'LH_COLLECTION_REQUEST_UNSUPPORTED',
        'LH_COLLECTION_LINK_NOT_FOUND',
        'LH_COLLECTION_LINK_MISMATCH',
        'ANNOUNCEMENT_NOT_FOUND',
        'PAN_ID_NOT_FOUND',
        'LH_DETAIL_SOURCE_NOT_FOUND',
        'LH_SUPPLY_SOURCE_NOT_FOUND',
        'UNSUPPORTED_SUPPLY_TYPE',
        'INVALID_VALUE',
        'COMPLEX_NOT_FOUND',
        'AMBIGUOUS_COMPLEX',
        'HOUSING_TYPE_NOT_FOUND',
        'AMBIGUOUS_HOUSING_TYPE',
        'LH_SUPPLY_LINK_REVIEW_REQUIRED'
    ));
