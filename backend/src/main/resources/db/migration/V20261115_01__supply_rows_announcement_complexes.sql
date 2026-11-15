-- 공고↔단지 연결을 공고 단지(announcement_complexes) 하나로 정한다(AnnouncementHousingComplex.LINKS). 관리자 등록·JSON 등록·
-- PDF 적용이 공고 단지 없이 만든 공급행에 공고 단지를 만들어 잇는다. 이 행들이 가리키던 단지는 이제 공고 단지의 연결로만 보인다.
--   - 공고 단지는 (공고, 단지명) 하나다. 같은 공고·같은 단지명의 행은 한 공고 단지에 든다.
--   - 새 공고 단지는 그 행들이 가리키던 단지에 이관 연결(MIGRATED)한다. 행마다 단지가 다르면 제외하지 않은 앞 행(표시 순서)의
--     단지로 잇는다.
--   - 같은 이름의 공고 단지가 이미 있으면 그것에 잇는다. 그 공고 단지가 단지에 이어지지 않았으면 행의 단지로 이관 연결한다.

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
       CASE WHEN o.housing_complex_id IS NOT NULL THEN 'V20261115_01' END,
       CASE WHEN o.housing_complex_id IS NOT NULL THEN CURRENT_TIMESTAMP END
FROM orphan_row_complexes o
ON CONFLICT (announcement_id, source_complex_name) DO NOTHING;

UPDATE announcement_complexes ac
SET housing_complex_id = o.housing_complex_id, link_method = 'MIGRATED', linked_by = 'V20261115_01',
    linked_at = CURRENT_TIMESTAMP
FROM orphan_row_complexes o
WHERE ac.announcement_id = o.announcement_id AND ac.source_complex_name = o.complex_name
  AND ac.housing_complex_id IS NULL AND o.housing_complex_id IS NOT NULL;

UPDATE supply_rows r
SET announcement_complex_id = ac.id
FROM announcement_complexes ac
WHERE r.announcement_complex_id IS NULL
  AND ac.announcement_id = r.announcement_id AND ac.source_complex_name = BTRIM(r.source_complex_name);
