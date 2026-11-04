-- 정리 마이그레이션 적용 전 primary DB에서 실행한다. 데이터를 바꾸지 않고 대상 건수만 조회한다.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '10s';

SELECT current_database() AS database_name, current_setting('transaction_read_only') AS read_only;

SELECT count(*) AS lh_non_construction_rental_rows
FROM lh_announcement_catalog_entries
WHERE NOT (
    (btrim(coalesce(upper_announcement_type_code, ''), E' \t\r\n') = '06'
        AND btrim(coalesce(supply_info_type_code, ''), E' \t\r\n') IN ('060', '061', '062', '063'))
    OR (btrim(coalesce(upper_announcement_type_code, ''), E' \t\r\n') = '39'
        AND btrim(coalesce(announcement_type_code, ''), E' \t\r\n') IN ('42', '51'))
);

SELECT count(*) AS existing_sh_source_rows FROM sh_announcement_source;

SELECT count(*) AS sh_failures_to_close
FROM external_data_collection_failures
WHERE source = 'SH_ANNOUNCEMENT' AND status = 'PENDING';

ROLLBACK;
