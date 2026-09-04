# Exception Handling

## 패키지와 책임

```text
global/exception/
├── ErrorResponse
└── GlobalExceptionAdvice
<feature>/
├── controller/<Feature>ExceptionAdvice
└── exception/
    ├── <Feature>NotFoundException
    └── Invalid<Feature>Exception
```

- `global.exception`: 기능과 무관한 공통 오류와 응답 모델
- `<feature>.exception`: Spring과 HTTP에 의존하지 않는 기능 예외
- `<feature>.controller`: 기능 예외를 HTTP 응답으로 변환하는 Advice
- 기능별 예외와 비즈니스 규칙을 `global.exception`에 모으지 않는다.

## 오류 응답

```json
{
  "code": "ANNOUNCEMENT_NOT_FOUND",
  "message": "모집 공고를 찾을 수 없습니다.",
  "traceId": "공개 가능한 추적 식별자"
}
```

```json
{
  "code": "VALIDATION_FAILED",
  "message": "요청값이 올바르지 않습니다.",
  "traceId": "abc123",
  "errors": [
    {"field": "applicationStartDate", "reason": "필수 값입니다."},
    {"field": "supplyRows[0].count", "reason": "100 이하여야 합니다."}
  ]
}
```

- `code`는 고정된 클라이언트 계약이고 같은 실패는 endpoint와 관계없이 같은 상태와 `code`로 응답한다.
- `message`와 `reason`에는 공개 가능한 문구만 쓰고 내부 예외명, SQL, 스택 트레이스와 비밀을 포함하지 않으며 `traceId`에도 공개 가능한 식별자만 사용한다.
- `errors`에는 가능한 모든 검증 실패를 담고 `field`는 요청 JSON의 필드명 또는 경로를 사용한다.

## Advice 책임과 상태

| 오류 의미 | HTTP 상태 |
|---|---|
| 잘못된 요청 또는 도메인 값 | 400 |
| 리소스를 찾을 수 없음 | 404 |
| 지원하지 않는 HTTP 메서드 | 405 |
| 현재 상태와 요청이 충돌함 | 409 |
| 지원하지 않는 미디어 타입 | 415 |
| 예상하지 못한 서버 오류 | 500 |

- `GlobalExceptionAdvice`는 DTO 검증, JSON 파싱, 지원하지 않는 메서드·미디어 타입과 미처리 예외를 담당한다.
- 기능별 Advice는 소속 기능의 구체적인 예외만 처리하고 `RuntimeException`, `Exception`을 처리하지 않는다.
- 기능별 Advice는 공통 Advice보다 우선하며 공통 Advice는 `Ordered.LOWEST_PRECEDENCE`로 둔다.
- Advice는 시작 Controller가 아니라 예외 타입으로 선택하며 특정 Controller 패키지로 적용 범위를 제한하지 않는다.
- 예: `HousingController → AnnouncementService → AnnouncementNotFoundException → AnnouncementExceptionAdvice`

## 예외 선택

- `IllegalArgumentException`을 전역에서 모두 400으로 처리하지 않는다. 프로그래밍 오류와 내부 전제조건 위반을 사용자 입력 오류로 위장할 수 있다.
- 예상 가능한 도메인 실패는 의미가 드러나는 기능별 예외로 정의한다.
- 클라이언트가 실패 원인을 구분해야 하면 별도 예외 타입과 고정된 오류 `code`를 제공한다.
- 오류 상태, `code`와 검증 필드 계약은 Controller 테스트로 고정한다.
