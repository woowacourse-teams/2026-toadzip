-- 마이홈 장기전세 중 다가구주택과 주택형명이 매입임대인 원천은 매입형이라 단지 정제에서 제외한다.
-- 이미 자동 등록된 단지는 관리자 휴지통 규칙대로 옮겨 보존하고 복구할 수 있게 둔다.
-- 원천 행이 모두 제외 대상인 단지만 옮긴다.
-- 관리자 수정, 승인된 통합 연결, 삭제되지 않은 공고 연결이 있는 단지는 옮기지 않는다.
WITH excluded_sources AS (
    SELECT source.hsmp_sn || ':' || CASE btrim(source.suply_ty_nm)
               WHEN '행복주택' THEN 'HAPPY_HOUSING'
               WHEN '국민임대' THEN 'NATIONAL_RENTAL'
               WHEN '영구임대' THEN 'PERMANENT_RENTAL'
               WHEN '5년임대' THEN 'PUBLIC_RENTAL_5Y'
               WHEN '10년임대' THEN 'PUBLIC_RENTAL_10Y'
               WHEN '50년임대' THEN 'PUBLIC_RENTAL_50Y'
               WHEN '장기전세' THEN 'LONG_TERM_JEONSE'
               WHEN '통합공공임대' THEN 'INTEGRATED_PUBLIC_RENTAL'
               WHEN '재개발임대' THEN 'REDEVELOPMENT_RENTAL'
               WHEN '기타' THEN 'ETC'
           END AS source_complex_identifier
    FROM myhome_complex_source_rows source
    GROUP BY source.hsmp_sn, btrim(source.suply_ty_nm)
    HAVING bool_and((btrim(source.suply_ty_nm) = '장기전세' AND coalesce(btrim(source.house_ty_nm), '') = '다가구주택')
                    OR coalesce(btrim(source.style_nm), '') = '매입임대')
),
targets AS (
    SELECT complex.id
    FROM housing_complexes complex
    JOIN excluded_sources excluded ON excluded.source_complex_identifier = complex.source_complex_identifier
    WHERE complex.admin_deleted = FALSE
      AND complex.admin_modified = FALSE
      AND NOT EXISTS (
          SELECT 1 FROM myhome_complex_links link
          WHERE link.housing_complex_id = complex.id AND link.merge_id IS NOT NULL
      )
      AND NOT EXISTS (
          SELECT 1 FROM supply_rows supply
          JOIN announcements announcement ON announcement.id = supply.announcement_id
          WHERE supply.housing_complex_id = complex.id AND announcement.admin_deleted = FALSE
      )
),
trashed AS (
    UPDATE housing_complexes complex
    SET admin_deleted = TRUE, admin_updated_at = now(), version = complex.version + 1
    FROM targets
    WHERE complex.id = targets.id
    RETURNING complex.id, complex.name, complex.source_complex_identifier
)
INSERT INTO admin_data_changes (resource_type, resource_id, action, actor, occurred_at, before_value, after_value)
SELECT 'COMPLEX', id, 'DELETE', 'migration:V20261024_01', now(),
       jsonb_build_object('id', id, 'name', name, 'sourceIdentifier', source_complex_identifier,
                          'deleted', FALSE)::text,
       jsonb_build_object('id', id, 'name', name, 'sourceIdentifier', source_complex_identifier,
                          'deleted', TRUE, 'reason', '마이홈 매입형 원천 정제 제외')::text
FROM trashed;
