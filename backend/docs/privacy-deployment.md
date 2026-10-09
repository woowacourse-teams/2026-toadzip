# 개인정보 기능의 DB 배포

개인정보 기능은 백엔드 이미지에 포함된 Flyway SQL을 시작 시 PRIMARY DB에 적용한다.
수동으로 테이블을 만들거나 JPA `ddl-auto`를 `update`로 바꾸지 않는다. SHARED DB는 이
마이그레이션 대상이 아니다. Flyway 이후 JPA 스키마 검증까지 성공해야 백엔드가 준비된다.

## 변경 범위

| 버전 | 변경 |
|---|---|
| `20261009.01` | `analytics_consents`, `analytics_consent_events`와 소유자·명령 고유 제약, 삭제 연쇄, 파기 인덱스 |
| `20261009.02` | 회원 알림 revision, 설정의 고지·신청·파기 시각, 업무 이벤트의 회원·고지·revision 및 nullable session ID |
| `20261009.03` | 회원 가입 안내 버전, `user_deletion_markers`와 만료 인덱스 |
| `20261009.04` | 기존 회원 알림 설정에서 비어 있는 파기 예정일만 보완 |

기존 회원 이메일과 알림 설정을 유지한다. 분석 동의는 처음 도입하므로 기존 회원·쿠키에서
허용을 추정하거나 동의 행을 일괄 생성하지 않는다. 기존 가입·알림 고지 버전과 신청 시각의
NULL은 과거 증빙이 없다는 뜻으로 유지한다. 프론트는 이 값이 NULL인 기존 설정도 조회·해제한다.

V04는 활성 설정의 `expires_at + 90일`, 비활성 설정의 `min(updated_at, expires_at) + 90일`을 사용한다.
90일은 Java `Duration`과 같은 2,160시간이며 DB 세션 시간대의 일광절약시간 영향을 받지 않는다.
이미 있는 파기 시각은 변경하지 않는다. 기한이 지난 행은 시작 후 파기 배치의 대상이 된다.
구버전에서 만료 후 취소하거나 반복 취소하면 `updated_at`이 더 늦게 바뀔 수 있으므로 만료일을
상한으로 삼는다. 덮어써진 최초 취소 시각 자체는 복원하지 못하며 남아 있는 값으로 기한을 정한다.
기존 비회원 이메일 알림 자료가 있다면 신규 신청 중단과 별개로 기존 정리 작업을 계속한다.
보유·파기 계약은 [알림 설정](notification-settings.md)을 따른다.

분석 동의 판정과 공개 안내도 같은 릴리스로 배포한다. 현재 안내는 `analytics-2026-10-09-v2`,
처리 범위는 `analytics-scope-2`다. v1 원문을 보존하고 current를 v2로 전환한다. 이전 범위의
허용은 새 수집을 허용하지 않으며 이용자가 최신 안내에 다시 동의해야 한다. 동의 판정만 먼저
바꾸거나 이전 안내를 새 범위의 동의 증빙으로 재사용하지 않는다.

## 배포 전 준비

1. 개발 서버에서 먼저 검증한 동일한 릴리스를 사용한다. 실제 DB에 적용된 모든 Flyway 파일을
   포함해야 한다. 오래된 기능 브랜치를 운영 최신 코드 대신 배포하지 않는다.
2. PRIMARY DB 백업과 격리 환경의 복원 가능 여부를 확인한다. 백업 파일·비밀번호는 저장소에
   넣지 않는다. 운영 DB의 행 수, 장기 트랜잭션과 점검 시간을 확인한다. ALTER·인덱스 생성·V04
   갱신에는 잠금과 시간이 필요하며 소규모 테스트의 실행 시간이 운영 소요 시간을 보장하지 않는다.
3. 서버의 기존 `.env`와 `COMPOSE_FILE`을 유지한다. HTTPS·모니터링 overlay를 빼지 않는다.
   PRIMARY/SHARED 연결 대상과 DDL 권한을 확인하되 `docker compose config`의 확장된 비밀값을
   로그에 남기지 않는다. 구문 확인은 `docker compose config --quiet`를 사용한다.
4. 호스트에 PostgreSQL `psql`과 Compose `--wait` 지원이 필요하다. 아래 점검 연결은 앱과 동일한
   PRIMARY DB를 지정한다. 비밀번호는 권한 `0600`의 저장소 밖 `PGPASSFILE` 또는 기존 인증을
   사용하고 명령행·문서에 쓰지 않는다.

```sh
# 실제 환경 값을 운영자가 지정한다. 아래 값은 예시다.
export PGHOST=primary-db.internal
export PGPORT=5432
export PGDATABASE=toadzip
export PGUSER=toadzip
export PGPASSFILE=/secure/path/to/primary.pgpass

docker compose config --quiet
sh scripts/check-privacy-schema.sh prod before
docker compose build backend frontend
```

`before`는 DB 이름, Flyway 이력과 개인정보 테이블·컬럼의 일치 여부를 읽기 전용으로 검사한다.
빈 DB는 허용하지만 기존 테이블이 있고 Flyway 이력이 없는 DB는 중단한다. 이 경우
[Flyway 최초 적용](flyway-adoption.md)을 먼저 검토한다. 현재 앱의 baseline 설정만 믿고
이 점검을 우회하지 않는다. 파일 checksum 검증은 이후 Flyway 시작 단계의 책임이다.

## 기존 서비스 교체 순서

구버전 화면은 새 OAuth 정책 버전과 회원 알림 API를 사용하지 않는다. 구버전 백엔드는 새로운
파기 시각·고지·revision을 기록하지 않는다. 따라서 이 전환에서는 여러 인스턴스를 포함한
**모든 구버전 프론트·백엔드 writer를 중지하고 같은 릴리스로 교체**한다. 무중단 혼합 운영은
지원하지 않는다. 빌드는 위에서 미리 끝내고 아래 구간만 점검 시간으로 잡는다.

저장소 루트에서, 백업과 준비가 끝난 뒤 실행한다. 개발 서버는 `prod`를 `dev`로 바꾼다.
괄호 안은 오류가 나면 즉시 중단하며, DB·Alloy·모니터링 컨테이너를 정지하지 않는다.

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

`restart`만으로 새 이미지와 SQL이 반영되지는 않는다. `up`은 새로 빌드한 이미지로 컨테이너를
재생성한다. `--no-deps`는 의존 서비스 대기를 생략하므로 위 순서의 백엔드 `--wait`와
`after` 점검을 생략하지 않는다. 일반 Compose 실행도 frontend의 `service_healthy` 의존 조건으로
백엔드가 준비될 때까지 기다린다. 이 조건은 이미 실행 중인 구버전 프론트를 중지해 주지는 않는다.

백엔드 healthcheck는 관리 포트의 `/actuator/health` HTTP 성공 여부를 확인한다. DB 연결을
포함한 앱의 준비 상태를 검사하며, 실패 시 Compose의 대기가 비정상 종료된다. `after`는
4개 개인정보 migration 성공 이력, 컬럼·제약·삭제 연쇄·인덱스와 알림 파기 시각 누락을 확인한다.
두 점검이 성공한 뒤에만 프론트를 시작한다.

## 완료 확인과 실패 대응

- `docker compose ps`에서 backend/frontend가 healthy인지 확인한다. 백엔드 시작 로그에서
  Flyway와 JPA 검증 성공을 확인하되 회원 정보·쿠키·자격 증명을 로그에 복사하지 않는다.
- 실제 HTTPS에서 `/api/v1/privacy/notices/current`와 공개 정책, 첫 방문 선택, 로그인,
  기존 회원 알림 조회·취소를 확인한다. dev/prod 동의 쿠키는 Secure이므로 HTTP로 검증하지
  않는다. HTTPS 운영의 세션 쿠키도 `SESSION_COOKIE_SECURE=true`로 설정한다.
- 현재 analytics 안내 버전·scope가 v2/scope-2인지, 구 scope 허용은 재동의를 요구하는지 확인한다.
  최신 범위에 유효하게 동의하면 context의 `collectionAllowed=true`와 자체 분석 API 성공을,
  미동의·거부·철회·만료·이전 범위에서는 수집 거절을 확인한다. 회원·브라우저 선택은 서로 복사하지 않는다.
- 이미 열린 탭에는 구버전 JavaScript가 남을 수 있으므로 배포 후 새로고침을 안내하고,
  새로고침한 화면에서 로그인·알림을 검증한다. 컨테이너 교체가 열린 탭까지 갱신하지는 않는다.
- 파기 배치의 마지막 성공·지연 지표를 확인한다. 실제 삭제·외부 경보 수신은
  [개인정보 운영 정책](../../docs/privacy-policy.md)의 별도 완료 조건이다.
- 분석은 유효한 현재 동의에 따라 실제 수집하며 전면 차단 플래그를 두지 않는다. 프론트 SDK의
  설정값과 네트워크 요청, 실제 전송 항목을 운영 환경에서 확인한다. 동의 API 성공만으로 GA4·PostHog
  전송 성공이나 제공자 관리 콘솔의 보유기간·삭제 설정까지 확인됐다고 판단하지 않는다.
- 외부 제공자의 실제 보유기간, 국외이전 안내와 처리 조건, 백업·문의 메일 삭제, 담당자와 경보 수신은
  [개인정보 운영 정책](../../docs/privacy-policy.md)의 운영 확인 항목이다. 코드와 로컬 검증의 완료를
  이 항목들의 확인 또는 운영 배포 완료로 기록하지 않는다.

실패하면 프론트를 열지 않고 원인을 확인한다. `--wait` 시간 초과는 DDL이 자동으로 취소됐다는
뜻이 아니다. PostgreSQL에서 각 SQL migration은 트랜잭션 단위로 적용되지만 앞서 성공한 파일은
이미 반영됐을 수 있다. 새 인스턴스를 계속 띄우거나 `clean`, 임의 `repair`·`baseline`, 테이블
삭제로 해결하지 않는다. 실제 실행 중인 트랜잭션과 Flyway 이력을 먼저 확인한다.

적용된 SQL은 수정하지 않고 새 migration과 수정 이미지로 전진 복구한다. 구버전 이미지만
되돌리면 새 파기 기준을 누락하는 writer가 다시 살아날 수 있으므로 단순 이미지 rollback을
기본 절차로 쓰지 않는다. 백업 복원이 필요하면 유입·writer를 정지한 상태에서 복원 검증과
백업 이후 탈퇴·삭제 재적용까지 끝내고 다시 연다. 자동 rollback이나 데이터 삭제는 수행하지 않는다.

## 검증 범위

`PrivacySchemaMigrationTest`는 PostgreSQL에서 빈 DB, 개인정보 적용 직전 DB와 V03까지 적용된
DB를 대상으로 실행·재실행·기존 값 보존·DST 경계·DB 제약·배포 점검을 검증한다.
`tests/deployment/test_backend_health.py`는 Compose overlay의 health 조건과 실패 응답을 검사한다.
이 검증은 실제 운영 DB의 백업, 데이터 규모, 배포 성공과 별개다.
