# 관측 코드 작성 기준

## 목표

에이전트와 운영자가 사용자 요청의 결과, 실패 원인과 성능을 저장소의 도구로 확인한다.
로그·메트릭·트레이스는 같은 요청을 연결할 수 있어야 한다.

## 문서 경로

| 주제 | 문서 |
|---|---|
| 로그 레벨·형식·기록 위치 | [로그 규칙](logging.md) |
| 비밀·개인정보 보호 | [security.md](security.md) |

## 메트릭

- 요청 수, 오류율과 지연시간을 endpoint 또는 유스케이스별로 본다.
- 외부 기관별 호출 성공률, timeout과 파싱 실패를 측정한다.
- 수집 시각과 출처 기준 시각의 지연을 측정한다.
- 고유 사용자·주택 ID처럼 cardinality가 큰 값을 label로 쓰지 않는다.
- SLO가 정해지지 않은 임의 임계치를 성공 기준으로 만들지 않는다.

현재 API·부하테스트 대시보드의 사용·검증은 [모니터링 서버 설정](../../operations/monitoring.md)을 따른다.
HTTP 요청과 DB 연결풀·GC를 먼저 비교하고, 요청 표의 traceId로 같은 시간대의 원문 로그를 조회한다.
네이버 HTTP 호출은 공통 `RestClientConfiguration`의 관측 가능한 Builder를 사용한다.
기존 연결·읽기 timeout을 유지하며 검색어·인증정보·응답 본문은 메트릭 라벨과 실패 로그에서 제외한다.
외부 실패 로그의 `failure`는 configuration·upstream_http·timeout·io·invalid_response를 구분한다.

## DB 통신 제한과 복구

기본 DB와 공유 DB 모두 연결풀 대기는 5초, 연결 검증은 2초로 제한한다.
PostgreSQL JDBC의 TCP 연결은 5초, 로그인은 10초, 소켓 읽기는 기본 120초로 제한하고
TCP keepalive를 활성화한다. 요청의 연결풀 대기 종료가 JDBC 연결 생성 작업까지 취소하지 않으므로
드라이버 제한시간도 두 DataSource에 각각 적용한다.

`DB_SOCKET_TIMEOUT_SECONDS`로 두 DB의 읽기 제한을 함께 조절할 수 있다.
이 제한은 네트워크 무응답뿐 아니라 오래 응답하지 않는 쿼리·COPY 완료 대기에도 적용된다.
긴 수집 작업이 제한을 넘는다면 실제 SQL 지연을 확인한 뒤 값을 조정한다.
로컬 복구 테스트는 JDBC 인증 응답과 사용 중 쿼리 응답을 각각 끊었다가 복원하여,
같은 Hikari 풀에서 다시 조회되고 사용 중 연결 수가 0으로 돌아오는지 확인한다.

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
