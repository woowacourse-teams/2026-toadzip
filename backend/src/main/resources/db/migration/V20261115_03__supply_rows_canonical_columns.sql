-- 공고 공급행(정본)에서 원천(LH·옛 API 모양) 시절 열을 걷고 공고 단지·주택형(신청형) 값만 남긴다. 공급행의 단지명·단지는
-- 공고 단지의 이름·연결이고, 행은 신청형 이름과 전용·공급면적을 직접 갖는다. 공개 화면이 보이던 값은 그대로다.
--   1. 공고 단지가 없는 행에 공고 단지를 만들어 잇는다(V20261115_01과 같은 규칙).
--   2. 이름: 공고 표시명 → 연결 주택타입명 → 원천 주택형명(공개 화면의 표시 순서).
--      면적: 공고 면적을 고정한 행은 공고 면적, 아니면 연결 주택타입 면적, 없으면 LH 전용면적.
--   3. 공급 검토(admin_supply_reviews)가 확인한 주택형 값 중 표현만 바뀐 값(단지명·이름·단지·면적)을 새 표현으로 옮긴다.
--   4. 원천 열·LH 매칭 열·단지 열·면적 고정 표시를 지우고, 공고 단지를 필수로 하며 공고 단지 삭제를 막는다.

CREATE TEMPORARY TABLE orphan_row_complexes ON COMMIT DROP AS
SELECT DISTINCT ON (r.announcement_id, BTRIM(r.source_complex_name))
       r.announcement_id, BTRIM(r.source_complex_name) AS complex_name, r.housing_complex_id
FROM supply_rows r
WHERE r.announcement_complex_id IS NULL
ORDER BY r.announcement_id, BTRIM(r.source_complex_name), r.admin_excluded, r.housing_complex_id IS NULL,
         r.display_order, r.id;

INSERT INTO announcement_complexes (announcement_id, source_complex_name, present_in_source, housing_complex_id,
                                    link_method, linked_by, linked_at)
SELECT o.announcement_id, o.complex_name, true, o.housing_complex_id,
       CASE WHEN o.housing_complex_id IS NOT NULL THEN 'MIGRATED' END,
       CASE WHEN o.housing_complex_id IS NOT NULL THEN 'V20261115_03' END,
       CASE WHEN o.housing_complex_id IS NOT NULL THEN CURRENT_TIMESTAMP END
FROM orphan_row_complexes o
ON CONFLICT (announcement_id, source_complex_name) DO NOTHING;

UPDATE announcement_complexes ac
SET housing_complex_id = o.housing_complex_id, link_method = 'MIGRATED', linked_by = 'V20261115_03',
    linked_at = CURRENT_TIMESTAMP
FROM orphan_row_complexes o
WHERE ac.announcement_id = o.announcement_id AND ac.source_complex_name = o.complex_name
  AND ac.housing_complex_id IS NULL AND o.housing_complex_id IS NOT NULL;

UPDATE supply_rows r
SET announcement_complex_id = ac.id
FROM announcement_complexes ac
WHERE r.announcement_complex_id IS NULL
  AND ac.announcement_id = r.announcement_id AND ac.source_complex_name = BTRIM(r.source_complex_name);

-- 3을 위해 채우기 전의 검토 값(SupplyReviewSql.TARGET_VALUES의 옛 표현)을 둔다.
CREATE TEMPORARY TABLE old_review_values ON COMMIT DROP AS
SELECT r.id AS row_id,
       jsonb_build_object('complexName', r.source_complex_name,
           'name', COALESCE(r.announcement_housing_type_name, r.source_housing_type_name),
           'housingComplexId', r.housing_complex_id,
           'exclusiveArea', CASE WHEN r.area_override THEN trim_scale(r.announcement_exclusive_area) END,
           'supplyArea', CASE WHEN r.area_override THEN trim_scale(r.announcement_supply_area) END) AS v
FROM supply_rows r;

-- 2. 이름·면적 채우기. 지운 주택타입은 공개 화면에서도 보이지 않으므로 쓰지 않는다.
UPDATE supply_rows r
SET announcement_housing_type_name = COALESCE(r.announcement_housing_type_name, t.name, r.source_housing_type_name),
    announcement_exclusive_area = CASE WHEN r.area_override THEN r.announcement_exclusive_area
                                       ELSE COALESCE(t.exclusive_area, r.lh_exclusive_area) END,
    announcement_supply_area = CASE WHEN r.area_override THEN r.announcement_supply_area ELSE t.supply_area END
FROM supply_rows target
LEFT JOIN housing_types t ON t.id = target.housing_type_id AND NOT t.admin_deleted
WHERE target.id = r.id;

-- 3. 검토 값 옮기기. 검토가 확인한 값이 옛 표현의 지금 값과 같은 키만 새 표현으로 바꾸므로, 이미 낡은(STALE) 값은 낡은 채로 남는다.
UPDATE admin_supply_reviews review
SET checked_values = (review.checked_values::jsonb || COALESCE((
        SELECT jsonb_object_agg(field, fresh.v -> field) FROM jsonb_object_keys(fresh.v) field
        WHERE review.checked_values::jsonb -> field = old.v -> field), '{}'::jsonb))::text
FROM old_review_values old
JOIN supply_rows r ON r.id = old.row_id
JOIN announcement_complexes ac ON ac.id = r.announcement_complex_id
CROSS JOIN LATERAL (SELECT jsonb_build_object('complexName', ac.source_complex_name,
        'name', r.announcement_housing_type_name, 'housingComplexId', ac.housing_complex_id,
        'exclusiveArea', trim_scale(r.announcement_exclusive_area),
        'supplyArea', trim_scale(r.announcement_supply_area)) AS v) fresh
WHERE review.announcement_id = r.announcement_id AND review.target = 'row:' || r.id;

-- 4. 열 정리. 단지 열과 그 FK·색인, 원천 식별자 유일 제약은 열과 함께 지워진다.
ALTER TABLE supply_rows DROP CONSTRAINT ck_supply_rows_lh_match_status;
ALTER TABLE supply_rows
    DROP COLUMN source_complex_name,
    DROP COLUMN source_housing_type_name,
    DROP COLUMN source_supply_row_identifier,
    DROP COLUMN lh_source_supply_row_identifier,
    DROP COLUMN lh_exclusive_area,
    DROP COLUMN lh_match_status,
    DROP COLUMN matching_failure_reason,
    DROP COLUMN housing_complex_id,
    DROP COLUMN area_override;

ALTER TABLE supply_rows RENAME COLUMN announcement_housing_type_name TO housing_type_name;
ALTER TABLE supply_rows RENAME COLUMN announcement_exclusive_area TO exclusive_area;
ALTER TABLE supply_rows RENAME COLUMN announcement_supply_area TO supply_area;
ALTER TABLE supply_rows RENAME CONSTRAINT ck_announcement_supply_areas TO ck_supply_rows_areas;
ALTER TABLE supply_rows ALTER COLUMN housing_type_name SET NOT NULL;

ALTER TABLE supply_rows ALTER COLUMN announcement_complex_id SET NOT NULL;
ALTER TABLE supply_rows DROP CONSTRAINT supply_rows_announcement_complex_id_fkey;
ALTER TABLE supply_rows ADD CONSTRAINT supply_rows_announcement_complex_id_fkey
    FOREIGN KEY (announcement_complex_id) REFERENCES announcement_complexes (id) ON DELETE RESTRICT;
