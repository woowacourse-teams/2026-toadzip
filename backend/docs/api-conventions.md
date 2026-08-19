# HTTP API Conventions

## 경계 책임

```text
HTTP → Request DTO 검증 → Controller → Service → Domain / Repository → Response DTO
```

- Controller 책임과 Entity 응답 금지는 `../CODE_CONVENTION.md`를 원본으로 따른다.
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

## 오류 응답

```json
{
  "code": "NOTICE_NOT_FOUND",
  "message": "모집 공고를 찾을 수 없습니다.",
  "traceId": "공개 가능한 추적 식별자"
}
```

- `code`는 클라이언트 계약이고 `message`는 사용자 이해를 돕는다.
- 내부 예외명, SQL, 스택 트레이스와 비밀을 응답에 포함하지 않는다.
- 같은 실패는 모든 endpoint에서 같은 상태와 code로 매핑한다.
- 필드 검증 실패는 어떤 입력이 왜 거부됐는지 안전하게 표현한다.

## 변경과 호환성

- 기존 필드의 의미·타입 변경과 필수화는 공개 계약 변경이다.
- 추가 필드는 클라이언트가 모르는 필드를 허용하는지 확인한다.
- breaking change는 승인과 마이그레이션 경로 없이 배포하지 않는다.
- 요청·응답·오류 계약은 Controller 테스트로 고정한다.
