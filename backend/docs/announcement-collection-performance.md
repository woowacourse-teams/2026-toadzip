# 공고 수집 설정

마이홈 → LH 목록 → LH 공급 → LH 상세 순서로 수집한다.
LH의 현재 수집 대상은 매 실행 조회한다. 대상 범위는 [공고 원천 관리](announcement-source-lifecycle.md#lh)를 따른다.

## 요청 설정

마이홈은 페이지당 500행을 요청하고 `totalCount`까지 읽는다. LH 설정은 아래와 같다.

| 환경 변수 | 기본값 | 용도 |
|---|---|---|
| `LH_ANNOUNCEMENT_MAX_CONCURRENT_REQUESTS` | `8` | 공급·상세의 동시 요청 수. 허용 범위는 1~8 |
| `LH_ANNOUNCEMENT_CONNECT_TIMEOUT` | `PT3S` | 연결 대기 제한 |
| `LH_ANNOUNCEMENT_READ_TIMEOUT` | `PT10S` | 소켓 읽기 대기 제한 |

완료된 슬롯에 다음 요청을 넣는다. 같은 `panId`의 서로 다른 조회 조건은 순서대로 처리한다.
같은 실행에서 동일 요청을 공유하는 공고는 한 번 조회하고 각각 성공 연결을 남긴다.

읽기 타임아웃은 전체 수집을 10초 안에 끝내는 제한이 아니다.

## 실패 처리

### 호출 제한

HTTP 429 또는 게이트웨이 응답 코드 22/23은 재시도하지 않고 즉시 차단한다.
새 요청 투입과 남은 LH 수집 단계를 중단하고, 이미 시작한 작업은 정리한다.

### 일시 장애

- 재시도 가능한 일시 장애는 최초 호출을 포함해 최대 3회 시도한다.
- 연결·5xx 실패가 연속 5회면 30초 동안 차단한다. 이후 요청 1건으로 복구를 확인한다.
- 복구 확인이 성공하면 재개하고 실패하면 다시 차단한다.

차단 상태는 JVM 안에서 LH 목록·공급·상세가 공유한다. 다른 애플리케이션의 같은 인증키 사용까지 제한하지는 않는다.
차단된 단독 API 요청은 HTTP 503과 `LH_ANNOUNCEMENT_UNAVAILABLE`로 응답한다.

저장 실패도 새 요청 투입을 중단한다. 이미 성공한 원천은 보존하며, 실패한 요청을 성공으로 기록하지 않는다.
데이터 오류와 재수집 절차는 [LH 공고 연결과 복구](lh-announcement-link-resolution.md#복구)를 따른다.

## 성능 확인

관리 포트의 `/actuator/prometheus`에서 확인한다. 단계는 `step`, 외부 조회·저장은 `source`로 구분한다.

| Micrometer 이름 | 확인할 값 |
|---|---|
| `ingest.pipeline.step` | 단계 전체 소요 시간 |
| `ingest.external.request` | 외부 조회·파싱의 시도별 시간 |
| `ingest.external.retry` | 재시도 진입 횟수 |
| `ingest.external.retry.wait` | 재시도 전 대기 시간 |
| `ingest.announcement.store` | 원천 교체 트랜잭션 시간 |
| `ingest.lh.circuit.state` | 정상 `0`, 차단 `1`, 복구 확인 중 `2` |
| `ingest.lh.circuit.rejected` | 외부 호출 전에 차단한 횟수 |

LH 저장 시간에는 성공 수집 기록의 저장도 포함된다. 연결·실패 이력 등 후처리 전체의 시간은 아니다.
호출 지연을 비교할 때 `rejected`는 제외한다. 병렬 요청의 시간 합계는 실제 단계 소요 시간과 다르다.

`event=ingest.pipeline.step.finished`의 `executionId`, `step`, `result`, `durationMs`로 실행 이력과 연결한다.
호출 제한이나 지연이 늘면 동시성을 4 또는 2로 낮추고, 같은 대상·실제 호출 수로 단계 시간을 비교한다.
