-- Preserve existing current profiles. New residence facts are UNKNOWN until supplied.
ALTER TABLE basic_comparison_profiles
    DROP CONSTRAINT basic_comparison_profiles_input_contract_version_check,
    ADD CONSTRAINT basic_comparison_profiles_input_contract_version_check
        CHECK (input_contract_version IN (2, 3)),
    ALTER COLUMN input_contract_version SET DEFAULT 3;
