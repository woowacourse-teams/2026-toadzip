BEGIN;

ALTER TABLE myhome_announcement_mapping_failures
    DROP CONSTRAINT IF EXISTS myhome_announcement_mapping_failures_reason_check,
    ADD CONSTRAINT myhome_announcement_mapping_failures_reason_check CHECK (reason IN (
        'LH_COLLECTION_REQUEST_UNSUPPORTED',
        'LH_COLLECTION_LINK_NOT_FOUND',
        'LH_COLLECTION_LINK_MISMATCH',
        'LH_SUPPLY_SOURCE_NOT_FOUND',
        'MISSING_REQUIRED_VALUE',
        'INVALID_VALUE',
        'CONFLICTING_SOURCE_VALUE',
        'PREVIOUS_ANNOUNCEMENT_NOT_FOUND',
        'CYCLIC_ANNOUNCEMENT_REVISION',
        'COMPLEX_NOT_FOUND',
        'AMBIGUOUS_COMPLEX',
        'HOUSING_TYPE_NOT_FOUND',
        'AMBIGUOUS_HOUSING_TYPE'
    ));

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
        'AMBIGUOUS_HOUSING_TYPE'
    ));

COMMIT;
