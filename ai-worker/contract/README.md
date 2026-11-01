# Spring ↔ AI 워커 계약 예시

`ai_jobs`의 `input`(Spring이 쓰고 워커가 읽음)과 `result`(워커가 쓰고 Spring이 읽음) JSON 예시다.
Spring 테스트(`PdfExtractionContractTest`)와 워커 테스트가 같은 파일을 읽어 두 쪽 형식이 어긋나지 않게 한다.
필드를 바꾸면 이 예시와 양쪽 코드를 함께 고친다.

- `announcement-pdf-extraction/input.example.json`: LH 공급 API 행(LH가 보낸 문자열 그대로)과 연결 단지의 주택형 후보.
- `announcement-pdf-extraction/result.example.json`: PDF 보정 화면이 읽는 추출 결과. `json`은 추출 JSON을 문자열로 담고, 근거 쪽 번호는 원본 PDF 기준이다.

## 큐 상태 전이 (워커가 지킬 것)

DB 제약(`V20261103_01__ai_jobs.sql`)이 아래 규칙을 강제하므로 어기면 UPDATE가 거절된다.

- 꺼내기: `PENDING`·`RETRY` 중 `next_attempt_at <= now()`인 것, 또는 `RUNNING` 중 `lease_until <= now()`인 것을 `FOR UPDATE SKIP LOCKED`로 하나 잡는다. 이때 `status='RUNNING'`, 새 `lease_token`, `lease_until`, `attempts = attempts + 1`을 쓰고, `started_at`은 비어 있을 때만(첫 시작) 쓴다.
- 처리하는 동안 1분마다 `WHERE id = ? AND lease_token = ?`로 `lease_until`을 늘린다(lease 3분). 워커가 죽으면 늘리기가 멈춰 다른 워커가 다시 꺼낸다. 마감이 지난 작업은 늘리지 않는다.
- 마감: `created_at`부터 40분이 지난 `PENDING`·`RUNNING`·`RETRY`는 꺼내지 않고 `FAILED`(`DEADLINE_EXCEEDED`)로 끝낸다. 관리자 화면은 45분까지 기다린다.
- `attempts`는 `max_attempts`를 넘을 수 없다. lease가 끝난 `RUNNING`이 이미 `attempts = max_attempts`면 다시 꺼내지 않고 `FAILED`로 끝낸다.
- 완료·실패·재시도 쓰기는 `WHERE id = ? AND lease_token = ?`로 한다. lease를 빼앗긴 이전 워커의 쓰기는 0행이 된다.
- `SUCCEEDED`: `result`(JSON 객체), `completed_at`을 쓰고 `pdf = NULL`, 재시도 사유(`error_code`·`error_message`)는 비운다.
- `FAILED`: `error_code`, 관리자에게 보일 공개 문구 `error_message`, `completed_at`을 쓰고 `pdf = NULL`.
- `RETRY`: 일시 오류(네트워크, 429, 5xx, 시간 초과)이고 시도가 남았을 때만. `next_attempt_at`을 1분(첫 시도 뒤)·5분(그 뒤) 미루고 `lease_token`·`lease_until`을 비운다. 직전 오류를 `error_code`·`error_message`에 남겨 화면이 기다리는 동안 보여 준다. PDF는 남긴다.
- 워커 역할(`toadzip_ai_worker`)은 `ai_jobs`를 읽고 상태·결과 열만 고칠 수 있다. 작업을 넣거나 지우지 않는다.
