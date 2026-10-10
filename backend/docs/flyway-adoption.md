# Flyway 적용

대상은 primary DB `toadzip`의 `public` 스키마다. shared DB `toadzip_shared`에는 적용하지 않는다.
백업과 복제본 검증을 마친 뒤 애플리케이션을 기동한다.

## DB별 적용 경로

| DB 상태 | 경로 |
|---|---|
| 기존 테이블이 있고 Flyway 이력이 없음 | `20260922.00` 자동 baseline 후 통합 `V20260922_01`부터 적용 |
| 새 빈 DB | 누적 스키마 `B20260922_01`과 후속 `V` 적용 |
| Flyway 이력이 있음 | 미적용 `V`만 실행 |

`baseline`은 기존 DB에 기준선을 기록하는 작업이고 `B`는 새 DB의 누적 스키마 스크립트다.
`baseline-on-migrate=true`는 이력이 없는 비어 있지 않은 스키마에만 적용된다.
기준선 기록은 기존 구조·데이터의 검증이나 실패한 마이그레이션의 복구를 대신하지 않는다.

## 기존 로그인 제약 처리

### 대상 확인

Flyway 이력이 없고 `uk_users_login_identifier`가 `UNIQUE (login_identifier)`로 존재하는 DB만 아래 절차를 따른다.
제약이 없거나 정의가 다르거나 Flyway 이력이 있으면 상태를 조사한 뒤 적용 경로를 정한다.

### 적용 순서

1. 주 DB 이름·주소를 확인하고 앱 쓰기를 중지한다. 백업과 복원 가능성을 확인한다.
2. [준비 SQL](../../scripts/flyway-login-constraint-prepare.sql)로 제약 이름을 `uk_users_login_identifier_pre_flyway`로 바꾼다. 고유성은 유지된다.
3. 새 앱을 기동해 자동 baseline과 후속 `V`를 적용한다. `V20260922_02`가 원래 이름의 제약을 만든다.
4. 성공 이력과 두 제약 정의를 확인한 뒤 [마무리 SQL](../../scripts/flyway-login-constraint-finalize.sql)로 이전 제약을 제거한다.
5. [알림 스키마 검사](../../docs/NOTIFICATION_DATA.md#개발운영-db-확인)를 실행하고 종료 코드 0을 확인한다.

두 SQL은 DB 이름·테이블·제약·중복 식별자와 적용 이력을 검사하며 조건이 다르면 중단한다.
같은 DB에서 재실행할 수 있다. 실행 명령은 아래 형식을 사용한다.

```text
psql -X -v ON_ERROR_STOP=1 -v expected_db=<확인한_DB_이름> -f <스크립트>
```

DB 인증은 접근 통제된 `PGPASSFILE`이나 비밀번호 프롬프트를 사용한다. 접속 정보·실행 결과는 저장소에 넣지 않는다.

## 복제본 검증

1. 백업을 격리된 복제본에 복원하고 DB 이름·Flyway 이력 유무를 확인한다.
2. 이력이 없는 기존 DB 복제본에는 `20260922.00` 기준선과 통합 `V20260922_01`을 적용한다. `B`가 실행되면 안 된다.
3. 좌표 제약과 파이프라인 자식 테이블 3개의 `ON DELETE CASCADE`를 확인한다. 공고·공급·실패·실행 행 수와 업무 값을 백업과 대조한다.
4. 최신 앱의 Hibernate `validate` 기동과 주요 조회를 확인한다.
5. 새 빈 DB도 별도로 기동해 `B20260922_01`과 후속 `V` 성공 이력을 확인한다.

## 배포와 실패 대응

복제본 검증 후 실제 대상 DB에서 같은 적용 경로를 사용한다.
기동 후 [읽기 전용 점검 SQL](../../docs/queries/notification-schema-status.sql)로 DB 이름·성공 이력·테이블·컬럼·제약을 확인한다.

기동 실패 시 원인을 조사한다. 운영 DB에서 마이그레이션을 임의 재실행하거나 `clean`·`repair`하지 않는다.
적용된 `V`·`B` 파일은 수정하지 않고 새 변경을 후속 `V`로 추가한다.
수집 구조 전환과 누락 버전 적용은 [기존 수집 DB 전환](ingest-branch-db-upgrade.md)을 따른다.
