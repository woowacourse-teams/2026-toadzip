-- 수집 시 교체되는 행 ID 대신 식별정보와 동일 식별정보 안의 순번으로 연결한다.
-- IngestSourceRows의 LH_LEASE_CATALOG 키와 같아야 한다. 중복 행도 각각 보존한다.
CREATE TEMP TABLE lh_lease_comparison_keys ON COMMIT DROP AS
SELECT r.id::text AS old_key,
       'lh-lease:' || encode(sha256(convert_to(jsonb_build_array(
           r.area_name, r.supply_type_name, r.complex_label, r.exclusive_area
       )::text, 'UTF8')), 'hex') || ':' || row_number() OVER (
           PARTITION BY r.area_name, r.supply_type_name, r.complex_label, r.exclusive_area
           ORDER BY r.source_order, r.id
       ) AS new_key
FROM lh_lease_catalog_source_rows r
JOIN lh_lease_catalog_source_bundles b ON b.id = r.source_id
WHERE b.scope_key = 'ALL';

UPDATE complex_comparison_links link
SET source_key = keys.new_key
FROM lh_lease_comparison_keys keys
WHERE link.category = 'LH_LEASE_CATALOG' AND link.source_key = keys.old_key;

UPDATE admin_comparison_sources link
SET source_key = keys.new_key
FROM lh_lease_comparison_keys keys
WHERE link.category = 'LH_LEASE_CATALOG' AND link.source_key = keys.old_key;

-- 근거 원문·확인값·작성자는 유지하고 조회용 키만 이관한다.
-- 이미 소실된 행의 숫자 키는 추정 연결하지 않고 기존 소실 상태로 남긴다.
UPDATE admin_comparison_checks checked
SET evidence = (
    SELECT jsonb_agg(
        CASE WHEN keys.new_key IS NOT NULL
             THEN jsonb_set(item.value, '{sourceKey}', to_jsonb(keys.new_key))
             ELSE item.value END
        ORDER BY item.ordinality
    )
    FROM jsonb_array_elements(checked.evidence) WITH ORDINALITY item(value, ordinality)
    LEFT JOIN lh_lease_comparison_keys keys
      ON item.value ->> 'category' = 'LH_LEASE_CATALOG'
     AND item.value ->> 'sourceKey' = keys.old_key
)
WHERE EXISTS (
    SELECT 1 FROM jsonb_array_elements(checked.evidence) item(value)
    JOIN lh_lease_comparison_keys keys ON item.value ->> 'sourceKey' = keys.old_key
    WHERE item.value ->> 'category' = 'LH_LEASE_CATALOG'
);

DROP TABLE lh_lease_comparison_keys;
