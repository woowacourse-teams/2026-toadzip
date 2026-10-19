ALTER TABLE supply_rows ADD COLUMN myhome_source_row_identifier VARCHAR(255);

-- Preserve the pre-upgrade grouping once; subsequent presentation reordering never changes source ownership.
WITH ordered AS (
    SELECT r.id, r.announcement_id, r.display_order, r.source_supply_row_identifier,
           CASE WHEN NOT starts_with(r.source_supply_row_identifier, a.source_announcement_identifier || ':LH:')
                THEN r.source_supply_row_identifier END AS root_key,
           sum(CASE WHEN NOT starts_with(r.source_supply_row_identifier, a.source_announcement_identifier || ':LH:')
                    THEN 1 ELSE 0 END)
               OVER (PARTITION BY r.announcement_id ORDER BY r.display_order, r.id) AS source_group
    FROM supply_rows r JOIN announcements a ON a.id = r.announcement_id
    WHERE r.supply_group_id IS NULL
      AND r.source_supply_row_identifier NOT LIKE 'MANUAL:%'
      AND r.source_supply_row_identifier NOT LIKE 'ADMIN:%'
), owners AS (
    SELECT id, max(root_key) OVER (PARTITION BY announcement_id, source_group) AS source_key FROM ordered
)
UPDATE supply_rows r SET myhome_source_row_identifier = owners.source_key FROM owners WHERE owners.id = r.id;
UPDATE supply_rows r SET myhome_source_row_identifier = g.source_row_identifier
FROM supply_row_groups g WHERE r.supply_group_id = g.id;
