# Spring ↔ AI 워커 계약 예시

`ai_jobs`의 `input`(Spring이 쓰고 워커가 읽음)과 `result`(워커가 쓰고 Spring이 읽음) JSON 예시다.
Spring 테스트(`PdfExtractionContractTest`)와 워커 테스트가 같은 파일을 읽어 두 쪽 형식이 어긋나지 않게 한다.
필드를 바꾸면 이 예시와 양쪽 코드를 함께 고친다.

- `announcement-pdf-extraction/input.example.json`: 기관 공급 원천 행(`sourceSupplyRows`). 행마다 원천(`source`: `LH`·`GH`)과 공급 보정 작업실이 그 행을 찾는 식별자(`rowIdentifier`)를 담고, 값은 문자열이다(모르면 빈 문자열). LH 행은 LH 공급 API가 보낸 값 그대로, GH 행은 GH 공급행 틀의 값(지구명·주택형 이름·전용면적·세대수)이다. 원천이 없는 공고(SH 등)는 빈 목록이다. 예시는 두 원천의 행 모양을 함께 보이지만 실제 한 공고는 한 기관 원천만 가진다.
- `announcement-pdf-extraction/result.example.json`: PDF 보정 화면이 읽는 추출 결과. `json`은 추출 JSON을 문자열로 담고, 근거 쪽 번호는 원본 PDF 기준이다.

### 공급행의 원천 행 (`data.supplyRows[n].sourceRow`)

- 그 공급행이 나온 원천 행의 식별자(`input.sourceSupplyRows[].rowIdentifier`)다. 화면은 목록 위치가 아니라 이 식별자로 공급 보정 작업실의 행을 맞춘다.
- LH: 식별자는 `LH:{panId}:SUPPLY:{order}`이고 공급 보정 작업실의 `lhSupplyRows[].rowIdentifier`와 같은 값이다. Spring은 작업실과 같은 LH 공급 원천(최신 공고목록 요청의 원천)으로 입력을 만든다. 모델은 프롬프트의 LH 행 목록 번호(1부터)를 쓰고, 워커가 결과를 쓰기 전에 그 번호를 그 행의 식별자로 바꾼다. 번호가 없거나 범위를 벗어나면 `null`이다. 식별자를 모르는 이전 결과(정수)는 화면이 LH 행에 맞추지 않는다.
- GH: 식별자는 GH 공급행 틀의 행 키(`{공고 식별자}:{지구명}:{주택형 코드}`)이고 작업실 `rowSources`의 `GH_ANNOUNCEMENT` 원천 키와 같은 값이다. Spring은 제외하지 않은 틀 행만 담는다. GH 행은 모델에 주지 않고(공급표를 코드로 읽는 경로를 막지 않는다), 워커가 결과 공급행을 같은 지구명(공백 무시)·같은 주택형(틀 이름의 첫 낱말이나 이름 전체, 여럿이면 전용면적)인 틀 행과 맞춘다. 한 공급행에 한 틀 행, 한 틀 행에 한 공급행일 때만 채우고 그 밖에는 `null`이다. 화면은 그 틀 행을 이 공급행 값으로 고치도록 제안한다.
- LH가 여러 주택형을 한 행으로 합쳐 보냈는데 PDF 공급 표가 주택형마다 이번 모집수를 따로 적으면, 공급행이 주택형마다 나뉘고 모두 같은 `sourceRow`를 쓴다. 건설호수만 주택형별로 있으면 나누지 않는다.
- PDF가 여러 주택형을 모집수 하나로만 안내하면 공급행 하나에 `subtypes`로 두고 모집수를 나누거나 복제하지 않는다.
- `reserveCount`는 이번 공고에서 새로 모집하는 예비자 수다. 공고 전에 이미 대기 중인 예비자 수와 구분한다. `sharedCounts`는 여러 대상이 함께 쓰는 `reserveCount`도 경로로 보존하지만, 대상별 수로 나누거나 복제하지 않는다.
- 원천 행이 없는 공고(입력 `sourceSupplyRows`가 빔)는 이 필드가 없거나 `null`이다. 필드를 모르는 이전 결과도 그대로 읽을 수 있어야 한다.
- PDF 모집수가 LH 모집수와 달라도 그 필드를 `unresolvedFields`에 넣지 않고 `warnings`로만 알린다. 워커는 값을 고치지 않고 다음을 `warnings`에 남긴다: 번호가 없거나 범위를 벗어난 공급행, 공급행이 하나도 나오지 않은 LH 행, LH 모집수와 다른 PDF 모집수(나뉜 행은 합계로 비교하고, 모집수가 빈 행이 있거나 LH 모집수가 0이면 비교하지 않음).
- GH 틀과 맞추지 못한 것도 `warnings`로만 알린다: 같은 지구·주택형 틀 행이 없거나 여럿인 공급행, 여러 공급행이 맞은 틀 행, 맞는 공급행이 없는 틀 행. GH 모집수는 비교하지 않는다.

## 큐 상태 전이 (워커가 지킬 것)

DB 제약(`V20261103_01__ai_jobs.sql`)이 아래 상태별 열 규칙을 강제하므로 어기면 UPDATE가 거절된다. 시도 횟수와 마감은 워커가 지킨다.

- 꺼내기: `PENDING`·`RETRY` 중 `next_attempt_at <= now()`인 것, 또는 `RUNNING` 중 `lease_until <= now()`인 것을 `FOR UPDATE SKIP LOCKED`로 하나 잡는다. 이때 `status='RUNNING'`, 새 `lease_token`, `lease_until`, `attempts = attempts + 1`을 쓰고, `started_at`은 비어 있을 때만(첫 시작) 쓴다.
- 처리하는 동안 1분마다 `WHERE id = ? AND lease_token = ?`로 `lease_until`을 늘린다(lease 3분). 워커가 죽으면 늘리기가 멈춰 다른 워커가 다시 꺼낸다. 마감이 지난 작업은 늘리지 않는다.
- 마감: `created_at`부터 40분이 지난 `PENDING`·`RUNNING`·`RETRY`는 꺼내지 않고 `FAILED`(`DEADLINE_EXCEEDED`)로 끝낸다. 관리자 화면은 45분까지 기다린다. 워커가 멈춰도 Spring이 작업을 넣거나 상태를 읽기 전에 같은 마감·문구로 끝낸다(`AnnouncementPdfExtractionService.DEADLINE`). 마감을 바꾸면 두 곳을 함께 바꾼다.
- 관리자 취소: Spring이 진행 중 작업을 `FAILED`(`CANCELLED_BY_ADMIN`)로 끝낸다. 마감과 같이 `pdf`·`lease_token`·`lease_until`을 비우므로 그 작업을 처리 중인 워커의 이후 쓰기는 0행이 된다.
- 꺼내기 기한: 한 번의 시도(10분)를 마감 전에 끝낼 수 없는 작업, 곧 `created_at`부터 30분이 지난 작업은 꺼내지 않는다.
- 작업 하나는 최대 3번(워커 `MAX_ATTEMPTS`) 처리한다. lease가 끝난 `RUNNING`이 이미 3번을 썼으면 다시 꺼내지 않고 `FAILED`로 끝낸다.
- 완료·실패·재시도 쓰기는 `WHERE id = ? AND lease_token = ?`로 한다. lease를 빼앗긴 이전 워커의 쓰기는 0행이 된다.
- `SUCCEEDED`: `result`(JSON 객체), `completed_at`을 쓰고 `pdf = NULL`, 재시도 사유(`error_code`·`error_message`)는 비운다.
- `FAILED`: `error_code`, 관리자에게 보일 공개 문구 `error_message`, `completed_at`을 쓰고 `pdf = NULL`.
- `RETRY`: 일시 오류(네트워크, 429, 5xx, 시간 초과)이고 시도가 남았을 때만. `next_attempt_at`을 1분(첫 시도 뒤)·5분(그 뒤) 미루고 `lease_token`·`lease_until`을 비운다. 직전 오류를 `error_code`·`error_message`에 남겨 화면이 기다리는 동안 보여 준다. PDF는 남긴다. 미룬 시각이 꺼내기 기한을 넘으면 `RETRY`로 두지 않고 그 오류로 바로 `FAILED`로 끝낸다.
- 워커 역할(`toadzip_ai_worker`)은 `ai_jobs`를 읽고 상태·결과 열만 고칠 수 있다. 작업을 넣거나 지우지 않는다.
