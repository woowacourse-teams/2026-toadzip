# HTTP API와 오류

요청 형식은 Controller, 비즈니스 불변식은 Domain에서 검증한다.
기능별 필드·단위·페이지 크기는 해당 API 문서와 OpenAPI를 따른다.

## 요청과 응답

- 경로는 소문자 kebab-case 명사, JSON 필드는 lowerCamelCase를 사용한다.
- 날짜·시간은 ISO 8601과 시간대를 명시하고 금액·좌표 단위를 고정한다.
- `null`·생략·빈 값의 의미와 목록 정렬·동률 순서를 계약에 명시한다.
- 생성·조회·변경·삭제에 맞는 상태를 사용하고 비동기는 접수와 완료를 구분한다.
- 원문 출처 시각과 수집 시각을 구분한다. Entity·지연 로딩 객체를 API 응답으로 반환하지 않는다.

기존 필드의 의미·타입·필수 여부를 바꾸면 호환성과 이전 경로를 확인한다.
공개 계약 변경은 승인 후 배포하고 추가 필드도 클라이언트 호환을 확인한다.

## 인증과 입력

인증 설정은 `AdminSecurityConfiguration`에서 관리한다.
관리자 API는 ADMIN, 회원 전용 API는 USER 세션을 사용하고 쓰기 요청은 CSRF를 검증한다.
로그인·CSRF 발급 등의 공개 예외는 인증 설정에서 확인한다. UI에서 버튼을 숨기는 것으로 권한 검증을 대신하지 않는다.

회원 ID는 인증 정보에서 얻고 리소스 소유권을 확인한다. 비밀번호·토큰·인증키·개인정보는 응답·로그·예제에 넣지 않는다.
외부 URL·파일은 허용 호스트·리다이렉트·크기·타임아웃을 제한하며 파싱 실패를 성공으로 처리하지 않는다.

## 오류 응답

```json
{
  "code": "VALIDATION_FAILED",
  "message": "요청값이 올바르지 않습니다.",
  "traceId": "abc123",
  "errors": [
    {"field": "supplyRows[0].count", "reason": "100 이하여야 합니다."}
  ]
}
```

`code`와 HTTP 상태는 클라이언트 계약이다. 같은 실패는 같은 값으로 응답한다.
`errors`는 검증 실패 목록이며 `field`는 요청 JSON 경로다. 일반 오류에서는 생략한다.
문구·추적 식별자는 공개 가능한 값만 사용하고 내부 예외명·SQL·스택·비밀을 노출하지 않는다.

## 예외 처리

### 소유자

| 위치 | 책임 |
|---|---|
| `global.exception` | 공통 오류 응답·DTO 검증·JSON 파싱·HTTP 형식·미처리 예외 |
| `<feature>.exception` | HTTP·Spring에 의존하지 않는 기능 예외 |
| `<feature>.controller` | 기능 예외를 HTTP로 변환하는 Advice |

기능별 Advice는 구체적인 예외만 처리한다. `Exception`·`RuntimeException` 전체를 처리하지 않는다.
공통 Advice는 `Ordered.LOWEST_PRECEDENCE`로 둔다. Controller 패키지로 범위를 제한하지 않고 예외 타입으로 처리한다.
예: `HousingController → AnnouncementService → AnnouncementNotFoundException → AnnouncementExceptionAdvice`.

### 상태 매핑

| 실패 | HTTP |
|---|---|
| 요청·도메인 값 오류 | 400 |
| 리소스 없음 | 404 |
| 지원하지 않는 메서드 | 405 |
| 현재 상태와 충돌 | 409 |
| 지원하지 않는 미디어 타입 | 415 |
| 예상하지 못한 서버 오류 | 500 |

`IllegalArgumentException` 전체를 400으로 처리하면 프로그래밍 오류도 입력 오류로 숨길 수 있다.
예상 가능한 실패는 기능별 예외로 정의하고 상태·code·검증 필드 계약을 Controller 테스트로 검증한다.
