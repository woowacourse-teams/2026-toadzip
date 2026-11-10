CREATE TABLE comparison_profile_rechecks (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    requested_version BIGINT NOT NULL CHECK (requested_version >= 0),
    cursor_announcement_id BIGINT NOT NULL DEFAULT 0 CHECK (cursor_announcement_id >= 0),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING', 'DONE')),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01 UTC',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((state = 'RUNNING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'RUNNING' AND claim_token IS NULL AND lease_until IS NULL))
);

-- A persistent canonical-root generation anchor as well as a coalesced fan-out job.
CREATE TABLE comparison_notice_rechecks (
    announcement_id BIGINT PRIMARY KEY REFERENCES announcements(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    retired BOOLEAN NOT NULL DEFAULT false,
    observed_basis VARCHAR(500),
    cursor_user_id BIGINT NOT NULL DEFAULT 0 CHECK (cursor_user_id >= 0),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RUNNING', 'DONE')),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01 UTC',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((state = 'RUNNING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'RUNNING' AND claim_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX comparison_profile_rechecks_ready ON comparison_profile_rechecks(next_attempt_at, user_id)
    WHERE state <> 'DONE';
CREATE INDEX comparison_notice_rechecks_ready ON comparison_notice_rechecks(next_attempt_at, announcement_id)
    WHERE state <> 'DONE';

CREATE TABLE comparison_latest_results (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    announcement_id BIGINT NOT NULL REFERENCES comparison_notice_rechecks(announcement_id) ON DELETE CASCADE,
    resolved_announcement_id BIGINT NOT NULL,
    profile_version BIGINT NOT NULL CHECK (profile_version >= 0),
    notice_generation BIGINT NOT NULL CHECK (notice_generation > 0),
    rule_set_id BIGINT,
    availability VARCHAR(30) NOT NULL,
    application_status VARCHAR(30) NOT NULL,
    evaluator_version VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('ELIGIBLE', 'INELIGIBLE', 'UNDETERMINED', 'NOT_EVALUATED')),
    result JSONB CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
    evaluated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id, announcement_id)
);
CREATE INDEX comparison_latest_results_notice_users ON comparison_latest_results(announcement_id, user_id);

CREATE TABLE comparison_discoveries (
    user_id BIGINT NOT NULL,
    announcement_id BIGINT NOT NULL,
    event_id UUID NOT NULL DEFAULT gen_random_uuid(),
    profile_version BIGINT NOT NULL CHECK (profile_version >= 0),
    notice_generation BIGINT NOT NULL CHECK (notice_generation > 0),
    option_ids JSONB NOT NULL CHECK (jsonb_typeof(option_ids) = 'array' AND jsonb_array_length(option_ids) > 0),
    discovered_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    PRIMARY KEY(user_id, announcement_id),
    FOREIGN KEY(user_id, announcement_id)
        REFERENCES comparison_latest_results(user_id, announcement_id) ON DELETE CASCADE
);

CREATE FUNCTION comparison_root_id(start_id BIGINT) RETURNS BIGINT
LANGUAGE sql STABLE AS $$
    WITH RECURSIVE ancestors AS (
        SELECT id, previous_announcement_id, ARRAY[id] AS visited FROM announcements WHERE id = start_id
        UNION ALL
        SELECT a.id, a.previous_announcement_id, c.visited || a.id
        FROM announcements a JOIN ancestors c ON c.previous_announcement_id = a.id
        WHERE NOT a.id = ANY(c.visited) AND cardinality(c.visited) < 100
    )
    SELECT id FROM ancestors ORDER BY cardinality(visited) DESC LIMIT 1
$$;

CREATE FUNCTION comparison_touch_notice(root_id BIGINT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF root_id IS NULL THEN RETURN; END IF;
    INSERT INTO comparison_notice_rechecks(announcement_id, retired)
        SELECT id, previous_announcement_id IS NOT NULL FROM announcements WHERE id = root_id
    ON CONFLICT(announcement_id) DO UPDATE SET
        generation = comparison_notice_rechecks.generation + 1,
        retired = EXCLUDED.retired, observed_basis = NULL, cursor_user_id = 0,
        state = 'PENDING', claim_token = NULL, lease_until = NULL,
        next_attempt_at = '1970-01-01 UTC', attempts = 0, last_error_code = NULL, updated_at = now();
END $$;

CREATE FUNCTION comparison_announcement_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_root BIGINT; current_root BIGINT; affected_root BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' AND ROW(NEW.previous_announcement_id, NEW.release_status, NEW.status, NEW.admin_deleted,
            NEW.application_start_date, NEW.application_end_date, NEW.application_schedule_reviewed)
        IS NOT DISTINCT FROM ROW(OLD.previous_announcement_id, OLD.release_status, OLD.status, OLD.admin_deleted,
            OLD.application_start_date, OLD.application_end_date, OLD.application_schedule_reviewed) THEN
        RETURN NULL;
    END IF;
    IF TG_OP <> 'INSERT' THEN
        previous_root := OLD.id;
        IF OLD.previous_announcement_id IS NOT NULL THEN
            previous_root := comparison_root_id(OLD.previous_announcement_id);
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN current_root := comparison_root_id(NEW.id); END IF;
    FOR affected_root IN SELECT DISTINCT root FROM unnest(ARRAY[previous_root, current_root]) root
            WHERE root IS NOT NULL ORDER BY root LOOP
        PERFORM comparison_touch_notice(affected_root);
    END LOOP;
    RETURN NULL;
END $$;
CREATE TRIGGER comparison_announcement_changed AFTER INSERT OR DELETE OR
    UPDATE OF previous_announcement_id, release_status, status, admin_deleted,
        application_start_date, application_end_date, application_schedule_reviewed ON announcements
    FOR EACH ROW EXECUTE FUNCTION comparison_announcement_changed();

CREATE FUNCTION comparison_structure_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' AND OLD.status = 'PUBLISHED' THEN
        PERFORM comparison_touch_notice(comparison_root_id(OLD.announcement_id));
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.status = 'PUBLISHED' THEN
        PERFORM comparison_touch_notice(comparison_root_id(NEW.announcement_id));
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER comparison_structure_changed AFTER INSERT OR UPDATE OR DELETE ON basic_comparison_rule_sets
    FOR EACH ROW EXECUTE FUNCTION comparison_structure_changed();

CREATE FUNCTION comparison_schedule_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_root BIGINT; current_root BIGINT; affected_root BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN previous_root := comparison_root_id(OLD.announcement_id); END IF;
    IF TG_OP <> 'DELETE' THEN current_root := comparison_root_id(NEW.announcement_id); END IF;
    FOR affected_root IN SELECT DISTINCT root FROM unnest(ARRAY[previous_root, current_root]) root
            WHERE root IS NOT NULL ORDER BY root LOOP
        PERFORM comparison_touch_notice(affected_root);
    END LOOP;
    RETURN NULL;
END $$;
CREATE TRIGGER comparison_schedule_changed AFTER INSERT OR UPDATE OR DELETE ON announcement_application_schedules
    FOR EACH ROW EXECUTE FUNCTION comparison_schedule_changed();

CREATE FUNCTION comparison_profile_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version < OLD.version OR
            (ROW(NEW.profile, NEW.input_contract_version) IS DISTINCT FROM ROW(OLD.profile, OLD.input_contract_version)
                AND NEW.version <= OLD.version) THEN
        RAISE EXCEPTION 'Profile changes require an increasing version' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER comparison_profile_version_guard BEFORE UPDATE ON basic_comparison_profiles
    FOR EACH ROW EXECUTE FUNCTION comparison_profile_version_guard();

CREATE FUNCTION comparison_profile_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO comparison_profile_rechecks(user_id, requested_version) VALUES(NEW.user_id, NEW.version)
    ON CONFLICT(user_id) DO UPDATE SET requested_version = EXCLUDED.requested_version,
        cursor_announcement_id = 0, state = 'PENDING', claim_token = NULL, lease_until = NULL,
        next_attempt_at = '1970-01-01 UTC', attempts = 0, last_error_code = NULL, updated_at = now()
    WHERE comparison_profile_rechecks.requested_version < EXCLUDED.requested_version;
    RETURN NULL;
END $$;
CREATE TRIGGER comparison_profile_changed AFTER INSERT OR UPDATE ON basic_comparison_profiles
    FOR EACH ROW EXECUTE FUNCTION comparison_profile_changed();

CREATE FUNCTION comparison_user_created() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO comparison_profile_rechecks(user_id, requested_version) VALUES(NEW.id, 0)
        ON CONFLICT DO NOTHING;
    RETURN NULL;
END $$;
CREATE TRIGGER comparison_user_created AFTER INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION comparison_user_created();

-- Old current data gets durable work without requiring the user to edit it again.
INSERT INTO comparison_profile_rechecks(user_id, requested_version)
    SELECT u.id, COALESCE(p.version, 0) FROM users u LEFT JOIN basic_comparison_profiles p ON p.user_id = u.id;
INSERT INTO comparison_notice_rechecks(announcement_id)
    SELECT id FROM announcements WHERE previous_announcement_id IS NULL;

-- Referenced standard IDs are immutable. Corrections use a new ID and a newly published rule set.
ALTER TABLE income_standard_tables DROP CONSTRAINT income_standard_tables_standard_kind_base_year_key;
CREATE INDEX income_standard_tables_kind_year_idx ON income_standard_tables(standard_kind, base_year);

CREATE FUNCTION comparison_lock_standard_references() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM income_standard_tables
        WHERE id IN (
            SELECT DISTINCT (value #>> '{}')::BIGINT
            FROM jsonb_path_query(NEW.content, '$.**.standardTableId') value
            WHERE jsonb_typeof(value) = 'number'
        )
        ORDER BY id FOR SHARE;
    RETURN NEW;
END $$;
CREATE TRIGGER comparison_lock_standard_references BEFORE INSERT OR UPDATE OF content ON basic_comparison_rule_sets
    FOR EACH ROW EXECUTE FUNCTION comparison_lock_standard_references();

CREATE FUNCTION comparison_standard_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE standard_id BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'income_standard_values' AND TG_OP = 'UPDATE' THEN
        IF NEW.table_id <> OLD.table_id THEN
            RAISE EXCEPTION 'Income standard values cannot move between standard IDs' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_TABLE_NAME = 'income_standard_tables' THEN standard_id := OLD.id;
    ELSIF TG_OP = 'INSERT' THEN standard_id := NEW.table_id;
    ELSE standard_id := OLD.table_id;
    END IF;
    PERFORM id FROM income_standard_tables WHERE id = standard_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM basic_comparison_rule_sets r WHERE
            jsonb_path_exists(r.content, '$.**.standardTableId ? (@ == $id)',
                jsonb_build_object('id', standard_id))) THEN
        RAISE EXCEPTION 'Referenced income standards are immutable; publish a new standard ID'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER comparison_standard_immutable BEFORE UPDATE OR DELETE ON income_standard_tables
    FOR EACH ROW EXECUTE FUNCTION comparison_standard_immutable();
CREATE TRIGGER comparison_standard_value_immutable BEFORE INSERT OR UPDATE OR DELETE ON income_standard_values
    FOR EACH ROW EXECUTE FUNCTION comparison_standard_immutable();
