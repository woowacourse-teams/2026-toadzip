-- 신청자격 전환 후 읽기 전용 점검. V20261110_02~08 적용 후 primary DB에서 실행한다.
-- 실행 위치: 저장소 루트. 접속 대상은 운영자가 별도로 선택한다.
-- psql -X --set ON_ERROR_STOP=1 --file docs/queries/eligibility-status.sql
-- 프로필·공고·결과 원문과 사용자 ID를 출력하지 않는다.
-- 투영 전체를 비교하므로 복제본에서 비용을 먼저 확인한다. timeout은 실행 보호값이며 서비스 SLO가 아니다.
-- 실패하면 psql 연결을 종료한다. 쓰기·repair·작업 상태 변경은 수행하지 않는다.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

SELECT current_database() AS database_name, now() AS observed_at;

SELECT version, script, success
FROM flyway_schema_history
WHERE version LIKE '20261106.%'
ORDER BY installed_rank;

-- 아래 두 누락/불일치 값의 기대값은 0이다.
SELECT count(*) AS missing_or_mismatched_profile_requests
FROM users u
LEFT JOIN basic_comparison_profiles p ON p.user_id = u.id
LEFT JOIN comparison_profile_rechecks j ON j.user_id = u.id
WHERE j.user_id IS NULL OR j.requested_version <> COALESCE(p.version, 0);

SELECT count(*) AS missing_root_notice_requests
FROM announcements a
LEFT JOIN comparison_notice_rechecks j ON j.announcement_id = a.id
WHERE a.previous_announcement_id IS NULL AND j.announcement_id IS NULL;

-- JSON 원본과 관계형 투영을 양방향 비교한다. 각 값의 기대값은 0이다.
-- expected는 V20261110_06의 백필 SELECT와 같은 열 순서·기본값을 사용한다.

WITH expected (rule_set_id, unit_key, label, region_codes, housing_types) AS (
SELECT r.id, u->>'id', u->>'label',
       ARRAY(SELECT jsonb_array_elements_text(COALESCE(u->'regionCodes', '[]'))), u->'housingTypes'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'supplyUnits', '[]')) u
), missing AS (
    SELECT * FROM expected
    EXCEPT ALL
    SELECT rule_set_id, unit_key, label, region_codes, housing_types FROM comparison_supply_units
), unexpected AS (
    SELECT rule_set_id, unit_key, label, region_codes, housing_types FROM comparison_supply_units
    EXCEPT ALL
    SELECT * FROM expected
)
SELECT 'comparison_supply_units' AS projection,
       (SELECT count(*) FROM missing) AS missing_or_different_rows,
       (SELECT count(*) FROM unexpected) AS unexpected_or_different_rows;

WITH expected (rule_set_id, path_key, label, kind, checks) AS (
SELECT r.id, p->>'id', p->>'label', p->>'kind', p->'checks'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'paths', '[]')) p
), missing AS (
    SELECT * FROM expected
    EXCEPT ALL
    SELECT rule_set_id, path_key, label, kind, checks FROM comparison_paths
), unexpected AS (
    SELECT rule_set_id, path_key, label, kind, checks FROM comparison_paths
    EXCEPT ALL
    SELECT * FROM expected
)
SELECT 'comparison_paths' AS projection,
       (SELECT count(*) FROM missing) AS missing_or_different_rows,
       (SELECT count(*) FROM unexpected) AS unexpected_or_different_rows;

WITH expected (rule_set_id, qualification_key, exact_name, legal_basis, conditions_as_written, categories, evidence_ids) AS (
SELECT r.id, q->>'id', q->>'exactName', q->>'legalBasis', q->>'conditionsAsWritten',
       ARRAY(SELECT jsonb_array_elements_text(COALESCE(q->'categories', '[]'))), q->'evidenceIds'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'qualifications', '[]')) q
), missing AS (
    SELECT * FROM expected
    EXCEPT ALL
    SELECT rule_set_id, qualification_key, exact_name, legal_basis, conditions_as_written, categories, evidence_ids FROM comparison_qualifications
), unexpected AS (
    SELECT rule_set_id, qualification_key, exact_name, legal_basis, conditions_as_written, categories, evidence_ids FROM comparison_qualifications
    EXCEPT ALL
    SELECT * FROM expected
)
SELECT 'comparison_qualifications' AS projection,
       (SELECT count(*) FROM missing) AS missing_or_different_rows,
       (SELECT count(*) FROM unexpected) AS unexpected_or_different_rows;

WITH expected (rule_set_id, path_key, qualification_key) AS (
SELECT r.id, p->>'id', q
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'paths', '[]')) p,
     LATERAL jsonb_array_elements_text(COALESCE(p->'qualificationIds', '[]')) q
), missing AS (
    SELECT * FROM expected
    EXCEPT ALL
    SELECT rule_set_id, path_key, qualification_key FROM comparison_path_qualifications
), unexpected AS (
    SELECT rule_set_id, path_key, qualification_key FROM comparison_path_qualifications
    EXCEPT ALL
    SELECT * FROM expected
)
SELECT 'comparison_path_qualifications' AS projection,
       (SELECT count(*) FROM missing) AS missing_or_different_rows,
       (SELECT count(*) FROM unexpected) AS unexpected_or_different_rows;

WITH expected (rule_set_id, option_key, unit_key, path_key, recruitment_status) AS (
SELECT r.id, o->>'id', o->>'supplyUnitId', o->>'pathId', o->>'recruitmentStatus'
FROM basic_comparison_rule_sets r,
     LATERAL jsonb_array_elements(COALESCE(r.content->'options', '[]')) o
), missing AS (
    SELECT * FROM expected
    EXCEPT ALL
    SELECT rule_set_id, option_key, unit_key, path_key, recruitment_status FROM comparison_options
), unexpected AS (
    SELECT rule_set_id, option_key, unit_key, path_key, recruitment_status FROM comparison_options
    EXCEPT ALL
    SELECT * FROM expected
)
SELECT 'comparison_options' AS projection,
       (SELECT count(*) FROM missing) AS missing_or_different_rows,
       (SELECT count(*) FROM unexpected) AS unexpected_or_different_rows;

-- 상태 집계는 관측용이다. DONE 건수만으로 전체 결과의 현재성을 판단하지 않는다.
SELECT 'PROFILE' AS kind, state, count(*) AS jobs,
       count(*) FILTER (WHERE state = 'RUNNING' AND lease_until < now()) AS expired_claims,
       min(updated_at) AS oldest_updated_at, max(attempts) AS maximum_attempts
FROM comparison_profile_rechecks
GROUP BY state
UNION ALL
SELECT 'NOTICE', state, count(*),
       count(*) FILTER (WHERE state = 'RUNNING' AND lease_until < now()),
       min(updated_at), max(attempts)
FROM comparison_notice_rechecks
GROUP BY state;

SELECT 'PROFILE' AS kind, last_error_code, count(*) AS jobs
FROM comparison_profile_rechecks WHERE last_error_code IS NOT NULL GROUP BY last_error_code
UNION ALL
SELECT 'NOTICE', last_error_code, count(*)
FROM comparison_notice_rechecks WHERE last_error_code IS NOT NULL GROUP BY last_error_code;

-- 정의·활성 상태를 DDL 원본과 대조한다. 존재 여부만으로 완료로 선언하지 않는다.
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN (
    'basic_comparison_rule_sets'::regclass, 'basic_comparison_profiles'::regclass,
    'comparison_supply_units'::regclass, 'comparison_paths'::regclass,
    'comparison_qualifications'::regclass, 'comparison_path_qualifications'::regclass,
    'comparison_options'::regclass, 'comparison_profile_rechecks'::regclass,
    'comparison_notice_rechecks'::regclass, 'comparison_latest_results'::regclass,
    'comparison_discoveries'::regclass
)
ORDER BY table_name, conname;

SELECT tgrelid::regclass AS table_name, tgname, tgenabled, pg_get_triggerdef(oid) AS definition
FROM pg_trigger
WHERE NOT tgisinternal AND tgname LIKE 'comparison_%'
ORDER BY table_name, tgname;

ROLLBACK;
