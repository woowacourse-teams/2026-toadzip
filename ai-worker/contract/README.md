# Spring ↔ AI 워커 계약 예시

`ai_jobs`의 `input`(Spring이 쓰고 워커가 읽음)과 `result`(워커가 쓰고 Spring이 읽음) JSON 예시다.
Spring 테스트(`PdfExtractionContractTest`)와 워커 테스트가 같은 파일을 읽어 두 쪽 형식이 어긋나지 않게 한다.
필드를 바꾸면 이 예시와 양쪽 코드를 함께 고친다.

- `announcement-pdf-extraction/input.example.json`: 기관 공급 원천 행(`sourceSupplyRows`). 행마다 원천(`source`: `LH`·`GH`)과 공급 보정 작업실이 그 행을 찾는 식별자(`rowIdentifier`)를 담고, 값은 문자열이다(모르면 빈 문자열). LH 행은 LH 공급 API가 보낸 값 그대로, GH 행은 GH 공급행 틀의 값(지구명·주택형 이름·전용면적·세대수)이다. 원천이 없는 공고(SH 등)는 빈 목록이다. 예시는 두 원천의 행 모양을 함께 보이지만 실제 한 공고는 한 기관 원천만 가진다.
- `announcement-pdf-extraction/result.example.json`: PDF 보정 화면이 읽는 추출 결과. `json`은 추출 JSON을 문자열로 담고, 근거 쪽 번호는 원본 PDF 기준이다.

### 공급행의 원천 행 (`data.supplyRows[n].sourceRow`)

- 그 공급행이 나온 원천 행의 식별자(`input.sourceSupplyRows[].rowIdentifier`)다. 화면은 목록 위치가 아니라 이 식별자로 공급 보정 작업실의 행을 맞춘다.
- LH: 워커는 이 필드를 채우지 않는다(`null`). 공급행은 PDF가 정하는 단지·전용면적 주택형 하나이고 신청자 구분(우선·일반·고령자·주거약자 등)은 그 행의 `targets`다. LH 공급 API 행은 공급행에 대응하지 않는 참고 자료로, 같은 공고 단지에서 전용면적(소수 둘째 자리, HALF_UP)이 같은 공급행에 여러 줄이 붙고 그 대응은 화면이 계산한다. LH 행 수와 공급행 수가 달라도 오류가 아니다. 이전 결과에 남은 LH 식별자는 화면이 LH 행에 맞추지 않아도 된다.
- GH: 식별자는 GH 공급행 틀의 행 키(`{공고 식별자}:{지구명}:{주택형 코드}`)이고 작업실 `rowSources`의 `GH_ANNOUNCEMENT` 원천 키와 같은 값이다. Spring은 제외하지 않은 틀 행만 담는다. GH 행은 모델에 주지 않고(공급표를 코드로 읽는 경로를 막지 않는다), 워커가 결과 공급행을 같은 지구명(공백 무시)·같은 주택형(틀 이름의 첫 낱말이나 이름 전체, 여럿이면 전용면적)인 틀 행과 맞춘다. 틀에 단지가 하나뿐이면 지구명이 달라도 그 단지로 보고, 주택형이 같은 틀 행이 없으면 전용면적이 같은 틀 행 하나와 맞춘다(같은 면적 행이 여럿이면 맞추지 않는다). 단지가 여럿인 틀은 이름이 맞아야 잇는다. 한 공급행에 한 틀 행, 한 틀 행에 한 공급행일 때만 채우고 그 밖에는 `null`이다. 화면은 그 틀 행을 이 공급행 값으로 고치도록 제안한다.
- 주택형·주택타입 열이 따로 있는 표에서 주택타입 여럿을 묶은 주택형 공급행(84 = 84A·84B…)의 모집수·예비자 수는 주택타입 행마다 적힌 수의 합이다. 병합된 공통 수량이 섞이거나 한 타입이라도 수가 없으면 비운다. `subtypes`는 세부 타입마다 따로 둔다.
- PDF가 여러 주택형을 모집수 하나로만 안내하면 공급행 하나에 `subtypes`로 두고 모집수를 나누거나 복제하지 않는다.
- 공급표에 주택타입 열이 있으면 주택형(신청형) 공급행의 `subtypes`는 그 신청형 칸이 걸친 주택타입 행에서 정한다. 전용면적(소수 둘째 자리)마다 세부 타입 하나이고 이름은 그 면적의 주택타입을 표 순서대로 `·`로 잇는다(`59A·59A1·59C`). 주택타입이 하나여도 세부 타입 하나를 둔다. `commonArea`·`supplyArea`는 그 면적 행들의 칸이 같을 때만, `constructedHouseholdCount`는 비운다. 공급행 자체의 면적은 신청형 행의 첫 면적이다. 공급행의 `sourceHousingTypeName`은 행 단위에 맞는 열의 표기다. 주택타입 행마다 뽑은 공급행은 주택타입(`84B`), 주택형마다 뽑은 공급행은 주택형(`84`)이다.
- 모집수(공급행 `totalSupplyHouseholdCount`, 대상 `supplyHouseholdCount`)는 이번 공고의 모집수다. 표가 입주자(당첨자)와 예비자 수를 따로 적으면 둘의 합이고, 그 합인 `계` 칸이 있으면 그 값이다.
- `reserveCount`는 이번 공고에서 새로 모집하는 예비자 수다. 공고 전에 이미 대기 중인 예비자 수와 구분한다. `sharedCounts`는 여러 대상이 함께 쓰는 `reserveCount`도 경로로 보존하지만, 대상별 수로 나누거나 복제하지 않는다.
- 원천 행이 없는 공고(입력 `sourceSupplyRows`가 빔)는 이 필드가 없거나 `null`이다. 필드를 모르는 이전 결과도 그대로 읽을 수 있어야 한다.
- PDF 모집수가 LH 모집수와 달라도 그 필드를 `unresolvedFields`에 넣지 않고 `warnings`로만 알린다. 워커는 값을 고치지 않고, 공급행마다 그 행의 전용면적(세부 주택형 포함) 가운데 하나와 면적이 같은 같은 단지 LH 행의 모집수 합계가 PDF 모집수와 다를 때만 `warnings`에 남긴다. 면적이 없는 LH 행, PDF 모집수가 빈 행, LH 모집수를 모르는(0) 줄이 붙은 행, 단지를 하나로 맞추지 못한 행은 비교하지 않는다.
- GH 틀과 맞추지 못한 것도 `warnings`로만 알린다: 같은 지구·주택형 틀 행이 없거나 여럿인 공급행, 여러 공급행이 맞은 틀 행, 맞는 공급행이 없는 틀 행. GH 모집수는 비교하지 않는다.

### 신청자격 (`data.eligibility`)

- 워커의 신청자격 단계가 신청자격·입주자격 장(그 장의 쪽만 모델에 보낸다)에서 뽑은 요건이다. 서버는 이것을 신청자격 초안(`EligibilityContent`)으로 바꾸고, 관리자가 검토해 공개한다. 그 단계는 일시 오류(연결 실패, 429, 5xx)면 한 번 다시 부르고, 그래도 실패하면 `null`이고 `warnings`에 이유가 남는다(작업은 성공으로 끝난다). 필드를 모르는 이전 결과도 그대로 읽을 수 있어야 한다.
- `common`은 공고의 모든 신청자에게, `targets[n]`은 공급 표의 대상 이름(`target`)이 같은 공급대상에만 적용한다. 묶음마다 `requirements`(판정 요건), `qualifications`(증명서로 확인하는 법정 자격: `exactName`·`legalBasis`·`conditionsAsWritten`), `summary`(모델에 80자 이내로 요청하는 요약)를 둔다. `exclusions`는 재당첨 제한 같은 신청 제한(`title`·`body`)이다. `targets[n]`은 신청 자격 경로 하나이고 `supplyTarget`은 그 경로가 속한 공급대상 이름이다(대학생 계층의 취업준비생 경로는 `supplyTarget` 대학생). 대상마다 `supplyTargets`에 `supplyTarget`을 공급대상 신청 조건과 같은 규칙으로 맞춘 공급표 대상 이름(`supplyRows[].targets[].target`)을 원문 그대로 싣고, 서버는 이 이름과 같은(공백만 무시) 공급대상만 잇는다. 공급표가 대상을 나누지 않아(대상이 없거나 `전체`뿐) 맞출 이름이 없으면 `supplyTargets`는 비고, 서버는 그런 공급행에 모든 경로를 잇는다. 한 묶음의 `qualifications`는 그중 하나만 갖추면 되는 자격이다. 경로가 둘 이상이면 모든 경로에 걸린 요건만 `common`에 두고 `common.summary`는 `null`이다. 요건을 빼서 판정이 넓어지지 않게, 경로가 가리킨 요건이 모델 응답에 없으면 그 자리에 `OTHER` 요건을 두고, 이름 없는 경로가 여럿이라 경로별 요건을 실을 곳이 없으면 `common`에 `OTHER` 요건을 하나 둔다(둘 다 `warnings`로 알린다). 어느 경로도 가리키지 않은 요건·자격은 결과에 싣지 않고 `warnings`로만 알린다. 공고가 "금회 모집은 1순위만"처럼 순위를 한정하면 받지 않는 순위의 경로는 싣지 않고 `warnings`로 알린다. 그 문구가 신청자격 쪽 원문에 있고, 문구에 적힌 순위가 모델이 답한 순위와 같고, 한정하는 말(…에 한합니다, …만 신청)이 있을 때만 뺀다. 확인하지 못하면 모든 순위의 경로를 싣고 `warnings`로 알린다.
- 요건 `kind`는 닫힌 목록이다: `AGE`(`scope` APPLICANT·YOUNGEST_CHILD, `minimum`·`maximum` 만 나이), `NO_HOUSE`(`scope` APPLICANT·HOUSEHOLD), `INCOME`(`scope` APPLICANT·HOUSEHOLD·APPLICANT_AND_PARENTS, `incomeStandard`, `incomeBaseYear`, `percent`, `householdSizeExtraPercent`, `adjustments`), `ASSETS`·`CAR_VALUE`(`scope`, `amountAsWritten`, `maximumKrw`, `adjustments`), `NO_CAR`(`scope`), `RESIDENCE`(`regions` 원문 이름, `minimumMonths`), `MARRIAGE_PERIOD`(`maximum` 년), `FAMILY_TYPE`(`familyTypes`), `UNIVERSITY_STUDENT`, `JOB_SEEKER`, `ANY_OF`(`options`, 한 단계만. 선택 요건 안의 `ANY_OF`, 원문에서 찾지 못한 선택 요건, 모델 응답에 없는 선택 요건은 `OTHER`로 실려 서버가 그 `ANY_OF` 전체를 직접 확인으로 둔다), `OTHER`. 모든 요건에 원문 `asWritten`이 있다. 가산 `adjustments[].kind`는 CHILDBIRTH·DUAL_INCOME·DISABILITY·OTHER다.
- 요건의 `asWritten`이 곧 근거 인용이다. `asWritten`의 낱말이 모두 신청자격 쪽 한 곳의 원문에 있을 때만 그 요건의 `evidence`(`eligibility...requirements[n]`, `quote`는 `asWritten`과 같음)가 붙는다. 근거가 없는 요건은 서버가 판정하지 않고 직접 확인으로 둔다.
- 숫자는 원문에서 확인한 값만 남는다. 나이·소득 비율(`percent`)·금액·혼인기간은 `asWritten` 안에, 가구원 수별 비율과 거주 개월은 `asWritten`이나 근거 쪽 글에 단위와 함께 있어야 한다. 가구원 수 가산(`householdSizeExtraPercent`)은 신청자격 쪽 어디든 "1인 가구 20%p"처럼 가구원 수와 가산값이 짝으로 적혀 있어도 인정한다. 짝에 '가구'가 없으면 바로 앞 80자 안에서 가장 가까운 표 머리말이 세대원·가구원일 때만 인정한다(출산자녀 수 가산 "1인 10%p"와 구분). 금액은 표기가 아니라 원 단위 값으로 대조한다. 숫자 없이 '원'으로 끝나는 낱말(세대구성원, 전원)은 금액으로 보지 않는다. 나이 하한 19는 `asWritten`에 숫자 대신 '성년'이 있어도 인정한다('미성년'은 제외). `asWritten`에 금액이 둘 이상이면("총자산 …원 및 자동차 …원") 그 요건의 낱말(총자산·자산, 자동차·차량) 바로 뒤 금액만 인정하고, 근거 쪽에서 더 큰 금액의 일부인 표기("3억 3,700만원"의 "3,700만원")는 인정하지 않는다. 한 숫자라도 확인하지 못하거나 금액 표기를 원으로 읽지 못하면 그 요건의 숫자를 모두 비우고 `warnings`에 남긴다. `maximumKrw`는 `amountAsWritten`(10,800만원·3억 3,700만원·37,080천원)을 코드가 바꾼 값이고, `incomeBaseYear`는 원문 연도 표현(전년도 등)을 모집공고일로 코드가 정한 값이다. 빈 값은 판정하지 않고 직접 확인으로 남긴다.
- 묶음 `summary`는 같은 대상의 `supplyRows[n].targets[m].applicationCondition`에 합쳐진다(공고 전체는 모든 대상에, 대상 요약은 핵심 이름이 같은 대상에).

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
