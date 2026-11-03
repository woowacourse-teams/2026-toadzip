# Spring ↔ AI 워커 계약 예시

`ai_jobs`의 `input`(Spring이 쓰고 워커가 읽음)과 `result`(워커가 쓰고 Spring이 읽음) JSON 예시다.
Spring 테스트(`PdfExtractionContractTest`)와 워커 테스트가 같은 파일을 읽어 두 쪽 형식이 어긋나지 않게 한다.
필드를 바꾸면 이 예시와 양쪽 코드를 함께 고친다.

- `announcement-pdf-extraction/input.example.json`: LH 공급 API 행(LH가 보낸 문자열 그대로).
- `announcement-pdf-extraction/result.example.json`: PDF 보정 화면이 읽는 추출 결과. `json`은 추출 JSON을 문자열로 담고, 근거 쪽 번호는 원본 PDF 기준이다.

### 공급행의 LH 원천 행 (`data.supplyRows[n].lhSourceRow`)

- 그 공급행이 나온 LH 공급 API 행을 가리킨다. 값은 `input.lhSupplyRows` 배열에서의 위치를 1부터 센 정수다. `order`(원천 순번)나 LH 식별자가 아니다. 워커 입력에 LH 식별자가 없어 위치로 가리킨다. Spring이 입력을 `source_order`, `id` 순서로 담으므로, 이 번호를 LH 행에 맞추는 쪽도 같은 순서로 센다.
- LH가 여러 주택형을 한 행으로 합쳐 보냈는데 PDF 공급 표가 주택형마다 이번 모집수를 따로 적으면, 공급행이 주택형마다 나뉘고 모두 같은 `lhSourceRow`를 쓴다. 건설호수만 주택형별로 있으면 나누지 않는다.
- PDF가 여러 주택형을 모집수 하나로만 안내하면 공급행 하나에 `subtypes`로 두고 모집수를 나누거나 복제하지 않는다.
- LH 공급 API 행이 없는 공고(입력 `lhSupplyRows`가 빔)는 이 필드가 없거나 `null`이다. 필드를 모르는 이전 결과도 그대로 읽을 수 있어야 한다.
- PDF 모집수가 LH 모집수와 달라도 그 필드를 `unresolvedFields`에 넣지 않고 `warnings`로만 알린다. 워커는 값을 고치지 않고 다음을 `warnings`에 남긴다: 번호가 없거나 범위를 벗어난 공급행, 공급행이 하나도 나오지 않은 LH 행, LH 모집수와 다른 PDF 모집수(나뉜 행은 합계로 비교하고, 모집수가 빈 행이 있거나 LH 모집수가 0이면 비교하지 않음).

## 큐 상태 전이 (워커가 지킬 것)

DB 제약(`V20261103_01__ai_jobs.sql`)이 아래 상태별 열 규칙을 강제하므로 어기면 UPDATE가 거절된다. 시도 횟수와 마감은 워커가 지킨다.

- 꺼내기: `PENDING`·`RETRY` 중 `next_attempt_at <= now()`인 것, 또는 `RUNNING` 중 `lease_until <= now()`인 것을 `FOR UPDATE SKIP LOCKED`로 하나 잡는다. 이때 `status='RUNNING'`, 새 `lease_token`, `lease_until`, `attempts = attempts + 1`을 쓰고, `started_at`은 비어 있을 때만(첫 시작) 쓴다.
- 처리하는 동안 1분마다 `WHERE id = ? AND lease_token = ?`로 `lease_until`을 늘린다(lease 3분). 워커가 죽으면 늘리기가 멈춰 다른 워커가 다시 꺼낸다. 마감이 지난 작업은 늘리지 않는다.
- 마감: `created_at`부터 40분이 지난 `PENDING`·`RUNNING`·`RETRY`는 꺼내지 않고 `FAILED`(`DEADLINE_EXCEEDED`)로 끝낸다. 관리자 화면은 45분까지 기다린다.
- 꺼내기 기한: 한 번의 시도(10분)를 마감 전에 끝낼 수 없는 작업, 곧 `created_at`부터 30분이 지난 작업은 꺼내지 않는다.
- 작업 하나는 최대 3번(워커 `MAX_ATTEMPTS`) 처리한다. lease가 끝난 `RUNNING`이 이미 3번을 썼으면 다시 꺼내지 않고 `FAILED`로 끝낸다.
- 완료·실패·재시도 쓰기는 `WHERE id = ? AND lease_token = ?`로 한다. lease를 빼앗긴 이전 워커의 쓰기는 0행이 된다.
- `SUCCEEDED`: `result`(JSON 객체), `completed_at`을 쓰고 `pdf = NULL`, 재시도 사유(`error_code`·`error_message`)는 비운다.
- `FAILED`: `error_code`, 관리자에게 보일 공개 문구 `error_message`, `completed_at`을 쓰고 `pdf = NULL`.
- `RETRY`: 일시 오류(네트워크, 429, 5xx, 시간 초과)이고 시도가 남았을 때만. `next_attempt_at`을 1분(첫 시도 뒤)·5분(그 뒤) 미루고 `lease_token`·`lease_until`을 비운다. 직전 오류를 `error_code`·`error_message`에 남겨 화면이 기다리는 동안 보여 준다. PDF는 남긴다. 미룬 시각이 꺼내기 기한을 넘으면 `RETRY`로 두지 않고 그 오류로 바로 `FAILED`로 끝낸다.
- 워커 역할(`toadzip_ai_worker`)은 `ai_jobs`를 읽고 상태·결과 열만 고칠 수 있다. 작업을 넣거나 지우지 않는다.
