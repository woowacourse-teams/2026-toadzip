# 회원 알림 설정

회원은 단지·공고·지역 알림 설정을 이메일 입력 없이 저장한다. 현재 새 공고 알림 생성·전달과 FCM은 제공하지 않는다. 신청은 회원 ID와 선택 대상의 설정 저장 요청이며, 분석 동의·마케팅 수신·OS 푸시 권한이 아니다. 회원 이메일은 관리자의 회원 조회·검색 목적으로 유지하며 알림 해제로 삭제하지 않는다.

## 신청과 조회 계약

`GET /api/v1/notification-subscriptions/me`는 인증된 회원의 `{userId, settingsRevision, targets}`를 반환한다. 각 대상은 `targetType`, `targetId`, `targetName`, `noticeVersion`, `requestedAt`, `expiresAt`을 가진다. 삭제된 대상 이름은 null일 수 있다. 목록과 revision은 같은 회원 잠금 아래에서 읽으며 개인 응답은 `Cache-Control: no-store`다.

도입 전 설정의 `noticeVersion`·`requestedAt`도 null일 수 있다. 과거 고지 확인을 임의로 생성하지
않으며 이 설정도 정상 목록으로 표시하고 취소할 수 있다. 새 신청은 현재 고지와 신청 시각을 기록한다.

`POST /api/v1/notification-subscriptions/me`는 인증·CSRF를 요구하고 다음 필드만 받는다.

| 필드 | 계약 |
|---|---|
| `eventId` | 명시적인 신청·취소마다 만드는 UUID. 같은 요청 재시도는 같은 ID·본문 사용 |
| `expectedUserId` | 인증 회원 ID와 비교하는 문자열. 권한을 부여하지 않음 |
| `expectedSettingsRevision` | GET 또는 직전 성공 응답의 revision |
| `eventType` | `CONFIRMED` 또는 `CANCELLED` |
| `source` | `SETTING`, `REGION_SEARCH`, `COMPLEX_DETAIL`, `ANNOUNCEMENT_DETAIL` |
| `targetType`, `targetId` | `REGION/COMPLEX/ANNOUNCEMENT`, 숫자 문자열 ID |
| `noticeVersion` | 신청은 현재 `NOTIFICATION_NOTICE` 버전 필수. 취소는 생략 가능 |

이메일·clientId·sessionId를 포함한 정의되지 않은 필드는 null이어도 400이다. `SETTING`은 세 대상 종류를 모두 지원하며 상세·검색 source는 대상 종류와 일치해야 한다. 신청에는 유효한 대상이 필요하지만 취소는 삭제된 대상에도 가능하다.

신청 모달의 `신청하기`를 누른 뒤에만 POST한다. `취소`는 모달을 닫고 설정을 만들지 않는다. 신청 안내와 개인정보처리방침 링크는 버튼 아래, 신청 전에 접근 가능한 같은 모달에 둔다. 분석 거부·철회는 설정 신청·조회·해제를 차단하지 않는다.

## 명령 결과와 현재 상태

성공 응답은 `{eventId, targetType, targetId, outcome, occurredAt, settingsRevision, currentTarget}`이다. `currentTarget`은 `{active, expiresAt, noticeVersion, requestedAt}`이며 미존재 대상은 active=false와 나머지 null이다.

- 첫 저장·만료 후 재신청은 `ACTIVATED`, 이미 유효하면 `ALREADY_ACTIVE`다.
- 유효 설정 해제는 `CANCELLED`, 이미 비활성이거나 없으면 `UNCHANGED`다.
- 같은 eventId 재시도는 원래 outcome/occurredAt과 **현재** settingsRevision/currentTarget을 반환한다. 과거 성공 영수증으로 취소 상태를 되살리지 않는다.
- 새 명령의 `ALREADY_ACTIVE/UNCHANGED`도 revision과 업무 이벤트를 남기지만 신청·만료·파기 시각을 연장하지 않는다.
- 회원 존재 확인·행 잠금 → 신규 `privacy_notification_states` 잠금 → 중복 명령 검사 → revision 비교 → 설정·이벤트·revision 저장 순으로 한 트랜잭션에서 처리한다. 변경과 증빙 중 하나만 성공하지 않는다.
- 회원/revision 불일치는 409 `NOTIFICATION_SETTINGS_CONFLICT`, 같은 eventId에 다른 본문은 409 `NOTIFICATION_INTEREST_CONFLICT`다. 최신 목록을 확인하며 새 ID로 자동 우회하지 않는다.

전체 해제는 대상별로 순서대로 처리하고 각 성공 응답의 settingsRevision을 다음 요청에 사용한다. 실패 대상은 남겨 둔다. 계정 전환 또는 오래된 응답을 감지하면 현재 계정 상태부터 다시 조회한다.

## 분석과 업무의 경계

`NotificationSettingsService`가 회원 설정만 변경한다. 공개 `/api/v1/notification-interest-events`는 EXPOSED/CLICKED/DECLINED 같은 선택적 행동 분석만 다루며 구독을 변경하지 않는다. 공개 CONFIRMED/CANCELLED 및 이메일·clientId를 포함한 요청은 거절한다. 비회원 이메일 신규 신청과 client ID 조회 경로는 제공하지 않는다.

공개 interest와 공고 조회 기록은 동의 행을 잠근 같은 트랜잭션에서 현재 동의·scope·만료를 확인한다. 미동의·거부·철회 상태는 저장하지 않고 유효한 동의에만 수집한다. 공개 interest는 `privacy_notification_events`에 저장하며 회원 ID·이메일과 연결하지 않는다. 단순 공고 열람과 알림 설정 업무는 계속 사용할 수 있다. 업무 영수증은 분석 원자료로 재사용하지 않는다.

## 저장 경계

신규 테이블 소유권·기존 스키마 불변·기능 제거 범위는 [모듈 분리 계약](privacy-isolation.md)을 따른다.
`PrivacyNotificationRepository`는 신규 메타 저장과 기존 구독 읽기를 맡고,
`NotificationSubscriptionRepository`는 사용자가 요청한 기존 업무 신청·취소를 처리한다.
기존 설정의 고지 버전·신청 시각을 backfill하지 않는다.

## 보유·파기와 운영

알림의 활성·만료 판단과 응답 만료 시각은 기존 `notification_subscriptions`를 단일 원본으로 사용한다.
기존 저장소가 DB 시간대 기준 신청 후 12개월로 정한 실제 만료 시각을 신규 고지 메타에 복사한다.
메타는 업무 유효기간을 연장하거나 덮어쓰지 않으며, 메타만 제거해도 원래 알림 상태는 유지된다.
만료 정각부터 조회 대상에서 제외한다.

신규 메타의 기간 계산은 `privacy.domain.PrivacyRetentionPolicy`를 사용하며
[운영 정책](../../docs/privacy-policy.md)의 보유·파기 기준을 따른다. 존재하는 회원의 revision은
이력 파기로 초기화하지 않는다. 회원이 사라진 고지·영수증·revision은 다음 성공 배치에서 정리한다.

`PrivacyNotificationRetentionService`는 기본 15분 주기로 실행한다. 한 트랜잭션은 최대 500행을 삭제하고 실행은 30초로 제한한다. 회원 → 신규 revision → 신규 메타 순서로 잠그며 다른 작업의 잠금은 건너뛴다. 잠금 후 파기 조건을 다시 확인하여 재신청한 고지를 보존한다. 실패 청크는 롤백하고 다음 주기에 재시도한다.

이 배치는 위 네 신규 테이블만 삭제한다. 기존 회원 이메일, 회원·비회원 구독, 이메일 참조, 비회원 취소 요청, 과거 행동 기록은 만료 여부와 무관하게 변경하지 않는다. 기존 자료의 보유·복구 작업은 별도 운영 승인 대상으로 관리한다. 배포 전 격리 DB에서 기존 스키마와 자료가 그대로인 것을 비교해야 하며 절차는 [DB 배포](privacy-deployment.md)를 따른다.

운영 지표는 `privacy.retention.*{job="notification"}`로 제공한다.
[공통 파기 감시·경보](../../docs/privacy-policy.md)를 적용하며 서버 복구 후에도 지연을 확인한다.

화면 진입점과 신청·해제 UI는 [프론트 계약](../../frontend/docs/privacy-consent.md)을 따른다.
