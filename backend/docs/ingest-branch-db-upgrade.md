# 기존 DB의 수집 저장 구조 전환

기존 수집 원천은 폐기하고 다시 수집한다. 제품·관리자 확인값·실행 및 실패 감사 이력은 보존한다.
이미 적용한 Flyway SQL·버전·체크섬은 변경하지 않는다.

`20261004.07`은 이전의 기존 원천 보존 이관 버전이고, `20261005.01`은 원천 폐기 전환 버전이다.
이미 적용한 이력을 유지하기 위해 `07`은 남겨 둔다. 아래 사전 정리는 아직 `07`을 적용하지 않은 DB에서
대량 이관 후 곧바로 폐기하는 비용을 피하기 위한 절차다. `07`이 이미 적용된 DB는 사전 정리 대상이 아니다.

## 대상과 적용 순서

1. 대상 DB 이름과 Flyway 이력을 확인하고 전체 DB를 백업한다.
   구버전 서버와 worker를 모두 중지하고 관리자 쓰기·수집·정제를 차단한다.
   제품·계정·감사 이력의 건수도 기록한다. [서버 교체 조건](data-pipeline-execution.md)을 따른다.

   ```sql
   SELECT current_database();
   SELECT installed_rank, version, script, success
   FROM flyway_schema_history ORDER BY installed_rank;
   ```

2. `20261004.07`이 미적용이면 아래 사전 정리를 먼저 실행한다.
   이관 SQL이 폐기할 원천을 다시 옮기는 비용을 피하고, 삭제 전 ID 최댓값·시퀀스를 보존한다.
   이미 `07`이 성공한 DB와 새 DB에는 이 사전 정리를 실행하지 않는다.
3. 누락된 낮은 버전이 있으면 새 아티팩트를 이번에만 `--spring.flyway.out-of-order=true`로 기동한다.
   최신 버전까지 적용해야 새 원천 Entity의 Hibernate `validate`가 통과하므로
   `--spring.flyway.target=20260930.09`로 제한하지 않는다.

   | 기존 DB 최종 버전 | 보충할 낮은 버전 |
   |---|---|
   | `20260930.01` | `20260928.03`, `20260928.04`, `20260929.01` |
   | develop의 `20260930.09` | `20260930.01` |

   표와 다른 이력은 실제 누락 목록을 먼저 확인한다. 낮은 버전 누락이 없으면 기본 설정으로 기동한다.
4. `20261005.01`의 `success=true`와 Hibernate `validate`를 확인한다.
   사전 정리를 실행했다면 아래 ID 복원도 완료한다. 제품·계정·감사 이력 보존과 원천 초기화를 대조한다.
5. 일회성 `out-of-order` 인자를 제거하고 기본 설정으로 재기동한다.
   추가 migration이 없고 원천 조회가 정상인 것을 확인한 뒤 수집을 재개한다.

## 07 미적용 DB의 원천 사전 정리

아래 두 SQL은 `psql -X -v ON_ERROR_STOP=1 -v expected_db=<확인한_DB명>`으로 실행한다.
백업과 접근 제한된 작업 디렉터리를 먼저 준비하고 `umask 077`을 사용한다.
`/secure/cutover/source-id-bounds.csv`는 실제 작업 경로로 바꾸고 두 단계에서 같은 파일을 사용한다.
CSV에는 DB 이름과 ID 하한만 저장하며 백업과 함께 보관한다. 저장에 실패하면 삭제를 진행하지 않는다.

```sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT set_config('toadzip.expected_db', :'expected_db', true);
DO $$
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db') THEN
        RAISE EXCEPTION 'Unexpected database';
    END IF;
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '20261004.07' AND success) THEN
        RAISE EXCEPTION '07 already applied; preparation is unnecessary';
    END IF;
    IF EXISTS (SELECT 1 FROM data_pipeline_executions WHERE status = 'RUNNING')
            OR NOT pg_try_advisory_xact_lock(8432026090100001) THEN
        RAISE EXCEPTION 'Collection/refinement is running';
    END IF;
END $$;
CREATE TEMP TABLE source_reset_id_bounds (
    database_name TEXT NOT NULL, table_name TEXT PRIMARY KEY, high_watermark BIGINT NOT NULL
);
DO $$
DECLARE
    mapping RECORD;
    source_table TEXT;
    high_watermark BIGINT;
    maximum_id BIGINT;
    sequence_value BIGINT;
    sequence_name TEXT;
BEGIN
    FOR mapping IN SELECT * FROM (VALUES
        ('myhome_complex_source', 'myhome_complex_source_rows'),
        ('myhome_announcement_source', 'myhome_announcement_source_rows'),
        ('lh_catalog_source', 'lh_lease_catalog_source_rows'),
        ('lh_announcement_supply_source', 'lh_announcement_supply_rows'),
        ('lh_announcement_detail_source', 'lh_announcement_detail_rows')
    ) pairs(old_table, new_table) LOOP
        high_watermark := 1;
        FOREACH source_table IN ARRAY ARRAY[mapping.old_table, mapping.new_table] LOOP
            IF to_regclass('public.' || source_table) IS NOT NULL THEN
                EXECUTE format('SELECT COALESCE(MAX(id), 1) FROM public.%I', source_table) INTO maximum_id;
                sequence_name := pg_get_serial_sequence('public.' || source_table, 'id');
                EXECUTE format('SELECT last_value FROM %s', sequence_name) INTO sequence_value;
                high_watermark := GREATEST(high_watermark, maximum_id, sequence_value);
            END IF;
        END LOOP;
        INSERT INTO source_reset_id_bounds VALUES (current_database(), mapping.new_table, high_watermark);
    END LOOP;
END $$;
\copy source_reset_id_bounds TO '/secure/cutover/source-id-bounds.csv' WITH CSV HEADER
DELETE FROM myhome_complex_source;
DELETE FROM myhome_announcement_source;
DELETE FROM lh_catalog_source;
DELETE FROM lh_announcement_catalog_source;
DELETE FROM lh_announcement_detail_source;
DELETE FROM lh_announcement_supply_source;
COMMIT;
```

`07`은 원천 행의 `MAX(id)`를 기준으로 시퀀스를 설정하므로, 사전 삭제 후에는 기존 하한을 잃는다.
최신 migration을 적용한 다음 새 psql 세션에서 CSV를 읽어 5개 원천 행 시퀀스를 다시 올린다.
현재 값보다 낮추지 않으며 제품 ID와 실행 소유권 세대값은 변경하지 않는다.

```sql
BEGIN;
SELECT set_config('toadzip.expected_db', :'expected_db', true);
CREATE TEMP TABLE source_reset_id_bounds (
    database_name TEXT NOT NULL, table_name TEXT PRIMARY KEY, high_watermark BIGINT NOT NULL
);
\copy source_reset_id_bounds FROM '/secure/cutover/source-id-bounds.csv' WITH CSV HEADER
DO $$
DECLARE
    bound RECORD;
    sequence_name TEXT;
    sequence_value BIGINT;
BEGIN
    IF current_database() <> current_setting('toadzip.expected_db')
            OR NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '20261005.01' AND success)
            OR (SELECT COUNT(*) FROM source_reset_id_bounds) <> 5
            OR EXISTS (SELECT 1 FROM source_reset_id_bounds
                WHERE database_name <> current_database() OR high_watermark < 1
                OR table_name NOT IN ('myhome_complex_source_rows', 'myhome_announcement_source_rows',
                    'lh_lease_catalog_source_rows', 'lh_announcement_supply_rows', 'lh_announcement_detail_rows')) THEN
        RAISE EXCEPTION 'Unexpected database, migration state or ID bounds';
    END IF;
    IF EXISTS (SELECT 1 FROM data_pipeline_executions WHERE status = 'RUNNING')
            OR NOT pg_try_advisory_xact_lock(8432026090100001) THEN
        RAISE EXCEPTION 'Collection/refinement is running';
    END IF;
    FOR bound IN SELECT * FROM source_reset_id_bounds LOOP
        sequence_name := pg_get_serial_sequence('public.' || bound.table_name, 'id');
        EXECUTE format('SELECT last_value FROM %s', sequence_name) INTO sequence_value;
        PERFORM setval(sequence_name::regclass, GREATEST(bound.high_watermark, sequence_value), true);
    END LOOP;
END $$;
COMMIT;
```

도중에 연결이 끊겨도 CSV를 보관하고, ID 복원을 다시 실행한 뒤 수집을 재개한다.

## 검증과 복구

신규 DB·기존 버전 누락 보충·원천 폐기 후 데이터 보존은
`LocalProfileSchemaPersistenceTest`와 `DiscardCollectedSourcesMigrationTest`로 검증한다.
이는 운영 DB의 처리 시간이나 잠금 범위를 입증하지 않으므로 운영 규모의 복제 DB에서도 예행연습한다.

`20261005.01`은 구 원천 테이블을 삭제한다. 이전 앱만 다시 배포하는 롤백은 지원하지 않는다.
검증 실패 시 쓰기를 차단한 채 새 버전에서 전방 수정하거나, 백업 DB를 복원한 뒤 그 스키마와 맞는 앱을 기동한다.
이미 적용한 migration을 삭제하거나 체크섬을 덮어 성공으로 처리하지 않는다.
