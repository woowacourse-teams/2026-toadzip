-- Preserve existing content and migrations. New writers save the expanded typed contract.
ALTER TABLE basic_comparison_rule_sets
    DROP CONSTRAINT basic_comparison_rule_sets_schema_version_check,
    ADD CONSTRAINT basic_comparison_rule_sets_schema_version_check CHECK (schema_version IN (2, 3)),
    ALTER COLUMN schema_version SET DEFAULT 3;

CREATE TABLE comparison_supply_units (
    rule_set_id BIGINT NOT NULL REFERENCES basic_comparison_rule_sets(id) ON DELETE CASCADE,
    unit_key VARCHAR(100) NOT NULL,
    label VARCHAR(300) NOT NULL,
    region_codes TEXT[] NOT NULL DEFAULT '{}',
    housing_types JSONB NOT NULL CHECK (jsonb_typeof(housing_types) = 'array'),
    PRIMARY KEY (rule_set_id, unit_key)
);
CREATE INDEX comparison_supply_regions ON comparison_supply_units USING GIN (region_codes);

CREATE TABLE comparison_paths (
    rule_set_id BIGINT NOT NULL REFERENCES basic_comparison_rule_sets(id) ON DELETE CASCADE,
    path_key VARCHAR(100) NOT NULL,
    label VARCHAR(300) NOT NULL,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('APPLICATION', 'GENERAL', 'COMMON_ONLY')),
    checks JSONB NOT NULL CHECK (jsonb_typeof(checks) = 'array'),
    PRIMARY KEY (rule_set_id, path_key)
);

CREATE TABLE comparison_qualifications (
    rule_set_id BIGINT NOT NULL REFERENCES basic_comparison_rule_sets(id) ON DELETE CASCADE,
    qualification_key VARCHAR(100) NOT NULL,
    exact_name VARCHAR(300) NOT NULL CHECK (btrim(exact_name) <> ''),
    legal_basis TEXT,
    conditions_as_written TEXT NOT NULL CHECK (btrim(conditions_as_written) <> ''),
    categories TEXT[] NOT NULL DEFAULT '{}',
    evidence_ids JSONB NOT NULL CHECK (jsonb_typeof(evidence_ids) = 'array' AND jsonb_array_length(evidence_ids) > 0),
    PRIMARY KEY (rule_set_id, qualification_key)
);
CREATE INDEX comparison_qualification_categories ON comparison_qualifications USING GIN (categories);

CREATE TABLE comparison_path_qualifications (
    rule_set_id BIGINT NOT NULL,
    path_key VARCHAR(100) NOT NULL,
    qualification_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (rule_set_id, path_key, qualification_key),
    FOREIGN KEY (rule_set_id, path_key) REFERENCES comparison_paths ON DELETE CASCADE,
    FOREIGN KEY (rule_set_id, qualification_key) REFERENCES comparison_qualifications ON DELETE CASCADE
);

CREATE TABLE comparison_options (
    rule_set_id BIGINT NOT NULL,
    option_key VARCHAR(100) NOT NULL,
    unit_key VARCHAR(100) NOT NULL,
    path_key VARCHAR(100) NOT NULL,
    recruitment_status VARCHAR(30) NOT NULL CHECK (
        recruitment_status IN ('RECRUITING', 'UPCOMING', 'NOT_RECRUITING', 'CANCELLED', 'UNKNOWN')),
    PRIMARY KEY (rule_set_id, option_key),
    UNIQUE (rule_set_id, unit_key, path_key),
    FOREIGN KEY (rule_set_id, unit_key) REFERENCES comparison_supply_units ON DELETE CASCADE,
    FOREIGN KEY (rule_set_id, path_key) REFERENCES comparison_paths ON DELETE CASCADE
);

-- Backfill previously authored v2 content without inferring absent regions or qualifications.
INSERT INTO comparison_supply_units
SELECT r.id, u->>'id', u->>'label',
       ARRAY(SELECT jsonb_array_elements_text(COALESCE(u->'regionCodes', '[]'))), u->'housingTypes'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'supplyUnits', '[]')) u;

INSERT INTO comparison_paths
SELECT r.id, p->>'id', p->>'label', p->>'kind', p->'checks'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'paths', '[]')) p;

INSERT INTO comparison_qualifications
SELECT r.id, q->>'id', q->>'exactName', q->>'legalBasis', q->>'conditionsAsWritten',
       ARRAY(SELECT jsonb_array_elements_text(COALESCE(q->'categories', '[]'))), q->'evidenceIds'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'qualifications', '[]')) q;

INSERT INTO comparison_path_qualifications
SELECT r.id, p->>'id', q
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'paths', '[]')) p,
     LATERAL jsonb_array_elements_text(COALESCE(p->'qualificationIds', '[]')) q;

INSERT INTO comparison_options
SELECT r.id, o->>'id', o->>'supplyUnitId', o->>'pathId', o->>'recruitmentStatus'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'options', '[]')) o;
