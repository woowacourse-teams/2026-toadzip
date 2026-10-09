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
- 회원 행 잠금 → 중복 명령 검사 → revision 비교 → 설정·이벤트·revision 저장 순으로 한 트랜잭션에서 처리한다. 변경과 증빙 중 하나만 성공하지 않는다.
- 회원/revision 불일치는 409 `NOTIFICATION_SETTINGS_CONFLICT`, 같은 eventId에 다른 본문은 409 `NOTIFICATION_INTEREST_CONFLICT`다. 최신 목록을 확인하며 새 ID로 자동 우회하지 않는다.

전체 해제는 대상별로 순서대로 처리하고 각 성공 응답의 settingsRevision을 다음 요청에 사용한다. 실패 대상은 남겨 둔다. 계정 전환 또는 오래된 응답을 감지하면 현재 계정 상태부터 다시 조회한다.

## 분석과 업무의 경계

`NotificationSettingsService`가 회원 설정만 변경한다. 공개 `/api/v1/notification-interest-events`는 EXPOSED/CLICKED/DECLINED 같은 선택적 행동 분석만 다루며 구독을 변경하지 않는다. 공개 CONFIRMED/CANCELLED 및 이메일·clientId를 포함한 요청은 거절한다. 비회원 이메일 신규 신청과 client ID 조회 경로는 제공하지 않는다.

공개 interest와 공고 조회 기록은 동의 행을 잠근 같은 트랜잭션에서 분석 출시 조건·현재 동의·scope·만료를 확인한다. 검증 전 출시 조건은 false여서 수집을 차단한다. 단순 공고 열람과 알림 설정 업무는 계속 사용할 수 있다. 업무 이벤트는 분석 원자료로 재사용하지 않는다.

## 보유·파기와 운영

정책 계산은 `privacy.domain.PrivacyRetentionPolicy`를 따른다. 알림은 Asia/Seoul 달력 기준 신청 후 12개월 동안 유효하며 만료 정각부터 조회 대상에서 제외한다. 활성 행에 신청 시각과 안내 버전을 보존한다. 만료·취소 후 90일이 지난 설정, 발생 후 90일이 지난 업무 이벤트를 삭제한다. 현재 회원 settingsRevision은 이력 파기로 초기화하지 않는다.

`NotificationRetentionService`는 기본 15분 주기로 실행한다. 한 트랜잭션은 최대 500행을 삭제하고 실행은 30초로 제한한다. 회원 → 설정/이력 순으로 잠그며 다른 작업의 잠금은 건너뛴다. 잠금 후 파기 조건을 다시 확인하여 재신청 행을 보존한다. 실패 청크는 롤백하고 다음 주기에 재시도한다. 미사용 알림 전용 이메일 참조는 정리하지만 `users.email`은 건드리지 않는다.

기존 비회원 이메일 알림은 신규 신청 중단 후에도 정리한다. 유효한 구독이 없는 client의 구독을
먼저 지우고, 자식이 모두 제거된 이메일 참조를 삭제한다. 같은 이메일에 다른 유효 구독이 있으면
그 취소 경로는 보존한다. 취소 요청은 접수 30일 경과 또는 해당 이메일 참조의 완전 제거 후 삭제한다.
게스트·취소 요청도 같은 500행 예산에 포함하며 잠긴 owner·미완료 청크는 다음 실행에서 재시도한다.
기존 회원 설정의 파기 기한 보완은 [DB 배포](privacy-deployment.md)를 따른다.

운영 지표는 `privacy.retention.*`, tag `job=notification`으로 마지막 성공 시각·삭제 건수·연속 실패·파기 지연 건수·최대 지연을 제공한다. 30분 이상 성공이 없거나 파기 지연이 30분을 넘는 경우 외부 모니터링의 운영팀 경보를 연결해야 한다. 지표 노출 자체를 경보 전달 완료로 간주하지 않는다. 서버 중단 중 즉시 물리 삭제를 보장하지 않으며 복구 후 지연을 점검한다.

회원 탈퇴는 회원 설정·업무 이벤트·알림 전용 이메일 참조를 계정과 함께 삭제한다. 자동 파기와 명령 처리는 같은 회원 잠금 순서를 사용한다. 관련 정책·권리행사와 외부 보관처 삭제는 [개인정보 처리 운영 기준](../../docs/privacy-policy.md)을 따른다.

회원 UI의 `/mypage/notifications`와 PC 중앙·모바일 하단 보관함은 받은 알림 준비 안내와 설정 목록을 분리한다. 이전 `/notifications` 경로는 지도 위 보관함으로 연결하며 비회원에게는 로그인 안내를 제공한다.
