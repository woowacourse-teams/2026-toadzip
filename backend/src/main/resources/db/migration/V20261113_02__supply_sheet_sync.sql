-- 공고 공급표(V20261113_01)를 옛 공급 테이블에서 다시 만드는 함수와, 옛 테이블이 바뀔 때 그것을 부르는 트리거.
-- 옛 테이블을 쓰는 곳이 많아 하나라도 빠지면 새 표가 조용히 어긋나므로 쓰는 쪽이 아니라 DB가 따라간다(PDF로 보정한 공고는 예외, 아래).
-- 테스트는 엔티티로 만든 스키마에 이 파일을 그대로 다시 실행하므로(PostgresTestDatabaseInitializer), 여러 번 실행해도 되게 쓴다.
--
-- PDF로 보정한 공고(supply_sheet_from_document)는 공고문이 공급표의 정본이다. 트리거는 이 공고를 다시 만들지 않고, PDF 보정 적용과
-- 그 뒤 관리자 공급 수정이 이 함수를 직접 불러 공급표를 쓴다(SupplySheetWriter). 그래야 옛 테이블 쓰기가 공고문 공급표를 덮지 않는다.
-- 그래서 이 공고의 옛 테이블에 쓰는 애플리케이션 경로는 같은 트랜잭션에서 flush 뒤 SupplySheetWriter를 불러야 한다. 빠뜨리면 공급표가
-- 조용히 낡는다. 공급행을 지울 때만 트리거가 그 행의 칸과 비게 된 묶음을 지운다(supply_sheet_drop_row_cells, supply_sheet_prune).
-- supply_owner로 가르지 않는다. GH·SH·관리자 등록 공고는 처음부터 공고문 소유지만 PDF 보정 전에는 정제·등록이 옛 테이블에 쓴 값이 전부다.

ALTER TABLE announcements ADD COLUMN IF NOT EXISTS supply_sheet_from_document BOOLEAN NOT NULL DEFAULT false;

-- UPDATE_FROM_JSON은 PDF 보정 이력을 한 건으로 합치기 전(#454)의 PDF·JSON 보정 이력 이름이다. 그때 기본정보만 고친 공고는 LH 소유로
-- 남았을 수 있고, LH 정제가 옛 테이블에 계속 쓰므로 공고문 소유 공고만 표시한다.
UPDATE announcements a SET supply_sheet_from_document = true
WHERE NOT a.supply_sheet_from_document
  AND a.supply_owner = 'ANNOUNCEMENT_DOCUMENT'
  AND EXISTS (SELECT 1 FROM admin_data_changes c
              WHERE c.resource_type = 'ANNOUNCEMENT' AND c.resource_id = a.id
                AND c.action IN ('CORRECT_FROM_PDF', 'CONFIRM_FROM_PDF', 'UPDATE_FROM_JSON'));

-- 공고들의 공급표를 옛 테이블에서 다시 만든다.
--  1. 공급대상: 제외하지 않은 옛 대상의 (표기, 신청 조건)을 공고마다 한 번씩, 처음 나온 공급행·대상 순서로.
--     (공고, 표기, 신청 조건)이 같은 공급대상은 지우지 않고 다시 써서 ID를 지킨다. 신청자격이 이 ID를 참조한다. 칸이 하나도 남지 않은
--     공급대상만 지운다.
--  2. 칸: 옛 대상 하나가 칸 하나. 같은 행에 같은 (표기, 조건)이 두 번 있으면 먼저 나온 것만 칸이 되고 나머지 수는 그 칸에 더한다.
--     대상이 없는 공급행은 '전체' 칸 하나를 둔다(수량 묶음은 칸을 멤버로 가지므로 공급행에 칸이 있어야 수를 담는다).
--     관리자가 대상을 모두 뺀 공급행은 대상 구분이 없는 행이 아니므로 칸을 두지 않는다. 그 행의 모집수도 담지 않는다.
--  3. 수량 묶음: 대상만의 수 / 한 행 안의 대상 묶음(옛 공통 모집수) / 여러 행 묶음(supply_row_groups, 그 행들의 모든 칸) /
--     행 모집수 중 대상별로 나뉘지 않은 나머지(행의 모든 칸이 함께 쓴다. 칸이 대상 하나뿐이면 '전체' 칸을 더해 나머지가 그 대상의
--     수로 읽히지 않게 한다). 대상별 수의 합이 행 모집수보다 크면 행 모집수를 버리고 대상별 수를 둔다. 모집수 0도 0으로 남긴다.
--  4. 임대조건: 금액이 하나라도 있는 칸마다 하나. 금액이 같아도 합치지 않는다.
CREATE OR REPLACE FUNCTION supply_sheet_rebuild(p_ids BIGINT[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    quota_seq TEXT := pg_get_serial_sequence('supply_quotas', 'id');
    lease_seq TEXT := pg_get_serial_sequence('lease_terms', 'id');
    cell_seq TEXT := pg_get_serial_sequence('supply_targets', 'id');
BEGIN
    p_ids := ARRAY(SELECT DISTINCT u.id FROM unnest(p_ids) AS u(id) WHERE u.id IS NOT NULL ORDER BY 1);
    IF cardinality(p_ids) = 0 THEN
        RETURN;
    END IF;
    -- 같은 공고를 두 트랜잭션이 함께 다시 만들면 옛 대상 ID로 넣는 칸이 겹친다. 공고 순서대로 잠가 차례로 만든다.
    PERFORM 1 FROM announcements WHERE id = ANY (p_ids) ORDER BY id FOR NO KEY UPDATE;

    -- 작업 표는 세션에 한 번만 만들고 비워 다시 쓴다. 호출마다 만들고 지우면 한 트랜잭션에서 문장이 많을 때
    -- 만든 표마다 잠금이 커밋까지 남아 잠금 표가 넘친다(대상 300건 갱신에 잠금 1만여 개).
    IF to_regclass('pg_temp.sheet_old_targets') IS NULL THEN
        CREATE TEMPORARY TABLE sheet_old_targets (
            old_target_id BIGINT, supply_row_id BIGINT, announcement_id BIGINT, label TEXT, application_condition TEXT,
            supply_household_count INTEGER, reserve_count INTEGER, supply_count_group_id BIGINT, reserve_count_group_id BIGINT,
            rental_deposit NUMERIC, monthly_rent NUMERIC, converted_deposit NUMERIC, admin_modified BOOLEAN,
            sheet_order BIGINT, cell_order INTEGER
        );
        CREATE TEMPORARY TABLE sheet_cells (
            old_target_id BIGINT, supply_row_id BIGINT, announcement_id BIGINT, supply_household_count INTEGER,
            reserve_count INTEGER, supply_count_group_id BIGINT, reserve_count_group_id BIGINT, rental_deposit NUMERIC,
            monthly_rent NUMERIC, converted_deposit NUMERIC, admin_modified BOOLEAN, cell_order INTEGER,
            supply_target_id BIGINT, cell_id BIGINT
        );
        CREATE TEMPORARY TABLE sheet_quotas (
            id BIGINT, announcement_id BIGINT, kind VARCHAR(20), household_count INTEGER, admin_modified BOOLEAN
        );
        CREATE TEMPORARY TABLE sheet_quota_members (quota_id BIGINT, cell_id BIGINT);
        CREATE TEMPORARY TABLE sheet_rest (
            id BIGINT, supply_row_id BIGINT, announcement_id BIGINT, admin_modified BOOLEAN, rest INTEGER
        );
        CREATE TEMPORARY TABLE sheet_leases (
            id BIGINT, announcement_id BIGINT, rental_deposit NUMERIC, monthly_rent NUMERIC, converted_deposit NUMERIC,
            admin_modified BOOLEAN, cell_id BIGINT
        );
    END IF;
    DELETE FROM pg_temp.sheet_old_targets;
    DELETE FROM pg_temp.sheet_cells;
    DELETE FROM pg_temp.sheet_quotas;
    DELETE FROM pg_temp.sheet_quota_members;
    DELETE FROM pg_temp.sheet_rest;
    DELETE FROM pg_temp.sheet_leases;

    -- 외래 키의 CASCADE와 열 기본값에 기대지 않는다(테스트는 엔티티로 만든 스키마에서 이 함수를 쓴다).
    DELETE FROM supply_quota_members m USING supply_quotas q
    WHERE m.supply_quota_id = q.id AND q.announcement_id = ANY (p_ids);
    DELETE FROM supply_quotas WHERE announcement_id = ANY (p_ids);
    DELETE FROM lease_term_members m USING lease_terms l
    WHERE m.lease_terms_id = l.id AND l.announcement_id = ANY (p_ids);
    DELETE FROM lease_terms WHERE announcement_id = ANY (p_ids);
    DELETE FROM supply_row_targets c USING announcement_supply_targets st
    WHERE c.supply_target_id = st.id AND st.announcement_id = ANY (p_ids);

    INSERT INTO pg_temp.sheet_old_targets
    SELECT t.id AS old_target_id,
           r.id AS supply_row_id,
           r.announcement_id,
           btrim(t.target) AS label,
           COALESCE(btrim(t.application_condition), '') AS application_condition,
           t.supply_household_count,
           t.reserve_count,
           t.supply_count_group_id,
           t.reserve_count_group_id,
           t.rental_deposit,
           t.monthly_rent,
           t.converted_deposit,
           t.admin_modified,
           ROW_NUMBER() OVER (PARTITION BY r.announcement_id ORDER BY r.display_order, r.id, t.display_order, t.id)
               AS sheet_order,
           t.display_order AS cell_order
    FROM supply_targets t
    JOIN supply_rows r ON r.id = t.supply_row_id
    WHERE r.announcement_id = ANY (p_ids)
      AND NOT t.admin_excluded
      AND btrim(t.target) <> '';

    -- 옛 대상에서 오지 않는 공급대상('전체')은 아래에서 새로 넣을 때와 같은 값으로 되돌린다.
    UPDATE announcement_supply_targets SET display_order = 1000, admin_modified = false
    WHERE announcement_id = ANY (p_ids);

    INSERT INTO announcement_supply_targets (announcement_id, label, application_condition, display_order, admin_modified)
    SELECT announcement_id, label, application_condition,
           (ROW_NUMBER() OVER (PARTITION BY announcement_id ORDER BY MIN(sheet_order)) - 1)::INTEGER,
           BOOL_OR(admin_modified)
    FROM sheet_old_targets
    GROUP BY announcement_id, label, application_condition
    ON CONFLICT (announcement_id, label, application_condition)
        DO UPDATE SET display_order = EXCLUDED.display_order, admin_modified = EXCLUDED.admin_modified;

    INSERT INTO pg_temp.sheet_cells
    SELECT o.old_target_id, o.supply_row_id, o.announcement_id, o.supply_household_count, o.reserve_count,
           o.supply_count_group_id, o.reserve_count_group_id, o.rental_deposit, o.monthly_rent, o.converted_deposit,
           o.admin_modified, o.cell_order,
           st.id AS supply_target_id,
           FIRST_VALUE(o.old_target_id) OVER (
               PARTITION BY o.supply_row_id, st.id ORDER BY o.cell_order, o.old_target_id
           ) AS cell_id
    FROM sheet_old_targets o
    JOIN announcement_supply_targets st
      ON st.announcement_id = o.announcement_id
     AND st.label = o.label
     AND st.application_condition = o.application_condition;

    INSERT INTO supply_row_targets (id, supply_row_id, supply_target_id, display_order)
    SELECT old_target_id, supply_row_id, supply_target_id, cell_order
    FROM sheet_cells
    WHERE old_target_id = cell_id;

    INSERT INTO announcement_supply_targets (announcement_id, label, application_condition, display_order, admin_modified)
    SELECT DISTINCT r.announcement_id, '전체', '', 1000, false
    FROM supply_rows r
    WHERE r.announcement_id = ANY (p_ids)
      AND NOT EXISTS (SELECT 1 FROM supply_targets t WHERE t.supply_row_id = r.id AND btrim(t.target) <> '')
    ON CONFLICT (announcement_id, label, application_condition) DO NOTHING;

    INSERT INTO supply_row_targets (id, supply_row_id, supply_target_id, display_order)
    SELECT nextval(cell_seq), r.id, st.id, 0
    FROM supply_rows r
    JOIN announcement_supply_targets st
      ON st.announcement_id = r.announcement_id AND st.label = '전체' AND st.application_condition = ''
    WHERE r.announcement_id = ANY (p_ids)
      AND NOT EXISTS (SELECT 1 FROM supply_targets t WHERE t.supply_row_id = r.id AND btrim(t.target) <> '');

    -- 3-1. 대상 하나만의 수
    WITH own AS (
        SELECT nextval(quota_seq) AS id, announcement_id, kind, SUM(count)::INTEGER AS household_count,
               BOOL_OR(admin_modified) AS admin_modified, cell_id
        FROM (
            SELECT announcement_id, 'WINNER' AS kind, supply_household_count AS count, admin_modified, cell_id
            FROM sheet_cells WHERE supply_household_count IS NOT NULL
            UNION ALL
            SELECT announcement_id, 'RESERVE', reserve_count, admin_modified, cell_id
            FROM sheet_cells WHERE reserve_count IS NOT NULL
        ) counts
        GROUP BY announcement_id, kind, cell_id
    ), quotas AS (
        INSERT INTO sheet_quotas SELECT id, announcement_id, kind, household_count, admin_modified FROM own
    )
    INSERT INTO sheet_quota_members SELECT id, cell_id FROM own;

    -- 3-2. 한 행 안의 대상 여럿이 함께 쓰던 수(옛 공통 모집수)
    WITH grouped AS (
        SELECT nextval(quota_seq) AS id, r.announcement_id,
               CASE g.kind WHEN 'SUPPLY' THEN 'WINNER' ELSE 'RESERVE' END AS kind, g.household_count, g.id AS group_id,
               g.kind AS group_kind
        FROM supply_target_count_groups g
        JOIN supply_rows r ON r.id = g.supply_row_id
        WHERE r.announcement_id = ANY (p_ids)
          AND EXISTS (SELECT 1 FROM sheet_cells c WHERE c.supply_count_group_id = g.id OR c.reserve_count_group_id = g.id)
    ), quotas AS (
        INSERT INTO sheet_quotas SELECT id, announcement_id, kind, household_count, false FROM grouped
    )
    INSERT INTO sheet_quota_members
    SELECT DISTINCT q.id, c.cell_id
    FROM grouped q
    JOIN sheet_cells c
      ON (q.group_kind = 'SUPPLY' AND c.supply_count_group_id = q.group_id)
      OR (q.group_kind = 'RESERVE' AND c.reserve_count_group_id = q.group_id);

    -- 3-3. 여러 공급행이 함께 쓰던 모집수(옛 supply_row_groups): 그 행들의 모든 칸이 멤버다.
    WITH shared AS (
        SELECT nextval(quota_seq) AS id, g.announcement_id, g.shared_household_count, g.id AS group_id
        FROM supply_row_groups g
        WHERE g.announcement_id = ANY (p_ids)
          AND g.shared_household_count IS NOT NULL
          AND EXISTS (SELECT 1 FROM supply_rows r JOIN supply_row_targets c ON c.supply_row_id = r.id
                      WHERE r.supply_group_id = g.id)
    ), quotas AS (
        INSERT INTO sheet_quotas SELECT id, announcement_id, 'WINNER', shared_household_count, false FROM shared
    )
    INSERT INTO sheet_quota_members
    SELECT q.id, c.id
    FROM shared q
    JOIN supply_rows r ON r.supply_group_id = q.group_id
    JOIN supply_row_targets c ON c.supply_row_id = r.id;

    -- 3-4. 공급행 모집수 중 대상별 수로 나뉘지 않은 나머지
    INSERT INTO pg_temp.sheet_rest
    WITH row_counts AS (
        SELECT r.id AS supply_row_id, r.announcement_id, r.admin_modified, r.total_supply_household_count AS row_total,
               COALESCE((SELECT SUM(q.household_count)
                         FROM sheet_quotas q
                         WHERE q.kind = 'WINNER'
                           AND EXISTS (SELECT 1 FROM sheet_quota_members m
                                       JOIN supply_row_targets c ON c.id = m.cell_id
                                       WHERE m.quota_id = q.id AND c.supply_row_id = r.id)
                           AND NOT EXISTS (SELECT 1 FROM sheet_quota_members m
                                           JOIN supply_row_targets c ON c.id = m.cell_id
                                           WHERE m.quota_id = q.id AND c.supply_row_id <> r.id)), 0) AS counted
        FROM supply_rows r
        WHERE r.announcement_id = ANY (p_ids)
          AND r.supply_group_id IS NULL
          AND r.total_supply_household_count IS NOT NULL
          AND EXISTS (SELECT 1 FROM supply_row_targets c WHERE c.supply_row_id = r.id)
    )
    SELECT nextval(quota_seq) AS id, supply_row_id, announcement_id, admin_modified, (row_total - counted)::INTEGER AS rest
    FROM row_counts
    WHERE row_total > counted OR (row_total = 0 AND counted = 0);

    INSERT INTO announcement_supply_targets (announcement_id, label, application_condition, display_order, admin_modified)
    SELECT DISTINCT rr.announcement_id, '전체', '', 1000, false
    FROM sheet_rest rr
    JOIN supply_row_targets c ON c.supply_row_id = rr.supply_row_id
    JOIN announcement_supply_targets st ON st.id = c.supply_target_id AND st.label <> '전체'
    WHERE (SELECT COUNT(*) FROM supply_row_targets all_cells WHERE all_cells.supply_row_id = rr.supply_row_id) = 1
    ON CONFLICT (announcement_id, label, application_condition) DO NOTHING;

    INSERT INTO supply_row_targets (id, supply_row_id, supply_target_id, display_order)
    SELECT nextval(cell_seq), rr.supply_row_id, everyone.id, 1000
    FROM sheet_rest rr
    JOIN supply_row_targets c ON c.supply_row_id = rr.supply_row_id
    JOIN announcement_supply_targets st ON st.id = c.supply_target_id AND st.label <> '전체'
    JOIN announcement_supply_targets everyone
      ON everyone.announcement_id = rr.announcement_id AND everyone.label = '전체' AND everyone.application_condition = ''
    WHERE (SELECT COUNT(*) FROM supply_row_targets all_cells WHERE all_cells.supply_row_id = rr.supply_row_id) = 1;

    INSERT INTO sheet_quotas SELECT id, announcement_id, 'WINNER', rest, admin_modified FROM sheet_rest;
    INSERT INTO sheet_quota_members
    SELECT rr.id, c.id FROM sheet_rest rr JOIN supply_row_targets c ON c.supply_row_id = rr.supply_row_id;

    INSERT INTO supply_quotas (id, announcement_id, kind, household_count, admin_modified)
    SELECT id, announcement_id, kind, household_count, admin_modified FROM sheet_quotas;
    INSERT INTO supply_quota_members (supply_quota_id, supply_row_target_id)
    SELECT quota_id, cell_id FROM sheet_quota_members;

    -- 4. 임대조건
    INSERT INTO pg_temp.sheet_leases
    SELECT nextval(lease_seq) AS id, announcement_id, rental_deposit, monthly_rent, converted_deposit, admin_modified, cell_id
    FROM sheet_cells
    WHERE old_target_id = cell_id
      AND (rental_deposit IS NOT NULL OR monthly_rent IS NOT NULL OR converted_deposit IS NOT NULL);

    INSERT INTO lease_terms (id, announcement_id, rental_deposit, monthly_rent, converted_deposit, admin_modified)
    SELECT id, announcement_id, rental_deposit, monthly_rent, converted_deposit, admin_modified FROM sheet_leases;
    INSERT INTO lease_term_members (lease_terms_id, supply_row_target_id)
    SELECT id, cell_id FROM sheet_leases;

    DELETE FROM announcement_supply_targets st
    WHERE st.announcement_id = ANY (p_ids)
      AND NOT EXISTS (SELECT 1 FROM supply_row_targets c WHERE c.supply_target_id = st.id);
END $$;

-- PDF로 보정한 공고의 공급행을 지울 때 쓴다. 지우기 전에 그 행의 칸과 멤버를 먼저 지운다(외래 키 CASCADE는 문장 트리거보다 늦게
-- 끝날 수 있고, 테스트의 엔티티 스키마에는 멤버 CASCADE가 없다). 남은 칸의 수·금액은 그대로 둔다.
CREATE OR REPLACE FUNCTION supply_sheet_drop_row_cells() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM announcements a WHERE a.id = OLD.announcement_id AND a.supply_sheet_from_document) THEN
        DELETE FROM supply_quota_members m USING supply_row_targets c
        WHERE m.supply_row_target_id = c.id AND c.supply_row_id = OLD.id;
        DELETE FROM lease_term_members m USING supply_row_targets c
        WHERE m.supply_row_target_id = c.id AND c.supply_row_id = OLD.id;
        DELETE FROM supply_row_targets WHERE supply_row_id = OLD.id;
    END IF;
    RETURN OLD;
END $$;

-- 칸을 지운 뒤 멤버가 없어진 묶음·임대조건과 칸이 없어진 공급대상을 지운다.
CREATE OR REPLACE FUNCTION supply_sheet_prune(p_ids BIGINT[]) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF cardinality(p_ids) = 0 THEN
        RETURN;
    END IF;
    DELETE FROM supply_quotas q WHERE q.announcement_id = ANY (p_ids)
      AND NOT EXISTS (SELECT 1 FROM supply_quota_members m WHERE m.supply_quota_id = q.id);
    DELETE FROM lease_terms l WHERE l.announcement_id = ANY (p_ids)
      AND NOT EXISTS (SELECT 1 FROM lease_term_members m WHERE m.lease_terms_id = l.id);
    DELETE FROM announcement_supply_targets st WHERE st.announcement_id = ANY (p_ids)
      AND NOT EXISTS (SELECT 1 FROM supply_row_targets c WHERE c.supply_target_id = st.id);
END $$;

-- 옛 테이블이 바뀐 문장마다 바뀐 공고의 공급표를 다시 만든다. PDF로 보정한 공고는 건너뛴다(머리 주석). TG_ARGV[0]: 'announcement'면 그 행의 announcement_id,
-- 'row'면 supply_row_id로 공고를 찾는다. 공급행이 지워져 공고를 찾지 못하는 대상 변경은 공급행 삭제 트리거가 다시 만든다.
CREATE OR REPLACE FUNCTION supply_sheet_sync() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    ids BIGINT[] := ARRAY[]::BIGINT[];
BEGIN
    IF TG_TABLE_NAME = 'supply_rows' AND TG_OP = 'UPDATE' THEN
        -- 공급행의 매칭·단지 같은 열만 바뀐 갱신은 공급표와 무관하므로 다시 만들지 않는다.
        ids := ARRAY(
            SELECT unnest(ARRAY[o.announcement_id, n.announcement_id])
            FROM old_rows o JOIN new_rows n ON n.id = o.id
            WHERE (o.announcement_id, o.display_order, o.total_supply_household_count, o.supply_group_id, o.admin_modified)
                  IS DISTINCT FROM
                  (n.announcement_id, n.display_order, n.total_supply_household_count, n.supply_group_id, n.admin_modified));
    ELSE
        IF TG_OP IN ('INSERT', 'UPDATE') THEN
            IF TG_ARGV[0] = 'announcement' THEN
                ids := ids || ARRAY(SELECT announcement_id FROM new_rows);
            ELSE
                ids := ids || ARRAY(SELECT r.announcement_id FROM new_rows n JOIN supply_rows r ON r.id = n.supply_row_id);
            END IF;
        END IF;
        IF TG_OP IN ('UPDATE', 'DELETE') THEN
            IF TG_ARGV[0] = 'announcement' THEN
                ids := ids || ARRAY(SELECT announcement_id FROM old_rows);
            ELSE
                ids := ids || ARRAY(SELECT r.announcement_id FROM old_rows o JOIN supply_rows r ON r.id = o.supply_row_id);
            END IF;
        END IF;
    END IF;
    PERFORM supply_sheet_rebuild(ARRAY(
        SELECT a.id FROM announcements a WHERE a.id = ANY (ids) AND NOT a.supply_sheet_from_document));
    -- PDF로 보정한 공고도 공급행이 지워지면 그 행의 칸은 남을 수 없다(supply_sheet_drop_row_cells). 비게 된 묶음을 지운다.
    IF TG_TABLE_NAME = 'supply_rows' AND TG_OP = 'DELETE' THEN
        PERFORM supply_sheet_prune(ARRAY(
            SELECT a.id FROM announcements a WHERE a.id = ANY (ids) AND a.supply_sheet_from_document));
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS trg_supply_sheet_rows_insert ON supply_rows;
CREATE TRIGGER trg_supply_sheet_rows_insert AFTER INSERT ON supply_rows
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('announcement');
DROP TRIGGER IF EXISTS trg_supply_sheet_rows_update ON supply_rows;
CREATE TRIGGER trg_supply_sheet_rows_update AFTER UPDATE ON supply_rows
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('announcement');
DROP TRIGGER IF EXISTS trg_supply_sheet_rows_drop_cells ON supply_rows;
CREATE TRIGGER trg_supply_sheet_rows_drop_cells BEFORE DELETE ON supply_rows
    FOR EACH ROW EXECUTE FUNCTION supply_sheet_drop_row_cells();
DROP TRIGGER IF EXISTS trg_supply_sheet_rows_delete ON supply_rows;
CREATE TRIGGER trg_supply_sheet_rows_delete AFTER DELETE ON supply_rows
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('announcement');

DROP TRIGGER IF EXISTS trg_supply_sheet_targets_insert ON supply_targets;
CREATE TRIGGER trg_supply_sheet_targets_insert AFTER INSERT ON supply_targets
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('row');
DROP TRIGGER IF EXISTS trg_supply_sheet_targets_update ON supply_targets;
CREATE TRIGGER trg_supply_sheet_targets_update AFTER UPDATE ON supply_targets
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('row');
DROP TRIGGER IF EXISTS trg_supply_sheet_targets_delete ON supply_targets;
CREATE TRIGGER trg_supply_sheet_targets_delete AFTER DELETE ON supply_targets
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('row');

DROP TRIGGER IF EXISTS trg_supply_sheet_count_groups_insert ON supply_target_count_groups;
CREATE TRIGGER trg_supply_sheet_count_groups_insert AFTER INSERT ON supply_target_count_groups
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('row');
DROP TRIGGER IF EXISTS trg_supply_sheet_count_groups_update ON supply_target_count_groups;
CREATE TRIGGER trg_supply_sheet_count_groups_update AFTER UPDATE ON supply_target_count_groups
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('row');
DROP TRIGGER IF EXISTS trg_supply_sheet_count_groups_delete ON supply_target_count_groups;
CREATE TRIGGER trg_supply_sheet_count_groups_delete AFTER DELETE ON supply_target_count_groups
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('row');

DROP TRIGGER IF EXISTS trg_supply_sheet_row_groups_insert ON supply_row_groups;
CREATE TRIGGER trg_supply_sheet_row_groups_insert AFTER INSERT ON supply_row_groups
    REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('announcement');
DROP TRIGGER IF EXISTS trg_supply_sheet_row_groups_update ON supply_row_groups;
CREATE TRIGGER trg_supply_sheet_row_groups_update AFTER UPDATE ON supply_row_groups
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('announcement');
DROP TRIGGER IF EXISTS trg_supply_sheet_row_groups_delete ON supply_row_groups;
CREATE TRIGGER trg_supply_sheet_row_groups_delete AFTER DELETE ON supply_row_groups
    REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION supply_sheet_sync('announcement');

SELECT supply_sheet_rebuild(ARRAY(SELECT id FROM announcements));
