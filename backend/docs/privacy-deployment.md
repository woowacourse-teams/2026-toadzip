# 개인정보 기능의 DB 배포

개인정보 기능은 `public` 스키마에 전용 영속 테이블을 추가한다. 기존 테이블의 컬럼·제약·인덱스와
기존 자료를 변경하거나 보유기간을 일괄 갱신하지 않는다. 회원 이메일의 수집·저장은 유지한다.
SQL TEMP TABLE을 사용하지 않으며, SHARED DB는 이 기능의 마이그레이션 대상이 아니다.

## 저장 경계와 시작 순서

| 영역 | Flyway 경로·이력 | 역할 |
|---|---|---|
| 기존 서비스 | `classpath:db/migration` / `flyway_schema_history` | 기존 기능의 스키마와 기존 SQL checksum 검증 |
| 개인정보 전용 | `classpath:db/privacy` / `privacy_flyway_schema_history` | 신규 `privacy_` 테이블의 생성·향후 변경 |
| 적용 원본 보존 | `classpath:db/archive/privacy-legacy` | 실행하지 않는 폐기 설계의 SQL 원본과 SHA-256 |

`PrivacyMigrationConfiguration`의 실행 순서는 읽기 전용 사전 검사 → 기존 Flyway → 개인정보
Flyway → JPA 검증이다. 개인정보 Flyway는 이미 테이블이 있는 `public`에서 자신의 새 이력에
**baseline 0**을 기록한 뒤 V1을 실행한다. 이 baseline은 기존 기능을 대신 승인하거나 기존
Flyway 이력을 덮어쓰는 작업이 아니다. 기존 Flyway 설정·이력·checksum 검증은 그대로 유지한다.
`clean`은 비활성이고, `repair`, 누락 이력 무시 또는 검증 생략 옵션을 사용하지 않는다.

V1은 [모듈 분리 계약](privacy-isolation.md)의 7개 자료 테이블과 전용 이력 테이블을 만든다.
기존 테이블로 향하는 FK나 과거 동의·고지의 backfill은 없다. 신규 자료로 시작하며
개인정보 DDL·배치는 기존 업무 데이터를 갱신·삭제하지 않는다.

## 이미 이전 설계가 적용된 DB

다음 중 하나라도 있으면 **기존 Flyway와 개인정보 Flyway 모두 실행하기 전에 시작을 중단한다.**

- 기존 `flyway_schema_history`에 archive의 구형 개인정보 SQL 4개 파일명 중 하나가 기록되어 있다.
  버전 번호만으로 판별하지 않는다. `V20261009_01__create_housing_complex_reviews.sql`은 정상 기존 기능의
  마이그레이션이며, 같은 `20261009.01`이라는 이유로 차단하지 않는다.
- 이전 `analytics_consents`, `analytics_consent_events`, `user_deletion_markers` 테이블이 있다.
- `users`, `notification_subscriptions`, `notification_interest_events`에 이전 개인정보 컬럼·인덱스가 있다.
- `notification_interest_events.session_id`의 NOT NULL이 제거되었다.
- 비어 있지 않은 DB에 기존 Flyway 이력이 없거나, 전용 이력 없이 `privacy_` 테이블만 있다.
- 기존 또는 개인정보 Flyway 이력에 실패한 실행이 있다.

이미 적용된 V20261009_01~04는 바이트와 checksum을 보존하여 archive로 옮겼다.
이를 다시 runtime Flyway location에 넣어 신규 DB에 실행하지 않는다. 이미 변경된 DB는
이 릴리스의 자동 마이그레이션 대상이 아니며 별도 승인된 복구가 끝나기 전까지 배포하지 않는다.
기존 SQL을 고치거나 Flyway 이력을 삭제·repair·ignore해서 위 검사를 통과시키면 안 된다.

복구 계획은 적용 직전 백업, 실제 이력, 전후 컬럼·제약·인덱스, 자료 차이를 함께 비교한다.
삭제된 행은 살아 있는 DB의 현재 행 수만으로 복구 가능하다고 판단하지 않는다. 해당 행이
실제 백업에 존재하는지 격리 복원으로 확인한다. 백업 이후 정상 회원·알림 변경과 새 개인정보
선택도 구분하여 보존 여부를 결정한다. 원본 DB에서의 복구 실행은 별도 명시적 승인 대상이다.
구체적인 대상·백업·변경 목록은 접근이 제한된 운영 자료로 관리하고 개인 자료를 문서에 복사하지 않는다.

## 배포 전 검증

1. 적용 대상 PRIMARY DB를 확인하고 백업 및 격리 복원 가능성을 검증한다. 기존 SQL 전체와
   현재 DB의 모든 적용 이력이 일치하는 릴리스여야 한다. 오래된 기능 브랜치를 그대로 운영에 올리지 않는다.
2. 기존 서비스 스키마의 격리 DB에 합성 자료를 준비한다. 신규 마이그레이션 전후 기존 테이블의
   전체 컬럼·제약·인덱스·자료가 동일한지 비교한다. 기존 테이블 `ALTER`·backfill이 필요하면 중단한다.
3. 같은 격리 DB에서 가입 고지·동의·알림 기능과 신규 정보의 보유기간·자동 파기를 검증한다.
   만료된 기존 회원·비회원 알림 자료가 이번 개인정보 배치로 삭제되지 않는지도 확인한다.
4. 기존 `.env`, HTTPS·모니터링 Compose overlay와 DB 볼륨을 유지한다. 비밀은 출력하지 않고
   구문 검사는 `docker compose config --quiet`를 사용한다.
5. 실제 DB에 대한 쓰기·교체 승인 후에만 아래 배포 절차를 실행한다. 코드 검사·격리 검증 완료가
   실제 로컬·개발·운영 DB 변경을 승인한다는 뜻은 아니다.

점검 스크립트는 호스트의 `psql`을 사용한다. 비밀번호는 저장소 밖 권한 0600의 `PGPASSFILE`
또는 기존 인증으로 제공한다. 다음 연결 값은 운영자가 실제 환경에 맞게 지정할 예시다.

```sh
export PGHOST=primary-db.internal
export PGPORT=5432
export PGDATABASE=toadzip
export PGUSER=toadzip
export PGPASSFILE=/secure/path/to/primary.pgpass

docker compose config --quiet
sh scripts/check-privacy-schema.sh prod before
docker compose build backend frontend
```

`before`는 앱 시작과 같은 읽기 전용 사전 검사를 실행한다. 기존 Flyway 이력이 없는 비어 있지
않은 DB는 [Flyway 최초 적용](flyway-adoption.md)을 별도로 검토한다. 임의 baseline으로 우회하지 않는다.
`after`는 독립 V1 성공 이력, 7개 테이블·필수 컬럼·파기 인덱스와 기존 테이블 FK 부재를 검사한다.
SQL checksum 검증은 각각의 Flyway가 담당한다.

## 승인된 교체 절차

구버전 화면과 API의 동의·알림 계약을 혼합하지 않도록 프론트와 백엔드 writer를 함께 중지하고
같은 릴리스로 교체한다. DB·볼륨·모니터링 서비스를 삭제하거나 초기화하지 않는다.

```sh
(
  set -eu
  docker compose stop frontend backend
  sh scripts/check-privacy-schema.sh prod before
  docker compose up -d --no-deps --wait --wait-timeout 300 backend
  sh scripts/check-privacy-schema.sh prod after
  docker compose up -d --no-deps --wait --wait-timeout 60 frontend
)
```

스키마 검사나 backend health가 실패하면 프론트를 열지 않는다. `restart`만으로 새 이미지가
반영되지 않으며 `--wait` 실패가 이미 성공한 마이그레이션을 취소한다는 뜻도 아니다. 원인을
읽기 전용으로 확인하고 추가 기동·DDL·복구를 중단한다. 자동 rollback이나 이력 조작은 하지 않는다.

## 기능 확인과 운영 경계

- HTTPS에서 공개 안내·첫 선택·로그인·기존 알림 조회·취소를 검증한다. 회원 이메일은 유지한다.
- 분석은 최신 안내 범위의 유효한 동의에만 수집한다. 미동의·거부·철회·만료 상태의 미전송과
  동의 후 전송을 구분하여 확인한다. 동의 저장만으로 외부 SDK 전송 성공을 판정하지 않는다.
- 개인정보 파기 배치의 대상은 위 신규 테이블뿐이다. 회원 부재 확인을 위한 기존 테이블 읽기와
  기존 테이블 삭제는 다르다. 기간은 [운영 정책](../../docs/privacy-policy.md),
  파기 동시성은 [동의 API](privacy-consent.md)와 [알림 설정](notification-settings.md)을 따른다.
- 제공자 보유·삭제 설정, 국외이전, 백업·메일 파기, 담당자와 외부 경보는
  [운영 정책](../../docs/privacy-policy.md)의 별도 확인 항목이다.

## 구조 변경·기능 제거

향후 개인정보 스키마 변경은 `db/privacy`의 새 버전만 추가한다. 적용된 SQL·이력은 수정하지 않는다.
기존 테이블의 컬럼·인덱스·FK를 따라가며 제거할 필요가 없는 구조를 유지한다.

기능 제거의 코드 범위·클라이언트 호환성과 자료 정리 순서는 [모듈 분리 계약](privacy-isolation.md)을 따른다.
신구 코드가 혼합 실행되는 동안 신규 테이블을 제거하지 않는다. 배포에 자동 DROP·CASCADE나
기존 테이블·이력의 수정을 포함하지 않는다.

## 자동 검증

`PrivacySchemaMigrationTest`는 격리 PostgreSQL에서 기존 모든 테이블의 컬럼·제약·인덱스·트리거와
전체 행 값의 전후 일치, 새 독립 이력·테이블만 추가, 재시작 무변경, 빈 DB 시작, 폐기 설계 적용 DB의
쓰기 없는 거절, 원본 SQL 해시, 신규 테이블 안에서만 FK·삭제 연쇄가 존재함을 검증한다.
배포 gate와 `NotificationInterestMigrationTest`는 같은 신규 경계를 확인한다.
이 검증은 별도 DB의 복구 실행이나 운영 배포 성공을 증명하지 않는다.
