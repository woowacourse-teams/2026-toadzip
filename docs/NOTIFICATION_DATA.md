# 알림 신청 데이터와 DB 점검

서비스는 알림 설정·신청 목록을 저장한다. 새 공고 대상 매칭과 이메일 발송은 자동화하지 않는다.
회원 설정 API는 [회원 알림 설정](../backend/docs/notification-settings.md), 보관·취소·수동 발송은 [운영 절차](notification-retention-and-cancellation.md)를 따른다.

## 저장 구조

### 주 DB

| 테이블 | 저장 내용 |
|---|---|
| `notification_interest_events` | 노출·클릭·신청·취소 등 행동 이벤트. 이메일 제외 |
| `notification_email_preferences` / `notification_subscriptions` | 회원 이메일 설정 / 대상별 신청 상태 |
| `notification_guest_email_preferences` / `notification_guest_subscriptions` | 비로그인 사용자의 이메일 설정 / 대상별 신청 상태 |
| `notification_guest_cancellation_requests` | 비회원 취소 요청·코드 해시·수동 발송 기록 |

사용자와 지역·단지·공고가 있는 주 DB에서 대상 검증·상태 변경을 한 트랜잭션으로 처리한다. 공유 DB는 사용하지 않는다.

### 신청과 취소

대상 종류·ID별 `active=true`는 유효 신청, `active=false`는 취소 상태다.
회원은 `user_id`, 비로그인 사용자는 브라우저의 임의 `client_id`로 구분한다. 같은 이메일만으로 서로 다른 브라우저를 연결하지 않는다.
회원은 다른 기기에서 서버 상태를 조회하며 열린 창은 다시 포커스될 때 갱신한다. 비로그인 신청은 다른 기기로 동기화하지 않는다.

마지막 비로그인 신청을 취소하면 이메일 설정과 연결된 신청 행을 함께 삭제한다. 이벤트는 별도로 보관한다.
과거 이벤트에 계정·브라우저 ID가 없으면 신청 목록으로 자동 이전하지 않는다. 해당 사용자는 다시 신청해야 유효 목록에 들어간다.

## 수동 발송 대상 조회

승인된 담당자가 공고의 대상 지역·단지·공고 ID를 확인하고 [유효 신청 조회 SQL](queries/active-notification-recipients.sql)을 실행한다.
대상 하나씩 조회하며 최종 일치 여부와 발송 대상은 사람이 확인한다.

```text
psql -X -v ON_ERROR_STOP=1 -v target_type=REGION -v target_id=<지역_ID> -f docs/queries/active-notification-recipients.sql
```

SQL은 읽기 전용이고 인자 없이 전체 이메일을 출력하지 않는다. 조회 계정에는 필요한 테이블의 SELECT 권한만 부여한다.
결과는 개인정보이므로 공개 로그·저장소에 남기지 않는다. 취소·만료 신청은 발송 대상에서 제외된다.

[관심 집계](queries/notification-interest-metrics.sql)의 클릭 수는 행동 기록이고 유효 신청 수는 현재 상태다. 두 건수는 같지 않을 수 있다.

## 개발·운영 DB 확인

### 배포 대상

`develop`은 개발, `main`은 운영 코드의 배포 대상이다.
각 서버 기동 시 `.env`의 `PRIMARY_DB_HOST`, `PRIMARY_DB_PORT`, `PRIMARY_DB_NAME`이 지정한 주 DB에 Flyway를 적용한다.
개발·운영의 연결값은 별도 관리하며 데이터가 서로 복사되지 않는다.

배포 전 실제 대상 DB를 백업하고 복제본에서 앱 기동을 확인한다.
이력 없는 DB의 스키마·로그인 제약은 [Flyway 적용](../backend/docs/flyway-adoption.md)을 따른다.

### 적용 검사

백엔드 기동 후 각 환경에서 검사 스크립트를 실행한다. 종료 코드가 0이어야 적용 완료로 기록한다.
비밀번호는 명령·저장소에 적지 않고 접근 통제된 `PGPASSFILE`을 사용한다.

```shell
PGHOST=<개발_주_DB_주소> PGPORT=5432 PGDATABASE=toadzip PGUSER=<점검_계정> \
  PGPASSFILE=<보호된_개발_비밀번호_파일> sh scripts/check-notification-schema.sh dev

PGHOST=<운영_주_DB_주소> PGPORT=5432 PGDATABASE=toadzip PGUSER=<점검_계정> \
  PGPASSFILE=<보호된_운영_비밀번호_파일> sh scripts/check-notification-schema.sh prod
```

DB 이름·Flyway 성공 이력·필수 테이블·컬럼·제약 중 하나라도 빠지면 비정상 종료한다.
출력의 DB 주소·이름·시각을 의도한 환경과 대조해 배포 기록에 남긴다. 원인 조사에는 [상세 조회 SQL](queries/notification-schema-status.sql)을 사용한다.
원격 DB의 적용 완료는 실제 서버에서 이 검사를 실행한 결과로 확인한다.
