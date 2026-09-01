# Backend Logging Convention

## 기본 원칙

- 로그는 장애 추적, 요청 흐름과 주요 이벤트 확인을 위해 작성한다.
- 자유로운 문장보다 검색 가능한 구조화 로그를 사용한다.
- 동일한 내용이나 예외를 중복 기록하지 않는다.
- 민감정보, 객체 전체와 불필요하게 상세한 데이터는 기록하지 않는다.

## 로그 레벨

| 레벨 | 기준 | 예시 |
|---|---|---|
| `ERROR` | 처리가 실패해 운영자 확인이 필요함 | 예상하지 못한 예외, DB·핵심 외부 시스템·배치 실패 |
| `WARN` | 비정상이지만 처리를 계속할 수 있음 | 데이터 누락·매칭 실패, 재시도·fallback, 예상하지 못한 값 |
| `INFO` | 운영 중 확인할 가치가 있는 주요 이벤트 | 수집·배치 결과, 주요 상태 변경, 인증 결과 |
| `DEBUG` | 개발 중 상세한 실행 흐름을 확인함 | 중간 계산값과 분기 결과 |

- 단순 CRUD 성공은 별도로 기록하지 않는다.
- 반복적으로 발생하는 로그에는 `INFO` 사용을 지양한다.
- 운영 환경에서는 `DEBUG`를 기본적으로 비활성화한다.

## 로그 형식

```text
event=<domain>.<action>.<state> key=value key=value
```

| 필드 | 목적 |
|---|---|
| `timestamp` | 사건 시각과 시간대 |
| `level` | 심각도 |
| `traceId` | 요청과 트레이스 연결 |
| `event` | `<domain>.<action>.<state>` 형식의 안정된 이벤트 이름 |
| `result` | 처리 결과 |
| `source` | 데이터 또는 요청 출처 |
| `durationMs` | 처리 시간 |
| `errorCode` | 내부 오류 코드 |

- 예시는 `announcement.collection.completed`, `external_api.request.failed`이다.
- `traceId`는 MDC에 저장하고 로그 출력 형식에서 공통으로 포함한다.

## HTTP 요청과 예외

- HTTP 요청 로그는 Controller마다 작성하지 않고 Filter 또는 Interceptor에서 공통 처리한다.
- 요청 로그에는 HTTP method, URI path, status code와 처리 시간을 기록한다.
- 예외는 처리 책임이 있는 경계에서 한 번만 기록한다.
- 스택 트레이스가 필요하면 예외 객체를 SLF4J 호출의 마지막 인자로 전달한다.

```java
log.error("event=external_api.request.failed uri={}", uri, exception);
```

- SLF4J placeholder를 사용하고 문자열을 직접 조합하지 않는다.
- 필요한 식별자와 처리 결과만 기록하며 상세한 보안 기준은 [security.md](security.md)를 따른다.
