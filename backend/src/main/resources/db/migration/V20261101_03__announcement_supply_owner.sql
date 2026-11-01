-- 공고별 공급정보의 주인. 이전 아티팩트가 만든 공고는 정제가 공급행을 덮지 않도록 공고문 소유로 둔다.
ALTER TABLE announcements ADD COLUMN supply_owner varchar(30) NOT NULL DEFAULT 'ANNOUNCEMENT_DOCUMENT';

-- 관리자가 공급정보를 고친 흔적이 없는 공고만 원천 소유다. 관리자 직접 등록 공고는 공고문 소유다.
-- 흔적: 관리자가 고치거나 뺀 공급행·공급대상, 나눈 공급행, 공급정보를 바꾼 관리자 이력.
UPDATE announcements announcement SET supply_owner = 'LH_SOURCE'
WHERE announcement.source_announcement_identifier NOT LIKE 'ADMIN\_ENTRY-%'
  AND NOT EXISTS (
      SELECT 1 FROM supply_rows supply_row
      WHERE supply_row.announcement_id = announcement.id
        AND (supply_row.admin_modified OR supply_row.admin_excluded OR supply_row.supply_group_id IS NOT NULL))
  AND NOT EXISTS (
      SELECT 1 FROM supply_targets target
      JOIN supply_rows supply_row ON supply_row.id = target.supply_row_id
      WHERE supply_row.announcement_id = announcement.id AND (target.admin_modified OR target.admin_excluded))
  AND NOT EXISTS (
      SELECT 1 FROM admin_data_changes change
      WHERE change.resource_type = 'ANNOUNCEMENT' AND change.resource_id = announcement.id
        AND change.action IN ('CORRECT_FROM_PDF', 'CORRECT_SUPPLY', 'UPDATE_SUPPLY', 'UPDATE_SUPPLY_TARGET',
            'SAVE_SUPPLY_MATCH', 'SPLIT_SUPPLY', 'EXCLUDE_SUPPLY_ROW', 'INCLUDE_SUPPLY_ROW'));

ALTER TABLE announcements ADD CONSTRAINT ck_announcements_supply_owner
    CHECK (supply_owner IN ('LH_SOURCE', 'ANNOUNCEMENT_DOCUMENT'));
