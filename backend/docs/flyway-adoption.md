# Flyway 도입: 기존 PostgreSQL DB

대상은 primary DB `toadzip`의 `public` 스키마다. shared DB `toadzip_shared`는 대상이 아니다.

## 경로

| DB 상태 | 적용 경로 |
|---|---|
| 기존 테이블이 있고 Flyway 이력이 없음 | 앱 시작 시 `20260922.00` 자동 baseline → 통합 `V20260922_01`부터 순서대로 적용 |
| 새 빈 DB | 누적 스키마 `B20260922_01`과 그보다 새 버전의 `V` 적용 |
| Flyway 이력이 있음 | 기록된 버전 다음의 `V`만 실행 |

`baseline` 명령과 `B` 스크립트는 서로 다른 기능이다. `baseline-on-migrate=true`와
`baseline-version=20260922.00`은 **이력이 없는 비어 있지 않은 스키마**에만 자동 기준선을 기록한다.
기존 DB에서 `B`가 실행되면 테이블 생성이 충돌하므로, 백업과 복제본 검증을 먼저 마친다.
자동 기준선은 기존 테이블의 구조·데이터가 올바른지 검사하거나 실패한 마이그레이션을 고쳐 주지 않는다.
배포 전후 주 DB는 [읽기 전용 점검 쿼리](../../docs/queries/notification-schema-status.sql)로 확인한다.

기존 DB에 `uk_users_login_identifier` 제약이 이미 있으면 `V20260922_02`가 같은 이름의 제약을 추가하려다 실패한다. 2026-09-23 로컬 DB에서 확인된 상태다. 해당 DB에서는 다음 순서를 적용한다.

1. 해당 환경의 주 DB 이름·주소를 확인하고 앱 쓰기를 중지한다. 백업과 복원 가능성을 확인한다. 운영 DB에는 복제본에서 같은 절차를 먼저 시험한다.
2. Flyway 이력이 **없고**, 기존 제약이 `UNIQUE (login_identifier)`인 DB에서만 `scripts/flyway-login-constraint-prepare.sql`을 실행한다. 스크립트는 DB 이름·기존 테이블·제약 정의·중복 식별자를 검증하고 제약을 `uk_users_login_identifier_pre_flyway`로 바꾼다. 고유성은 계속 유지된다. 조건이 다르면 오류로 중단한다.
3. 새 앱을 기동한다. 이력이 없는 기존 DB에는 `20260922.00` 자동 baseline과 후속 `V`가 적용된다. `V20260922_02`는 원래 이름의 제약을 새로 만든다. 앱 시작이 실패하면 원인을 조사하고 마이그레이션을 임의로 재실행하거나 `repair`하지 않는다.
4. 새 앱의 Flyway 이력과 스키마를 확인한 뒤 `scripts/flyway-login-constraint-finalize.sql`을 실행한다. 이 스크립트는 `V20260922_02` 성공 기록과 두 제약 정의가 같을 때만 이전 제약을 제거한다. 준비·마무리 스크립트는 같은 DB에서 재실행해도 안전하다.
5. `scripts/check-notification-schema.sh dev|prod`가 성공해야 해당 환경의 알림 스키마 적용 완료로 기록한다. 운영자는 출력의 DB 주소·이름·시각을 배포 기록에 남긴다.

두 SQL은 `psql -X -v ON_ERROR_STOP=1 -v expected_db=<확인한_DB_이름> -f <스크립트>`로 실행한다. DB 인증은 서버의 접근 통제된 `PGPASSFILE` 또는 비밀번호 프롬프트를 사용한다. 실제 개발·운영 DB 접속 정보나 실행 결과는 저장소에 넣지 않는다. 제약이 없는 DB, 이미 Flyway 이력이 있는 DB, 정의가 다른 DB에는 준비 SQL을 실행하지 않고 상태를 먼저 조사한다.

## 복제본에서 확인할 순서

1. 운영 복제본의 `current_database()`가 `toadzip_rehearsal`인지 확인한다. 복제본에 `flyway_schema_history`가 없어야 한다.
2. 복제본에 Flyway CLI의 `baseline`을 버전 `20260922.00`으로 한 번 실행한다. `flyway_schema_history`에 해당 버전의 `BASELINE`이 기록됐는지 확인한다.
3. 동일한 마이그레이션 파일과 연결 정보로 `migrate`를 실행한다. 통합 `V20260922_01`이 성공해야 하며 `B`는 실행되면 안 된다.
4. 보정 후 좌표 제약조건과 파이프라인 자식 테이블 3개의 `ON DELETE CASCADE`를 확인한다. 데이터 건수와 [운영 스키마 전환 점검](production-schema-reconciliation.md)의 비즈니스 값도 다시 확인한다.
5. 최신 애플리케이션을 복제본에 연결해 `ddl-auto=validate` 기동과 주요 조회를 확인한다. 새 빈 DB도 별도로 기동해 `B20260922_01`과 후속 `V` 이력이 성공하는지 확인한다.

현재 EC2의 덤프로 시험할 때는 아래 명령을 사용한다. 먼저 로컬에서 최신 `migration/` 디렉터리를 EC2의 `~/toadzip-migration-audit/migration`에 **삭제 동기화**한다. `scp -r`만 쓰면 EC2에 남은 기존 V 파일 17개가 삭제되지 않는다. `rsync --delete`의 대상은 이 마이그레이션 디렉터리 하나이며 부모의 데이터 덤프는 건드리지 않는다.

```bash
rsync -av --delete -e "ssh -i $HOME/source/key-toadzip.pem" \
  src/main/resources/db/migration/ \
  ec2-user@<현재-EC2-공인-IP>:/home/ec2-user/toadzip-migration-audit/migration/
```

기존 `toadzip-migration-rehearsal`에는 이미 17개 SQL을 수동으로 적용했으므로, 별도의 새 복제본을 만든다. 아래 명령은 운영 DB 주소를 사용하지 않는다.

```bash
docker run --rm -d --name toadzip-flyway-rehearsal --network none \
  -e POSTGRES_PASSWORD=local-rehearsal-only \
  -e POSTGRES_DB=toadzip_rehearsal postgres:17-alpine

docker exec toadzip-flyway-rehearsal \
  pg_isready -U postgres -d toadzip_rehearsal

docker exec -i toadzip-flyway-rehearsal \
  pg_restore -U postgres -d toadzip_rehearsal \
  --no-owner --no-privileges --exit-on-error \
  < "$HOME/toadzip-migration-audit/toadzip.dump"

docker run --rm --network container:toadzip-flyway-rehearsal \
  -v "$HOME/toadzip-migration-audit/migration:/flyway/sql:ro" \
  -e FLYWAY_URL=jdbc:postgresql://localhost:5432/toadzip_rehearsal \
  -e FLYWAY_USER=postgres \
  -e FLYWAY_PASSWORD=local-rehearsal-only \
  flyway/flyway:12.4.0 -baselineVersion=20260922.00 baseline

docker run --rm --network container:toadzip-flyway-rehearsal \
  -v "$HOME/toadzip-migration-audit/migration:/flyway/sql:ro" \
  -e FLYWAY_URL=jdbc:postgresql://localhost:5432/toadzip_rehearsal \
  -e FLYWAY_USER=postgres \
  -e FLYWAY_PASSWORD=local-rehearsal-only \
  flyway/flyway:12.4.0 migrate

docker exec toadzip-flyway-rehearsal \
  psql -X -U postgres -d toadzip_rehearsal -c \
  "SELECT installed_rank, version, type, script, success FROM public.flyway_schema_history ORDER BY installed_rank"
```

이력은 `BASELINE` 버전 `20260922.00`, 통합 `V20260922_01` 순서로 나와야 한다. `B20260922_01`은 이력에 나타나면 안 된다. 스키마 전용 백업을 복원한 로컬 DB와 운영 데이터가 있는 EC2 격리 복제본 모두에서 이 순서를 확인했다. EC2 복제본의 공고 89건·공급 행 325건은 보존됐고 LH 소유권·실패 이력·파이프라인 컬럼과 보정 제약조건도 검증했다.

## 운영 적용

복제본 검증과 백업·복구 경로를 확인한 뒤 새 앱을 기동한다. 이력이 없는 기존 주 DB는
앱 기동 중 자동 baseline과 후속 마이그레이션을 적용한다. 기존 `uk_users_login_identifier`
제약이 있는 DB는 위의 별도 보정 절차를 먼저 따른다. 운영 앱의 이전 버전과 새 버전이
동시에 접속할 수 있으므로 호환성과 배포 순서를 확인한다. 기동 후 점검 쿼리로 대상 DB,
성공 이력, 새 테이블·컬럼·제약을 확인한다. 운영 DB에서 `clean`이나 `repair`를 임의로 실행하지 않는다.

첫 적용 이후에는 통합 `V`와 `B` 파일을 수정하지 않고, 후속 스키마 변경을 새 버전의 `V` 파일로 추가한다.
