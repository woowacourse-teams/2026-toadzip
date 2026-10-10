# 로그와 메트릭

HTTP 요청 로그와 오류 응답의 `traceId`로 같은 요청을 찾는다.
`HttpRequestLoggingFilter`가 method·URI path·status·durationMs를 기록하고 MDC에 `traceId`를 넣는다.
Controller마다 같은 요청 로그를 추가하지 않는다.

## 로그 작성

```text
event=<domain>.<action>.<state> key=value
```

| 레벨 | 사용 기준 |
|---|---|
| ERROR | 처리 실패로 운영자 확인이 필요함 |
| WARN | 누락·매칭 실패·재시도 등 이상이 있지만 처리를 계속함 |
| INFO | 수집 결과·주요 상태 변경 |
| DEBUG | 개발 중 상세 흐름. 운영 기본값에서는 비활성 |

예외는 처리 책임이 있는 곳에서 한 번만 기록한다. SLF4J placeholder를 사용하고 스택이 필요하면 예외를 마지막 인자로 전달한다.
단순 CRUD 성공·반복 이벤트·객체 전체를 불필요하게 남기지 않는다.

비밀번호·토큰·인증키·개인정보를 로그나 메트릭에 넣지 않는다.
메트릭 label에는 사용자·주택 ID처럼 종류가 계속 늘어나는 값을 쓰지 않는다.

## 메트릭 확인

관리 포트 `8081`의 `/actuator/prometheus`에서 HTTP 요청 수·시간과 기능별 지표를 확인한다.
수집 단계·외부 요청·재시도·차단 지표는 [공고 수집 설정](announcement-collection-performance.md#성능-확인)을 따른다.
로그 수집·대시보드 실행은 [모니터링 서버 설정](../../docs/MONITORING_SERVER_SETUP.md)을 따른다.
