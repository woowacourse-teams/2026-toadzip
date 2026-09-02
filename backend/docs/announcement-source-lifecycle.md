# Announcement Source Lifecycle

## 정책

- 마이홈 공고 수집 한 번에 UUID 형식의 `runId`를 하나 발급한다.
- 조회된 원천은 `lastSeenRunId`와 기존 `collectedAt`을 마지막 확인 실행과 시각으로 기록한다.
- 모든 공급유형의 전체 페이지가 성공한 실행만 미조회 횟수를 증가시킨다.
- 한 번 미조회된 원천은 활성 상태를 유지하고, 두 번 연속 미조회되면 비활성화한다.
- 비활성 원천이 다시 조회되면 활성화하고 미조회 횟수를 0으로 초기화한다.
- 비활성화는 원천과 정제 공고를 삭제하지 않으며 과거 공고 상세 조회를 유지한다.

## 스키마 배포

운영은 `ddl-auto=validate`이므로 애플리케이션 배포 전에 다음 SQL을 실행한다.

```text
src/main/resources/db/migration/V20260902_01__add_myhome_announcement_source_lifecycle.sql
```

```bash
psql "$DATABASE_URL" --set ON_ERROR_STOP=1 \
  --file src/main/resources/db/migration/V20260902_01__add_myhome_announcement_source_lifecycle.sql
```

SQL은 기존 행을 활성·미조회 0회로 backfill한다. 이전 애플리케이션은 추가 컬럼을 사용하지 않으므로
SQL을 먼저 적용하는 동안 호환된다.

배포 후 `last_seen_run_id`, `consecutive_miss_count`, `active` 컬럼과 `NOT NULL` 제약을 확인한다.
롤백 시에는 이전 애플리케이션을 다시 배포하고 추가 컬럼은 보존한다. 컬럼 삭제는 이력 손실을
수반하므로 별도 백업과 승인 없이 수행하지 않는다.
