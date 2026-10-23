# 공고 수집 동시성과 계측

## 적용 범위

- 관리자 공고 파이프라인의 마이홈 페이지 크기는 500행이다. `totalCount`까지 계속 조회한다.
- LH 상세·공급은 각 API 수집 실행에서 서로 다른 공고를 기본 최대 8개씩 처리한다. 한 요청이 끝나면 빈 슬롯에 다음 요청을 투입한다.
- 같은 `panId`의 다른 조회 조건은 요청 해시별로 원천을 저장한다. 수집 묶음에서는 기존 순서를 유지해 이전 요청의 원천·성공 수집 기록 저장까지 기다린다.
- 동일 요청을 공유하는 마이홈 공고는 외부 호출을 합치고 각 공고의 연결을 기록한다.
- 호출 제한이나 저장 예외를 관찰하면 새 요청을 시작하지 않는다. 이미 시작한 공고는 완료·실패 처리하고 저장 예외는 원래 예외로 전파한다.
- 실행 잠금은 작업 종료까지 유지한다. 호출 제한 응답은 재시도하지 않는다.
- 공급과 상세 단계의 실행 순서는 유지한다. 목록·공급·상세의 실행 잠금을 공유해 단독 API·다른 인스턴스도 동시에 실행하지 않는다.

LH 공급·상세의 실제 재조회와 저장 원천 재사용은 [수집 정책](../../features/ingest/overview.md#실제-재조회와-저장-원천-재사용)을 따른다.

## 동시성·타임아웃·장애 차단

| 장치 | 적용 값과 동작 |
|---|---|
| 동시성 제한(벌크헤드) | 상세·공급 최대 8개. 완료된 슬롯에 다음 요청을 투입하고 같은 PAN_ID는 순서를 유지 |
| 공통 실행 잠금 | LH 목록·상세·공급이 PostgreSQL advisory lock을 공유. 단독 API·파이프라인·같은 DB의 다른 인스턴스 중 한 작업만 실행 |
| LH 전용 타임아웃 | 연결 3초, 읽기 10초. 다른 외부 클라이언트의 10초/30초는 유지 |
| 서킷 브레이커 | 연결·5xx 실패가 연속 5회면 30초 차단. 이후 실제 재실행 요청 중 1건만 복구 확인. 성공 시 재개, 실패 시 다시 차단 |
| 호출 제한 | HTTP 429 또는 JSON 응답의 게이트웨이 22/23은 즉시 차단. 해당 요청은 재시도하지 않음 |
| 실패 처리 | 새 공고 투입 중단, 진행 중인 작업 정리. 성공 원천·성공 수집 기록 보존, 차단된 요청은 성공 처리하지 않음 |

서킷 상태는 JVM 단위이며 목록·상세·공급이 공유한다. 공통 DB 실행 잠금이 인스턴스 간 동시성을 막지만,
다른 애플리케이션에서 같은 인증키를 쓰는 호출과 구버전 인스턴스까지 제한하지는 않는다.
구버전 수집이 끝난 뒤 교체해야 공통 잠금이 일관되게 적용된다.
읽기 타임아웃은 소켓 대기 제한이며 전체 작업의 10초 마감 시간이 아니다. 개별 일시 장애는 기존 최대 3회
재시도 정책을 유지하지만, 회로가 열리면 추가 네트워크 호출을 차단한다.

호출 제한 보고를 받은 파이프라인은 남은 LH 수집 단계를 건너뛴다. 병렬 처리 중 회로 차단 예외가
발생한 실행은 FAILED로 기록하고 LH 장애/제한과 재실행 안내를 보존한다. 단독 API 차단 응답은
HTTP 503, `LH_ANNOUNCEMENT_UNAVAILABLE`이다. 이미 진행 중인 응답은 끝까지 정리하므로 즉시 종료를 보장하지 않는다.

설정은 `LH_ANNOUNCEMENT_MAX_CONCURRENT_REQUESTS`(기본 8, 허용 1~8),
`LH_ANNOUNCEMENT_CONNECT_TIMEOUT`(기본 `PT3S`), `LH_ANNOUNCEMENT_READ_TIMEOUT`(기본 `PT10S`)이다.
운영에서 제한·지연이 증가하면 동시성을 4 또는 2로 낮추고 기존 단계별 메트릭으로 확인한다.

## 운영에서 볼 값

관리 포트의 `/actuator/prometheus`로 노출한다.
공통 RetryExecutor의 호출 계측은 기존 다른 수집에도 적용되며 `source`로 구분한다.

| Micrometer 이름 | 의미 / 라벨 |
|---|---|
| `ingest.pipeline.step` | 단계 전체 시간 / `step`, `result` |
| `ingest.external.request` | 시도별 외부 조회·파싱 시간 / `source`, `result` |
| `ingest.external.retry` | 재시도 진입 횟수(회로 거절 포함) / `source` |
| `ingest.external.retry.wait` | 재시도 전 대기 시간 / `source` |
| `ingest.announcement.store` | 공고 원천·성공 수집 기록 저장 트랜잭션 시간 / `source` |
| `ingest.announcement.catalog.rows` | 성공한 LH 목록 수집의 신규·변경·동일 행 누적 수 / `change` (`new`, `changed`, `unchanged`) |
| `ingest.lh.circuit.state` | 0: 정상, 1: 차단, 2: 복구 확인 중 |
| `ingest.lh.circuit.opened` | 장애·제한으로 차단한 횟수 |
| `ingest.lh.circuit.rejected` | 외부 호출 전에 차단한 횟수 |

Timer는 Prometheus에서 `*_seconds_count`, `*_seconds_sum`, `*_seconds_max`로 나타난다.
재시도 Counter는 `ingest_external_retry_total`이다. 결과는 `completed`, `failed`,
`rate_limited`, `rejected`로 구분한다. `rejected`는 네트워크에 보내지 않은 시도다. 저장 Timer에는 성공과 실패한 저장 시도가 모두 포함된다.
저장 Timer는 원천 교체와 성공 수집 기록의 저장을 측정한다. 요청 시작·실패 기록, 공고 연결 후처리와 전체 DB 쿼리는 포함하지 않는다.
평균 호출 지연은 `rejected`를 제외한 request의 sum 증가량 / count 증가량으로 계산한다. 병렬 요청의 시간 합계는
실제 실행 시간과 다르므로 단계 시간에서 단순 차감하지 않는다.

단계 종료 로그 `event=ingest.pipeline.step.finished`의 `executionId`, `step`, `result`,
`durationMs`로 DB 실행 이력과 연결한다. 실행 ID는 MDC의 `executionId`로 파이프라인과 병렬 작업에
전달하고, 파이프라인을 시작한 HTTP 요청의 `traceId`와 구분한다. 메트릭 라벨에는 넣지 않는다.
