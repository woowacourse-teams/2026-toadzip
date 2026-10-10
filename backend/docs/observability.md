# Observability

## 목표

에이전트와 운영자가 사용자 요청의 결과, 실패 원인과 성능을 저장소의 도구로 확인한다.
로그·메트릭·트레이스는 같은 요청을 연결할 수 있어야 한다.

## 문서 경로

| 주제 | 문서 |
|---|---|
| 로그 레벨·형식·기록 위치 | [logging-convention.md](logging-convention.md) |
| 비밀·개인정보 보호 | [security.md](security.md) |

## 메트릭

- 요청 수, 오류율과 지연시간을 endpoint 또는 유스케이스별로 본다.
- 외부 기관별 호출 성공률, timeout과 파싱 실패를 측정한다.
- 수집 시각과 출처 기준 시각의 지연을 측정한다.
- 고유 사용자·주택 ID처럼 cardinality가 큰 값을 label로 쓰지 않는다.
- SLO가 정해지지 않은 임의 임계치를 성공 기준으로 만들지 않는다.

## 트레이스

```text
HTTP → Controller → Service → DB Query / External API → Mapping → Response
```

- 외부 호출, 재시도, DB 병목과 비동기 경계를 span으로 구분한다.
- trace context가 비동기 실행과 메시지를 넘어 전파되는지 검증한다.
- span attribute에도 개인정보와 비밀을 넣지 않는다.

## 로컬 검증

- worktree마다 충돌 없는 포트와 격리된 실행 환경을 사용한다.
- 로그 검색과 핵심 메트릭 조회 명령을 제공한다.
- 장애 재현에는 요청, 관련 로그, traceId와 기대 결과를 함께 남긴다.
- 관측 도구가 없으면 성능·장애 관련 완료를 미검증으로 보고한다.
