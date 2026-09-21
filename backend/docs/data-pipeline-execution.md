# Data Pipeline Execution

## 실행 상태

- 파이프라인은 `단지 수집`, `단지 정제`, `공고 수집`, `공고 정제` 네 실행 단위로 구분한다.
- 단지 수집·정제와 공고 수집·정제는 각자의 최신 실행 상태를 독립적으로 보존한다.
- 관리자 파이프라인은 PostgreSQL advisory lock을 획득한 실행만 시작한다.
- 시작 직후 `RUNNING` 실행을 저장하고 단계 시작·완료마다 현재 상태를 갱신한다.
- 전체 단계가 끝나면 `COMPLETED`, API 호출 제한으로 건너뛴 단계가 있으면
  `COMPLETED_WITH_SKIPS`, 부분 실패나 서버 오류가 나면 `FAILED`와 실패 응답을 저장한다.
- 한 단계가 일부 원천 처리에 실패해도 성공한 데이터로 처리할 수 있는 나머지 단계를 한 번씩 계속 실행한다.
- 부분 실패가 여러 단계에서 발생하면 최초 부분 실패를 대표 실패로 보존하고, 모든 단계를 시도한 뒤 실행을 `FAILED`로 기록한다.
- 예상하지 못한 서버 오류가 발생하면 이후 단계를 실행하지 않고 즉시 `FAILED`로 기록한다.
- 한 단계의 실패가 모두 외부 API 호출 제한이면 해당 단계와 응답을 건너뜀으로 보존하고 다음 단계를 실행한다.
- 호출 제한은 재시도하지 않으며, 전국 병렬 수집 중이면 대기 중인 지역 요청도 취소한다.
- 호출 제한과 다른 실패가 섞인 결과는 부분 실패로 처리하되, 동일 실행에서 실패한 단계 전체를 다시 실행하지 않는다.
- 상태 조회는 애플리케이션 메모리가 아니라 DB의 유형별 최신 실행을 사용한다.
- 완료 단계와 실패 응답을 보존하므로 다른 인스턴스나 클라이언트 재접속에서도 같은 결과를 조회한다.
- 실행 중에는 30초마다 DB lease를 갱신한다. 2분 동안 갱신되지 않고 advisory lock도 없으면 중단 실패로 복구한다.

## 정기 실행 오케스트레이터

- 내부 스케줄러 하나가 공고와 단지의 현재 슬롯을 확인하고, 각각 `수집 → 정제` 순서로 실행한다.
- 수집 실행이 실제 DB 상태에서 `COMPLETED`가 된 경우에만 같은 슬롯의 정제를 시작한다. 실행 요청의 HTTP `202` 응답은 완료로 해석하지 않는다.
- 실행 중인 다른 파이프라인이 advisory lock을 보유하면 예외를 외부로 전파하지 않고 `execution_in_progress` 지연 사유, 관찰 시각과 다음 재시도 시각을 DB에 보존한다.
- 재시작 뒤 직전 슬롯이 아닌 과거 실행 이력이 확인되고 현재 슬롯 실행이 비어 있으면 현재 슬롯을 한 번 `RECOVERY`로 보충한다. 직전 슬롯이 있으면 일반 `SCHEDULED`로 처리하고, 현재 슬롯에 이미 실행 이력이 있으면 중복 실행하지 않는다.
- 실행 이력에는 `MANUAL`, `SCHEDULED`, `RECOVERY` 트리거, 예정 시각(`scheduledAt`), 수집-정제 연결 ID(`upstreamExecutionId`)를 보존한다.
- `FAILED`와 `COMPLETED_WITH_SKIPS` 수집은 정제를 자동 연결하지 않고 지연 사유를 기록한다. 자동 재시도가 없으므로 `nextRetryAt`은 비워 두며, 운영자가 실패 원인을 확인한 뒤 수동 실행한다.
- 스케줄별 최신 지연은 `GET /api/admin/ingest/pipelines/schedule-deferrals`와 `WARN` 구조화 로그·메트릭으로 확인한다. 실행이 시작되거나 이미 연결된 정제가 확인되면 해결 시각을 기록한다. 수신자 기반 외부 알림 전달은 OPS-05 범위로 남긴다.
- 기본 설정은 비활성화이며 운영 프로파일에서 활성화된다. 공고는 기본 6시간 슬롯, 단지는 기본 월요일 03:00(Asia/Seoul) 슬롯이다.

## 스키마 배포

운영은 `ddl-auto=validate`이므로 애플리케이션 배포 전에 다음 SQL을 실행한다.

```text
src/main/resources/db/migration/V20260903_01__create_data_pipeline_executions.sql
src/main/resources/db/migration/V20260903_02__add_data_pipeline_skipped_steps.sql
src/main/resources/db/migration/V20260919_01__add_data_pipeline_completed_step_reports.sql
src/main/resources/db/migration/V20260919_02__add_data_pipeline_partial_failure_reports.sql
src/main/resources/db/migration/V20260921_01__add_data_pipeline_schedule_metadata.sql
src/main/resources/db/migration/V20260921_02__create_data_pipeline_schedule_deferrals.sql
src/main/resources/db/migration/V20260921_03__allow_null_schedule_deferral_next_retry_at.sql
```

```bash
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260903_01__create_data_pipeline_executions.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260903_02__add_data_pipeline_skipped_steps.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260919_01__add_data_pipeline_completed_step_reports.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260919_02__add_data_pipeline_partial_failure_reports.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260921_01__add_data_pipeline_schedule_metadata.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260921_02__create_data_pipeline_schedule_deferrals.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260921_03__allow_null_schedule_deferral_next_retry_at.sql
```

SQL은 신규 테이블을 생성하고 기존 실행·완료 단계 테이블을 안전하게 확장하므로 이전
애플리케이션과 함께 적용할 수 있다. 배포 후 실행 테이블, 완료·건너뜀·부분 실패 단계 테이블,
완료 단계의 `completed_report` 컬럼, 정기 실행 메타데이터 컬럼·관련 인덱스와 최신 스케줄 지연 테이블을 확인한다. `next_retry_at`은 자동 재시도가 없는 지연을 표현하기 위해 `NULL`을 허용한다.

롤백 시에는 이전 애플리케이션을 다시 배포하고 실행 이력 테이블은 보존한다. 테이블 삭제는 실행
이력 손실을 수반하므로 별도 백업과 승인 없이 수행하지 않는다.
