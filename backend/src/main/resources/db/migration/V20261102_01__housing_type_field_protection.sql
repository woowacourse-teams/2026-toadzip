-- 공고(PDF 보정)가 주택형 이름·공급면적·세대수를 공고 값으로 갱신한다. 관리자가 직접 바꾼 필드만 보호하고,
-- 더 이른 공고가 나중에 저장돼 최신 공고 값을 되돌리지 않도록 마지막으로 반영한 공고 게시일을 남긴다.
-- admin_modified는 휴지통과 이전 원천 동기화 보호에만 쓴다.
ALTER TABLE housing_types
    ADD COLUMN name_admin_modified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN household_count_admin_modified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN announcement_observed_at TIMESTAMP WITH TIME ZONE;

-- 기존 관리자 수정은 주택형 이력(단지 이력의 after_value.id)으로 실제로 바꾼 필드만 보호한다.
-- 관리자가 만든 주택형은 입력한 이름과, 입력했다면 세대수를 보호한다. 이력이 없는 관리자 수정 주택형은 보호하지 않는다.
UPDATE housing_types t SET
    name_admin_modified = EXISTS (
        SELECT 1 FROM admin_data_changes c
        WHERE c.resource_type = 'COMPLEX' AND c.resource_id = t.housing_complex_id
          AND c.action IN ('CREATE_HOUSING_TYPE', 'UPDATE_HOUSING_TYPE')
          AND CASE WHEN c.action IN ('CREATE_HOUSING_TYPE', 'UPDATE_HOUSING_TYPE')
              THEN (c.after_value::jsonb ->> 'id')::bigint END = t.id
          AND (c.action = 'CREATE_HOUSING_TYPE'
               OR c.action = 'UPDATE_HOUSING_TYPE'
                  AND (c.before_value::jsonb ->> 'name') IS DISTINCT FROM (c.after_value::jsonb ->> 'name'))),
    household_count_admin_modified = EXISTS (
        SELECT 1 FROM admin_data_changes c
        WHERE c.resource_type = 'COMPLEX' AND c.resource_id = t.housing_complex_id
          AND c.action IN ('CREATE_HOUSING_TYPE', 'UPDATE_HOUSING_TYPE')
          AND CASE WHEN c.action IN ('CREATE_HOUSING_TYPE', 'UPDATE_HOUSING_TYPE')
              THEN (c.after_value::jsonb ->> 'id')::bigint END = t.id
          AND (c.action = 'CREATE_HOUSING_TYPE' AND (c.after_value::jsonb ->> 'householdCount') IS NOT NULL
               OR c.action = 'UPDATE_HOUSING_TYPE'
                  AND (c.before_value::jsonb ->> 'householdCount')
                      IS DISTINCT FROM (c.after_value::jsonb ->> 'householdCount')))
WHERE t.admin_modified;
