# 알림 설정과 취소

회원은 단지·공고·지역 알림 설정을 이메일 없이 저장한다. 실제 알림 발송과 FCM은 제공하지 않는다.
기존 비회원 신청의 취소 확인 코드는 팀 담당자가 수동으로 전달한다.

## 회원 설정

### API

| 요청 | 동작 |
|---|---|
| GET `/api/v1/notification-subscriptions/me` | 인증된 회원의 `{userId, settingsRevision, targets}` 조회 |
| POST `/api/v1/notification-subscriptions/me` | 세션·CSRF로 신청·해제. 회원 ID는 인증 정보에서 얻음 |

조회 대상은 `targetType`, `targetId`, `targetName`, `noticeVersion`, `requestedAt`, `expiresAt`을 가진다.
삭제된 대상 이름과 도입 전 설정의 고지 버전·신청 시각은 null일 수 있다.
과거 고지를 추정하지 않으며 기존 설정도 조회·해제할 수 있다. 개인 응답에는 `Cache-Control: no-store`를 적용한다.

### 신청과 해제

| 필드 | 요청값 |
|---|---|
| `eventId` | 신청·해제마다 생성한 UUID. 같은 요청 재시도는 같은 ID와 본문 사용 |
| `expectedUserId` | 현재 인증 회원 ID와 비교할 문자열 |
| `expectedSettingsRevision` | 조회 또는 직전 성공 응답의 revision |
| `eventType` | `CONFIRMED` 또는 `CANCELLED` |
| `source` | `SETTING`, `REGION_SEARCH`, `COMPLEX_DETAIL`, `ANNOUNCEMENT_DETAIL` |
| `targetType`, `targetId` | `REGION/COMPLEX/ANNOUNCEMENT`와 숫자 문자열 ID |
| `noticeVersion` | 신청은 현재 `NOTIFICATION_NOTICE` 버전 필수. 해제는 생략 가능 |

`SETTING`은 모든 대상 종류를 지원한다. 상세·검색 source는 대상 종류와 일치해야 한다.
이메일·clientId·sessionId 등 정의되지 않은 필드는 null이어도 400이다.
신청에는 유효한 대상이 필요하며 삭제·카탈로그 제외 대상도 해제할 수 있다.

신청 모달에서 고지·정책 링크를 제공하고 `신청하기`를 누른 뒤에만 POST한다. 모달을 취소하면 저장하지 않는다.
알림 설정은 분석 동의·마케팅 수신·OS 푸시 권한과 별개이며 분석 거부·철회가 신청·조회·해제를 막지 않는다.

### 응답과 충돌

성공 응답은 200과 `eventId`, `targetType`, `targetId`, `outcome`, `occurredAt`, `settingsRevision`, `currentTarget`이다.
`currentTarget`은 `{active, expiresAt, noticeVersion, requestedAt}`이며 미존재 대상은 active=false와 나머지 null이다.

| `outcome` | 의미 |
|---|---|
| `ACTIVATED` / `ALREADY_ACTIVE` | 새 활성화 / 이미 활성 상태 |
| `CANCELLED` / `UNCHANGED` | 실제 해제 / 해제할 설정 없음 |

같은 이벤트 ID 재시도는 최초 결과·시각과 **현재** revision·대상 상태를 반환한다. 과거 성공 응답으로 해제 상태를 되살리지 않는다.
새 명령은 `ALREADY_ACTIVE/UNCHANGED`여도 revision·업무 영수증을 남기지만 유효기간을 연장하지 않는다.

| 충돌 | 응답과 처리 |
|---|---|
| 회원 또는 revision 불일치 | 409 `NOTIFICATION_SETTINGS_CONFLICT`. 현재 계정의 목록을 다시 조회 |
| 같은 eventId에 다른 본문 | 409 `NOTIFICATION_INTEREST_CONFLICT`. 새 ID로 자동 우회하지 않음 |

전체 해제는 대상별로 순서대로 처리하며 성공 응답의 revision을 다음 요청에 사용한다.
실패 대상은 남겨 두고, 계정 전환·오래된 응답을 감지하면 현재 계정부터 다시 조회한다.
회원 화면과 신청 모달은 [프론트 알림 계약](../../../frontend/docs/privacy-consent.md#분석과-업무-기록)을 따른다.

### 분석과 기존 신청

공개 `/api/v1/notification-interest-events`는 유효한 분석 동의 이후의 노출·클릭·거절만 기록한다.
공개 `CONFIRMED/CANCELLED`나 이메일·clientId를 포함한 요청은 거절하며 구독을 변경하지 않는다.
동의 상태와 수집 시점의 잠금은 [분석 동의](../features/PRIVACY.md)를 따른다.

신규 비회원 이메일 신청은 제공하지 않는다. 기존 조회는 `GET /api/v1/notification-subscriptions/guest`와
필수 UUID 헤더 `X-Notification-Client-Id`를 사용하며 로그인·분석 동의가 필요 없다.
응답은 `{emailConfirmed, targets: [{targetType, targetId, targetName}]}`이고 이름은 null이다.
활성·미만료 구독만 반환하며 이메일 원문을 반환하거나 개인정보 기록을 생성하지 않는다.
헤더 누락·오류는 400, 응답은 `Cache-Control: no-store`다.

## 보관과 조회

### 저장 구조

주 DB에서 대상 검증·상태 변경을 한 트랜잭션으로 처리한다. 공유 DB는 사용하지 않는다.

| 테이블 | 저장 내용 |
|---|---|
| `notification_interest_events` | 기존 행동 이벤트, 신규 분석·업무 영수증과 구분 |
| `notification_email_preferences` / `notification_subscriptions` | 회원 이메일 설정 / 대상별 신청 상태 |
| `notification_guest_email_preferences` / `notification_guest_subscriptions` | 비로그인 이메일 설정 / 대상별 신청 상태 |
| `notification_guest_cancellation_requests` | 비회원 취소 요청·코드 해시·수동 발송 기록 |

유효 신청은 기존 구독의 `active=true`와 `expires_at`으로 판단한다.
회원은 `user_id`, 비로그인은 브라우저의 임의 `client_id`로 구분하며 같은 이메일만으로 브라우저를 연결하지 않는다.
회원은 다른 기기에서도 조회하고 창에 다시 포커스되면 갱신한다. 비로그인은 기기 간 동기화하지 않는다.

고지·revision·명령 영수증·새 행동 기록은 [개인정보 전용 테이블](../features/PRIVACY_STORAGE.md)에 저장한다.
`NotificationSettingsService`는 회원 → 신규 revision 순으로 잠근 뒤 기존 신청과 신규 메타를 한 트랜잭션으로 저장한다.
신규 메타에는 기존 구독의 실제 만료일을 복사하며 업무 유효기간을 변경하지 않는다.

### 보관 기간

알림은 신청·재활성화부터 12개월간 유효하고 만료 정각부터 조회 대상에서 제외한다.
만료가 기존 행의 자동 삭제를 뜻하지는 않는다. 마지막 신청을 해제하면 기존 알림용 이메일이 정리될 수 있으며 회원 계정의 `users.email`은 유지한다.
비회원 코드 취소가 완료되면 해당 신청·알림용 이메일·취소 요청을 삭제한다.

신규 개인정보 자료의 기간은 [운영 정책](../../../docs/privacy-policy.md#보유파기-정책)을 따른다.
신규 배치는 기존 회원·구독·이메일·과거 행동 기록·미완료 취소 요청을 삭제하지 않는다.
기존 자료의 보유·삭제는 별도 운영 절차로 관리한다.

`PrivacyNotificationRetentionService`는 15분마다 최대 500행씩 처리하며 한 실행은 30초로 제한한다.
회원 → 신규 revision → 신규 메타 순으로 잠그고 파기 조건을 다시 확인한다.
잠긴 행은 건너뛰고 실패 청크는 롤백해 다음 주기에 재시도한다.
지연은 [파기 지표와 경보](../../../docs/privacy-policy.md#운영-점검과-출시-조건)에서 확인한다.

### 기존 자료 점검

승인된 담당자가 [기존 이메일 신청 SQL](../../../docs/queries/active-notification-recipients.sql)을 대상 하나씩 실행한다.
이 쿼리는 `users.email`을 읽지 않아 이메일 없이 신청한 회원이 빠질 수 있다.
전체 신청 목록이나 발송 허용 목록으로 사용하지 않는다.

```text
psql -X -v ON_ERROR_STOP=1 -v target_type=REGION -v target_id=<지역_ID> -f docs/queries/active-notification-recipients.sql
```

SQL은 읽기 전용이며 인자 없이 전체 이메일을 출력하지 않는다. 조회 계정에는 필요한 SELECT 권한만 부여한다.
결과는 공개 로그·저장소에 남기지 않는다. 취소·만료 신청은 제외한다.
[관심 집계](../../../docs/queries/notification-interest-metrics.sql)의 행동 지표는 기존 이벤트만 읽는다.
새 `privacy_notification_events`·업무 영수증은 포함하지 않으며 최근 90일 조회가 자동 파기를 뜻하지 않는다.

## 비로그인 취소

### 요청과 코드 확인

1. 사용자가 `/notifications/cancel`에서 신청 이메일을 입력한다. 신청 여부와 관계없이 같은 접수 응답을 받는다.
2. 관리자가 `/admin/notification-cancellations`에서 코드를 발급해 신청 주소로 수동 발송하고 완료 기록을 남긴다.
3. 사용자가 이메일·코드를 입력하면 같은 주소의 모든 비로그인 신청·알림용 이메일을 삭제한다. 회원 신청에는 영향이 없다.

코드는 발급 시 한 번만 표시한다. DB에는 SHA-256 해시만 저장하며 24시간 유효·성공 시 한 번 사용·오입력 5회 후 무효다.
코드 확인 전에는 신청이 계속 유효할 수 있다. 일반 종 버튼 취소와 회원 취소에는 코드가 필요 없다.

### 재발급

분실·잘못된 발송은 재발급한다. 이전 코드가 즉시 무효화되고 실패 횟수·발송 기록이 초기화된다.
다른 관리자가 재발급한 코드에 이전 발송 완료를 기록하면 거부한다. 신분증 등 신원 자료는 수집하지 않는다.

### 담당과 확인 시간

서비스 주담당자가 처리한다. 부재 전 대체 담당자에게 기간·미발송 요청·관리자 주소를 인계한다.
갑작스러운 부재는 팀원 한 명이 담당 사실을 알리고 당일 요청을 처리한다.

평일 10:00·17:00(Asia/Seoul)에 관리자 요청과 공용 Gmail을 확인한다.
휴일 접수는 다음 평일 10:00에 확인하고 접수 후 다음 평일 17:00까지 코드 발송을 목표로 한다.

### 발송

1. 발송 준비가 되면 코드를 발급하고 요청에 표시된 주소로만 보낸다.
2. 관리자 화면에서 발송 완료를 기록하고 Gmail 보낸 편지도 확인한다. 관리자 기록만으로 실제 전달을 증명하지는 못한다.
3. 사이트에서 코드 입력을 안내한다. 코드 원문을 티켓·메신저·로그에 남기거나 코드·신분증 회신을 요구하지 않는다.

발신은 `toadzip.official@gmail.com`이며 2단계 인증을 설정한다.
대체 담당자는 [Gmail 위임](https://support.google.com/mail/answer/138350?hl=ko)을 받고 각자 관리자 계정을 쓴다.
운영 코드는 `bokduckbang.com`, 개발 코드는 `dev.bokduckbang.com`에서 입력한다. 재발급 시 이전 코드가 무효라고 안내한다.

```text
제목: [공공주택 복덕방] 알림 취소 확인 코드

알림 취소를 요청하신 이메일인지 확인하기 위한 코드입니다.
확인 코드: <이번에 발급한 코드>
유효 기간: <관리자 화면의 만료 시각, 한국 시간>

https://bokduckbang.com/notifications/cancel 에서 신청 이메일과 코드를 입력해 주세요.
확인되면 이 이메일로 신청한 모든 비로그인 알림이 취소됩니다.
로그인 계정의 신청은 종 버튼에서 별도로 취소할 수 있습니다.
새 코드를 받으셨다면 이전 코드는 사용할 수 없습니다.
직접 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.
```

## DB 점검과 복원

### 배포 확인

각 서버의 `.env`에 지정한 주 DB에 Flyway를 적용한다. 실제 대상 주소·포트·이름을 확인하고 [DB 운영](DATABASE.md)을 따른다.
기동 후 아래 검사 종료 코드 0과 출력의 대상 환경을 확인한다. 비밀번호는 보호된 `PGPASSFILE`을 쓴다.

```shell
PGHOST=<대상_주_DB_주소> PGPORT=<확인한_포트> PGDATABASE=toadzip PGUSER=<점검_계정> \
  PGPASSFILE=<보호된_비밀번호_파일> sh scripts/check-notification-schema.sh <dev_또는_prod>
```

DB 이름·Flyway 성공 이력·필수 테이블·컬럼·제약 누락은 비정상 종료다.
원인 조사에는 [상세 조회 SQL](../../../docs/queries/notification-schema-status.sql)을 쓴다.
[보관 상태 SQL](../../../docs/queries/notification-retention-status.sql)은 기존 자료 확인용이며 신규 배치 지연 지표로 사용하지 않는다.
신규 개인정보 테이블·독립 Flyway 이력은 [개인정보 배포 검사](PRIVACY.md)로 별도 확인한다.
원격 적용 완료는 실제 서버의 검사 결과로 확인한다.

### 백업 복원

삭제 전 백업에는 이메일이 남을 수 있으므로 백업 보관·삭제 정책을 확인한다. 저장소는 DB 백업 자동화를 제공하지 않는다.
복원 전에 백업 이후의 취소·삭제를 대조한다. 기존 자료와 이력을 보존하는 승인된 복구 절차를 사용한다.
[복원 정리 SQL](../../../scripts/reset-restored-notifications.sql)은 기존 알림 자료를 비우므로 개인정보 도입·복구 절차에 사용하지 않는다.
