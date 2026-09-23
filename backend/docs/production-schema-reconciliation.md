# 운영 DB 스키마 전환 점검 (2026-09-22)

## 목적과 범위

- 대상은 primary PostgreSQL DB `toadzip`의 `public` 스키마다. `toadzip_shared`는 포함하지 않는다.
- 운영 DB에는 이 점검 중 변경을 실행하지 않았다.
- 기존 SQL 17개를 현재 운영 데이터 복제본에서 수동 검증했다. 현재 저장소에서는 이를 통합 `V20260922_01`로 묶었다.
- 운영 데이터가 담긴 덤프는 EC2에만 보관하며 저장소나 채팅에 첨부하지 않는다.

## 확인한 현재 상태

- 운영 DB에는 33개 테이블이 있고 `flyway_schema_history`가 없다.
- `data_pipeline_executions`에 `execution_trigger`, `scheduled_at`, `upstream_execution_id`가 없고 `data_pipeline_schedule_deferrals` 테이블도 없다.
- 9월 17일의 실패 사유·공급 유형 `CHECK`, 9월 18일의 LH 소유권 컬럼, 9월 19일의 실패 수명주기 컬럼과 인덱스 등도 일부 빠져 있다.
- 테이블이 존재한다는 사실만으로 해당 `CREATE TABLE IF NOT EXISTS` SQL이 모두 적용되었다고 판단할 수 없다. 기존 테이블의 정의는 건너뛰기 때문이다.
- 운영 설정의 `ddl-auto=validate`는 스키마를 검사할 뿐 변경하지 않는다.

## 격리된 복제본 검증

- 운영 DB 크기: 93 MB. 운영 DB를 덤프하여 외부 포트를 열지 않은 `toadzip_rehearsal` 컨테이너에 복원했다.
- 복원 직후 실패 이력 행 수는 운영 조회와 일치했다: 단지 매핑 156, 공고 매핑 73, LH 보강 199.
- SQL 17개를 파일명 순으로 `ON_ERROR_STOP=1`로 실행했고 모두 완료했다.
- 실행 후 공고 89건 중 `lh_reception_place_owned=true`는 예상한 74건이며 NULL은 0건이었다.
- 공급 행 325건 중 LH 식별자가 있는 131건은 `owned=true`, `enriched=true`로 채워졌고 두 플래그의 NULL은 0건이었다. 131건 모두 LH 원천 행과 정확히 1건씩 연결되며 원천 미조회·중복 연결은 0건이다. `enriched=true`는 해당 SQL의 총세대수 대조 결과다.
- 실패 이력 156·73·199건에서 `last_occurred_at` 또는 `status`가 NULL인 행은 0건이었다.
- 파이프라인 실행 7건에서 `execution_trigger`가 NULL인 행은 0건이고 일정 유예 테이블이 생성됐다.

## 기존 SQL 실행 후 남는 차이

- `road_address_locations`에는 기존 SQL이 정의하는 좌표 쌍 `CHECK`가 생기지 않는다. 운영 DB에서 좌표가 한쪽만 있는 행은 0건으로 조회됐다.
- 파이프라인의 완료·건너뜀·부분 실패 자식 테이블 FK는 기존 SQL의 `ON DELETE CASCADE` 대신 기본 삭제 제한으로 남는다. 기존 SQL이 의도한 삭제 동작을 별도 보정 마이그레이션에서 적용한다. 현 코드에는 실행 이력 삭제 경로가 없다.
- `road_address_locations` 기본키 컬럼 순서와 일부 `id` 생성 방식은 SQL의 새 테이블 정의와 다르다. 키의 논리적 구성과 현재 사용 쿼리를 검토해 필요한 차이만 보정한다.

## Flyway 도입과 운영 전 남은 일

- 기존 DB는 Flyway 이력이 없으므로 `baseline` 명령으로 버전 `20260922.00`을 기록한 다음 통합 `V20260922_01`을 실행한다. 자동 baseline은 비활성화한다.
- 새 빈 DB는 운영 스키마 스냅샷과 기존 SQL의 누적 상태인 `B20260922_01`만 적용한다. 이 경로는 기존 운영 DB에 적용하지 않는다.
- 상세 실행 순서와 확인 쿼리는 [flyway-adoption.md](flyway-adoption.md)에 둔다.
- 스키마 전용 백업을 복원한 로컬 DB에서는 Flyway baseline·migrate와 새 빈 DB의 앱 `ddl-auto=validate` 기동을 확인했다. 운영 데이터가 있는 EC2 새 격리 복제본에서도 `BASELINE 20260922.00`과 통합 `V20260922_01` 적용, 공고·공급·실패 이력 건수와 보정 제약조건을 확인했다.
- 운영 데이터 복제본에 최신 앱을 직접 기동하는 확인과 운영 백업·복구 절차 점검은 남아 있다. 운영 DB 자체는 아직 변경하지 않았다.
