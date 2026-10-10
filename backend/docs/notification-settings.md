# 회원 알림 설정

회원은 단지·공고·지역 알림 설정을 이메일 입력 없이 저장한다.
새 공고 알림 생성·전달과 FCM은 제공하지 않는다. 화면에서 준비 중으로 안내한다.

## API

### 조회

`GET /api/v1/notification-subscriptions/me`: 인증된 회원의 유효 설정 조회. 대상에 `targetType`, `targetId`, `targetName`을 제공한다. 지역 이름은 프론트 지역 카탈로그에서 해석하며 삭제된 대상 이름은 null일 수 있다.

### 변경

- `POST /api/v1/notification-subscriptions/me`: 인증과 CSRF가 필요한 설정 변경. 기존 이벤트 요청 구조를 사용하며 `CONFIRMED`(저장)와 `CANCELLED`(해제)만 허용한다. 회원 ID는 인증 정보에서 얻는다.
- 변경 응답은 `200`과 `eventId`, `targetType`, `targetId`, `outcome`, `occurredAt`이다.

| `outcome` | 의미 |
|---|---|
| `ACTIVATED` | 새 활성화 |
| `ALREADY_ACTIVE` | 이미 활성 상태 |
| `CANCELLED` | 실제 해제 |
| `UNCHANGED` | 해제할 설정이 없음 |

같은 이벤트 재시도는 원래 결과와 시각을 반환하며 상태 변경을 반복하지 않는다.

## 저장 기준

- 이메일 없는 `CONFIRMED`는 설정만 저장하며 이메일 수신 동의나 푸시 권한으로 해석하지 않는다. 기존 이메일을 동반한 요청은 호환성을 유지한다.
- 기존 구독 테이블과 대상별 12개월 만료를 재사용한다. 별도 마이그레이션은 없다. 재시도는 같은 이벤트 ID를 사용한다.
- 삭제되거나 카탈로그에서 제외된 대상도 기존 설정을 해제할 수 있다.
- 기존 비회원 이메일 신청 API는 과거 신청의 호환성을 위해 남겨두지만 새 UI에서는 호출하지 않는다.
