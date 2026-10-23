# HTTP API와 오류 계약

## 경계 책임

```text
HTTP → Request DTO 검증 → Controller → Service → Domain / Repository → Response DTO
```

- Controller 책임과 Entity 응답 금지는 [CODE_CONVENTION.md](../../backend/CODE_CONVENTION.md)를 원본으로 따른다.
- 문법 검증은 Controller, 비즈니스 불변식은 Domain에서 처리한다.
- 날짜, 통화, 좌표와 식별자의 형식과 단위를 명시한다.
- 알 수 없는 입력을 조용히 보정하지 않고 일관된 오류로 거부한다.

## 요청과 응답

| 항목 | 규칙 |
|---|---|
| 경로 | 리소스 중심 명사, 소문자 kebab-case |
| JSON 필드 | lowerCamelCase |
| 날짜·시간 | ISO 8601, 시간대 또는 UTC 명시 |
| 목록 | 안정된 정렬, 커서 또는 페이지 계약 명시 |
| 부재 | `null`, 생략, 빈 값의 의미를 계약별로 고정 |

- 성공 상태는 생성, 조회, 변경, 삭제 결과에 맞게 선택한다.
- 비동기 처리는 접수와 완료를 구분한다.
- 원문 출처와 시각은 응답에서 의미가 섞이지 않게 분리한다.

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

## 변경과 호환성

- 기존 필드의 의미·타입 변경과 필수화는 공개 계약 변경이다.
- 추가 필드는 클라이언트가 모르는 필드를 허용하는지 확인한다.
- breaking change는 승인과 마이그레이션 경로 없이 배포하지 않는다.
- 요청·응답 계약은 Controller 테스트로 고정한다.
