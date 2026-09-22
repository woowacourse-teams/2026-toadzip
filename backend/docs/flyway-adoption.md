# Flyway 도입: 기존 PostgreSQL DB

대상은 primary DB `toadzip`의 `public` 스키마다. shared DB `toadzip_shared`는 대상이 아니다.

## 경로

| DB 상태 | 적용 경로 |
|---|---|
| 기존 테이블이 있고 Flyway 이력이 없음 | 명시적으로 `baselineVersion=20260922.00` 기록 → 통합 `V20260922_01` |
| 새 빈 DB | 누적 스키마 `B20260922_01`만 적용 |
| Flyway 이력이 있음 | 기록된 버전 다음의 `V`만 실행 |

`baseline` 명령과 `B` 스크립트는 서로 다른 기능이다. 기존 DB에서 `B`가 실행되면 테이블 생성이 충돌하므로, 복제본에서 이력과 실행 순서를 먼저 확인한다. `baseline-on-migrate=false`를 유지한다.

## 복제본에서 확인할 순서

1. 운영 복제본의 `current_database()`가 `toadzip_rehearsal`인지 확인한다. 복제본에 `flyway_schema_history`가 없어야 한다.
2. 복제본에 Flyway CLI의 `baseline`을 버전 `20260922.00`으로 한 번 실행한다. `flyway_schema_history`에 해당 버전의 `BASELINE`이 기록됐는지 확인한다.
3. 동일한 마이그레이션 파일과 연결 정보로 `migrate`를 실행한다. 통합 `V20260922_01`이 성공해야 하며 `B`는 실행되면 안 된다.
4. 보정 후 좌표 제약조건과 파이프라인 자식 테이블 3개의 `ON DELETE CASCADE`를 확인한다. 데이터 건수와 [운영 스키마 전환 점검](production-schema-reconciliation.md)의 비즈니스 값도 다시 확인한다.
5. 최신 애플리케이션을 복제본에 연결해 `ddl-auto=validate` 기동과 주요 조회를 확인한다. 새 빈 DB도 별도로 기동해 `B20260922_01` 이력 하나만 성공하는지 확인한다.

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

복제본 검증과 백업·복구 경로를 확인한 뒤, 같은 아티팩트로 primary DB에 명시적 `baselineVersion=20260922.00`을 기록하고 `migrate`를 실행한다. 운영 앱의 이전 버전과 새 버전이 동시에 접속할 수 있으므로, 마이그레이션의 호환성과 배포 순서를 확인한다. `flyway_schema_history`의 `success=false`가 없는지 확인한 후 새 앱을 기동한다. 운영 DB에서 `clean`이나 `repair`를 임의로 실행하지 않는다.

첫 적용 이후에는 통합 `V`와 `B` 파일을 수정하지 않고, 후속 스키마 변경을 새 버전의 `V` 파일로 추가한다. 로컬·개발 환경도 이미 데이터가 있는 DB라면 동일하게 명시적 baseline이 필요하다.
