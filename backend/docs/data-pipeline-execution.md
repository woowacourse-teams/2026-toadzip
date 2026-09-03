# Data Pipeline Execution

## 실행 상태

- 파이프라인은 `단지 수집`, `단지 정제`, `공고 수집`, `공고 정제` 네 실행 단위로 구분한다.
- 단지 수집·정제와 공고 수집·정제는 각자의 최신 실행 상태를 독립적으로 보존한다.
- 관리자 파이프라인은 PostgreSQL advisory lock을 획득한 실행만 시작한다.
- 시작 직후 `RUNNING` 실행을 저장하고 단계 시작·완료마다 현재 상태를 갱신한다.
- 전체 단계가 끝나면 `COMPLETED`, API 호출 제한으로 건너뛴 단계가 있으면
  `COMPLETED_WITH_SKIPS`, 부분 실패나 서버 오류가 나면 `FAILED`와 실패 응답을 저장한다.
- 한 단계의 실패가 모두 외부 API 호출 제한으로 확인된 경우에만 단계와 응답을 보존하고 다음 단계를 실행한다.
- 호출 제한과 다른 실패가 섞이거나 실패 원인을 구분할 수 없으면 기존처럼 이후 단계를 실행하지 않는다.
- 상태 조회는 애플리케이션 메모리가 아니라 DB의 유형별 최신 실행을 사용한다.
- 완료 단계와 실패 응답을 보존하므로 다른 인스턴스나 클라이언트 재접속에서도 같은 결과를 조회한다.
- 실행 중에는 30초마다 DB lease를 갱신한다. 2분 동안 갱신되지 않고 advisory lock도 없으면 중단 실패로 복구한다.

## 스키마 배포

운영은 `ddl-auto=validate`이므로 애플리케이션 배포 전에 다음 SQL을 실행한다.

```text
src/main/resources/db/migration/V20260903_01__create_data_pipeline_executions.sql
src/main/resources/db/migration/V20260903_02__add_data_pipeline_skipped_steps.sql
```

```bash
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260903_01__create_data_pipeline_executions.sql
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260903_02__add_data_pipeline_skipped_steps.sql
```

SQL은 신규 테이블을 생성하고 기존 실행 테이블의 파이프라인 유형 컬럼을 안전하게 확장하므로 이전
애플리케이션과 함께 적용할 수 있다. 배포 후 실행 테이블과 완료 단계 테이블,
`idx_data_pipeline_execution_type_id` 인덱스를 확인한다.

롤백 시에는 이전 애플리케이션을 다시 배포하고 실행 이력 테이블은 보존한다. 테이블 삭제는 실행
이력 손실을 수반하므로 별도 백업과 승인 없이 수행하지 않는다.
