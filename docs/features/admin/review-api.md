# 관리자 비교·검토 API

관리자는 `실패·검토 항목`이나 단지·공고 목록에서 대상을 찾는다. 단지는 상세의 `데이터 검증` 탭, 공고는 `출처·수정 이력`의 수집 원천 비교에서 비교·수정한다.
값 수정, 등록값 검토 기록, 재수집 대비 원천 확인은 각각 다른 기록이다.

변경·검토를 수행한 관리자는 인증 정보에서 결정하고 처리 시각과 함께 이력에 남긴다.

## 공고·주택형 비교

- 기준: `/api/admin/quality/{COMPLEX|ANNOUNCEMENT}/{id}/comparison`.
- GET의 `target`: 단지는 `basic`·`type:{id}`(주택타입), 공고는 `basic`·`cell:{id}`(공급표 칸)다. 칸의 값은 공급대상 표기·신청 조건, 그 칸만의 모집·예비 수, 임대조건 금액이다.
- GET은 현재 상세·원천 행·비교 토큰을 반환한다. 공고는 정확한 공고 식별자·LH PAN ID로 원천을 찾는다.
- 주택형은 단지 원천을 함께 표시하므로 해당 주택형인지 직접 확인한다. 이름 유사도로 자동 연결하지 않는다.
- POST/DELETE `/sources`: 비교용 원천 연결·해제. 자동 정제 연결을 변경하지 않는다.
- PUT은 선택한 필드만 `values`로 받으며 기존 수정 서비스를 통해 검증·보호·이력을 적용한다. 공급표 칸은 여기서 고치지 않고 400으로 거절한다. 공급정보는 [공급정보 보정](#공급정보-일괄-보정과-범위-검토)에서 고친다.
- 단지 기본정보는 기존 [단지 원천 비교](review-api.md#단지-기본정보-비교와-수정)를 사용한다.
- 조회 후 현재 상세·원천·연결이 달라지면 409로 거부한다. 관리자·CSRF·파이프라인 소유권·행 잠금 적용.
- 금액은 단위·최소/전체 범위가 불명확한 원천 문자열을 자동 환산하지 않는다. 원 단위 정수 문자열로 직접 확인한다.
- 변경 전후와 당시 원천 행 전체·근거·수정 대상을 이력에 보존한다.

## 재수집 후 재검토

- POST `/checks`: 수정 없이 현재 원천 확인 근거를 기록한다. 단지 기본 비교는 `/checks/legacy`를 사용한다.
- 기존 수집값 불일치·미검토 사유는 확인 기록과 별도로 유지한다.
- 확인 기록은 새 행으로 추가하며 이전 근거를 덮어쓰지 않는다. 값 수정이나 전체 검증 완료를 의미하지 않는다.

## 공고문 조회와 기존 스키마

공고 관리 상세는 기존 `announcement_attachments`의 파일 식별자·이름·종류·URL을 표시순서로 반환한다.
첨부파일 본문은 기존 공개 공고 첨부 미리보기·다운로드 API를 사용한다. 휴지통 공고는 공식 원문 링크로 안내한다.
PDF 추출(`POST /api/admin/announcements/{id}/pdf-extractions`, 공고 첨부는 `.../attachments/{attachmentId}`)은 업로드하거나 서버가 받은 첨부 PDF를 AI 작업(`ai_jobs`)으로 넣고, AI 워커가 외부 AI 모델로 추출한 결과와 PDF의 SHA-256을 작업 행에 남긴다. PDF 원본은 처리 중에만 보관하고 작업이 끝나면 비운다. 공유 초안 API와 로컬 폴더 연동은 없다.

`PDF_AUTO_EXTRACTION_ENABLED=true`이면 서버가 PDF 추출 작업을 스스로 넣는다(`PdfAutoExtractionScheduler`, 요청 출처 `BATCH`). 결과는 관리자가 PDF 보정 화면에서 검토하고 적용할 때만 공고에 반영된다.

- 대상: 휴지통이 아니고 접수가 끝나지 않았으며 아직 추출한 적이 없는 공고(처리 단계 '추출 전')다. 최근 공고부터 넣는다.
- 첨부: 공고 화면과 같은 기준으로 고른다. 종류가 공고문인 첨부, 이름에 '공고문'·'모집공고'가 든 PDF, 첫 PDF 순서다.
- 서버 시작 3분 뒤부터 5분마다(`PDF_AUTO_EXTRACTION_CHECK_INTERVAL`) 확인하고, 진행 중인 서버 작업이 2건(`PDF_AUTO_EXTRACTION_MAX_IN_FLIGHT`)이 되도록 채운다. 서버 작업은 마감(40분) 안에 끝나도록 이만큼만 동시에 둔다.
- 서버 작업이 실패하면 다시 넣지 않는다. 관리자가 실패 원인을 보고 다시 요청한다. PDF가 아니거나 읽을 수 없는 첨부는 서버가 다시 시작될 때까지 다시 받지 않고, 원천 응답 실패는 다음 확인에 다시 받는다.
- 이미 추출한 공고의 공고문이 바뀌어도 서버가 다시 추출하지 않는다. 관리자가 다시 요청한다.
- 워커는 관리자 작업을 서버 작업보다 먼저 꺼낸다. 관리자가 서버 작업이 기다리는 공고를 요청하면 그 작업이 관리자 작업으로 바뀌어 먼저 처리되고, 같은 PDF면 화면이 그 작업을 기다린다. 다른 첨부를 고른 요청은 진행 중 작업 때문에 거절된다.
- 서버 작업은 OpenAI Flex 처리로 불러 비용을 줄이고, 같은 PDF·입력·워커 버전으로 성공한 작업이 있으면 모델을 부르지 않고 그 결과를 쓴다(관리자 작업도 같다). 규칙은 [워커 계약](../../../ai-worker/contract/README.md#큐-상태-전이-워커가-지킬-것)에 있다.
과거 원본·초안·담당 업무 테이블을 만든 마이그레이션과 기존 데이터는 보존한다. 기능 제거를 위해 공유 DB 데이터를 삭제하지 않는다.
비교 연결·확인 스냅샷은 `V20261016_01`을 계속 사용한다.

## 공급정보 일괄 보정과 범위 검토

공급정보는 공고 공급표 하나에 저장한다. 공급표는 주택형(신청형) 행, 공급대상, 주택형×공급대상 칸, 모집·예비 수 묶음, 임대조건으로 이루어진다. 모든 쓰기는 공급표 저장(`SupplySheetStore`)이 맡는다.

### 공급표 구조

- 주택형은 (공고 단지, 신청형)마다 하나다. 공고 단지가 없으면 공고의 단지명으로 가린다. 신청형은 형명 끝의 신청자 표기(`(고령자)`·`[청년]`)를 뗀 이름이다. 같은 전용면적이어도 신청형이 다르면(`55A1`·`55C(주거약자)`) 주택형을 따로 두고 같은 단지 주택타입을 가리킬 수 있다.
- 공급대상은 공고마다 (표기, 신청 조건)으로 하나다. 우선·일반·고령자 같은 신청자 묶음이 공급대상이고, 칸은 (주택형, 공급대상)마다 하나다.
- 공급대상은 분류(`groups`) 목록을 가진다. 값은 `GENERAL`·`YOUTH`·`UNIVERSITY_STUDENT`·`NEWLYWED`·`PRE_NEWLYWED`·`SINGLE_PARENT`·`ELDERLY`·`HOUSING_BENEFIT_RECIPIENT`·`GA_CLASS`·`NA_CLASS`·`EXISTING_RESIDENT`·`INDUSTRIAL_WORKER`·`HOUSING_VULNERABLE`이고 이 순서로 저장한다. 요청에서 빠지면(`null`) 저장된 같은 공급대상(표기·신청 조건이 같은 대상, 없으면 같은 ID로 이름을 고친 대상)의 분류를 그대로 두고, 새 공급대상은 분류가 없다. 분류를 지우려면 빈 목록(`[]`)을 보낸다. 같은 분류가 두 번이거나 빈 값·목록에 없는 값이 있으면 400으로 거절한다. 분류는 자연 키에 들어가지 않으며, 분류만 바꿔도 그 공급대상을 관리자 수정으로 보호한다.
- 모집·예비 수 묶음은 종류(`WINNER`·`RESERVE`), 수, 멤버 칸 목록이다. 칸 하나의 수는 멤버가 그 칸 하나인 묶음이고, 여러 칸이 함께 쓰는 수(공고문의 병합 칸, `49A·49B·49C 함께 모집 70`)는 멤버가 여럿인 묶음 하나다. 주택형 모집수는 그 주택형의 모든 칸을 멤버로 둔 `WINNER` 묶음이다. 같은 칸 목록에 같은 종류의 묶음은 하나만 둔다.
- 임대조건은 보증금·월 임대료·전환보증금과 멤버 칸이다. 칸 하나에는 임대조건 하나만 둔다.
- 저장은 자연 키로 기존 ID를 이어 쓴다: 주택형은 (공고 단지 또는 단지명, 신청형), 공급대상은 (표기, 신청 조건), 칸은 (주택형, 공급대상)이다. 모집·예비 수 묶음과 임대조건은 공고마다 지우고 다시 넣는다. 저장할 공급표에 없는 주택형과 어느 칸도 쓰지 않는 공급대상은 지운다.

### 작업실 조회

- GET `/api/admin/announcements/{id}/supply-corrections`는 `{snapshotToken, version, sheet, announcementComplexes, applicationTypeNames, sourceReference, warnings}`를 준다.
- `sheet`는 공급표 보기다. 개체 키는 `row:{id}`(주택형), `target:{id}`, `cell:{id}`, `quota:{id}`, `lease:{id}`다. 주택형은 공고 단지·단지명·연결 단지·연결 주택타입·이름·공고별 전용·공급면적·입주 예정월·공급 PNU·공급 구분·표시 순서·`adminModified`를 갖는다. 수 묶음은 `{kind, count, members, adminModified}`, 임대조건은 원 단위 정수 문자열 금액과 `members`, `adminModified`다.
- `announcementComplexes`는 공고 단지 `{id, name, roadAddress, housingComplexId, housingComplexName, linkMethod, rowCount}`다. `linkMethod`는 `AUTO`·`ADMIN`·`MIGRATED`이고 연결이 없으면(휴지통 단지 포함) null이다. `rowCount`는 그 공고 단지의 주택형 수다.
- `applicationTypeNames`는 주택형 키별 신청형 이름이다. 읽기 전용이며 요청에는 보내지 않는다.
- `sourceReference`는 원천 공급 행 `{complexLabel, typeName, exclusiveArea, householdCount}`다. LH 공고는 LH 공고 ID(`lh_pan_id`)로 찾은 최신 LH 공급 원본 행, GH 공고는 공고 원천의 지구별 공급 행이다. `householdCount`는 원천의 이번 공급 수(정수가 아니면 null)다. 공급표를 정하지 않으며 세대수 검산에만 쓴다. 원천 행 수와 주택형 수가 달라도 오류가 아니다.
- `warnings`는 공급 구분이 없는 주택형 같은 저장 전 확인 사항이다.

### 보정 요청과 미리보기

- 요청은 `{operationId, snapshotToken, reason, sourceUrl, sourcePage, sheet}`다. `sheet`는 저장할 공급표 전체 `{housingTypes, targets, cells, quotas, leaseTerms}`이며 정의되지 않은 JSON 필드는 400으로 거절한다.
  - 기존 개체는 조회한 키를, 새 개체는 `new:`로 시작하는 요청 키를 쓴다. 수 묶음은 `{kind, count, members}`, 임대조건은 `{rentalDeposit, monthlyRent, convertedDeposit, members}`다.
  - 요청에 없는 주택형·공급대상·칸·수·임대조건은 지운다.
- POST `/preview`는 변경 전후 공급표(`before`·`after`), 경고와 `confirmationToken`을 준다. 저장하지 않는다. 바뀐 것이 없으면 400(`변경한 공급정보가 없습니다`)이다. `after`의 새 개체는 요청 키를 쓴다.
- 400으로 거절하는 요청:
  - 한 단지에 같은 신청형 주택형이 둘(`한 단지에서 주택형(신청형)은 하나입니다`), 공급 구분이 없는 주택형, 이 공고의 공고 단지가 아닌 공고 단지, 이 공고에 없거나 두 번 보낸 기존 키.
  - 같은 분류가 두 번 있는 공급대상, 같은 (표기, 신청 조건) 공급대상 둘, 한 주택형의 같은 공급대상 칸 둘, 같은 칸 목록의 같은 종류 수 둘, 금액이 없는 임대조건, 칸 하나에 임대조건 둘.
  - 기존 주택타입(`housingTypeId`)과 새 주택타입(`newHousingType`)을 함께 지정, 고른 단지에 속하지 않은 주택타입.
- 단지 주택타입 연결: 화면은 연결할 주택타입(`housingTypeId`)이나 새 주택타입(`newHousingType: {name, exclusiveArea, supplyArea}`)을 정해 보낸다. 새 주택형이거나 지금까지 주택타입이 없던 주택형이 단지와 양수 전용면적만 주고 둘을 비우면 서버가 정한다. 이름이 같은 주택타입, 없으면 같은 면적 주택타입이 하나뿐일 때 그것, 둘 다 없으면 첫 표기에서 신청자 표기를 뗀 이름과 그 면적으로 새 주택타입을 만든다. 주택타입이 있던 주택형을 비워 보내면 연결을 끊은 것이라 다시 채우지 않는다.
  - 새 주택타입은 단지에 이름이 같은 주택타입이 있으면(휴지통 포함) 400이다. 한 저장에서 단지·이름이 같은 새 주택타입은 하나만 만들고, 면적이 다르면 400이다.
- 경고(저장은 막지 않음): 단지·주택타입 연결이 미확정인 주택형, 공급대상 칸이 없는 주택형, 주택형이 없는 공급표.
- 409 응답의 `code`로 대응을 나눈다. 조회 이후 저장값·원천이 바뀌어 snapshotToken이나 confirmationToken이 맞지 않으면 `ADMIN_DATA_STALE`이며, 최신값을 다시 조회해 입력을 맞추면 저장할 수 있다. 접수 일정이 걸린 단지의 연결을 없애는 저장, 같은 작업 번호의 다른 요청 같은 규칙 위반은 `ADMIN_DATA_CONFLICT`이며 다시 조회해도 풀리지 않으므로 `message`를 그대로 보여 준다.
- 금액은 원 단위 18자리 이하 정수 문자열, 수량은 음수가 아닌 정수다.

### 적용

- POST `{correction, confirmationToken}`. 공고 잠금과 수집 소유권 아래 미리보기를 다시 계산해 확인한 뒤 한 트랜잭션으로 저장한다.
  - 공급정보 주인을 공고문으로 바꾼다. 이후 LH·GH 정제는 이 공고의 공급표를 다시 만들지 않는다.
  - 새 주택타입을 만들고(`MANUAL:{operationId}:{요청 키}`), 공급표를 바꾸고, 손댄 주택형이 속한 공고 단지를 관리자 연결로 정한다. 한 공고 단지의 주택형은 같은 단지로 연결해야 하며 아니면 400이다.
  - 새로 넣거나 이미 관리자가 고친, 또는 이번에 바뀐 주택형·공급대상·수·임대조건은 관리자 수정(`adminModified`)으로 보호한다.
  - `CORRECT_SUPPLY` 이력의 변경 전 값은 `{sheet}`, 변경 후 값은 `{sheet, reason, sourceUrl, sourcePage, operationId}`다. 신규 알림을 만들지 않는다.
- 같은 작업 UUID·같은 요청은 기존 changeId와 현재 작업실을 `alreadyApplied: true`로 돌려준다. 같은 UUID의 다른 요청은 409다.
- POST `/announcement-complexes/{announcementComplexId}/link` `{snapshotToken, housingComplexId}`는 공고 단지 하나를 관리자가 고른 단지로 다시 연결한다. 그 공고 단지의 주택형은 새 단지에서 LH 대표 전용면적(없으면 공고 면적)과 이름으로 고른 주택타입으로 옮긴다. 같은 이름, 없으면 같은 면적이 하나뿐인 주택타입이며 맞는 주택타입이 없으면 비운다. 지금 연결과 같은 단지면 확인만 하고 주택형 연결은 그대로 둔다. 휴지통 단지는 400이다. 이력은 `LINK_BY_ADMIN`으로 주택형의 이전·이후 단지·주택타입과 함께 남는다.

### 범위 검토

- POST `/reviews`: snapshotToken, 대상(`row:{id}` 주택형 또는 `cell:{id}` 칸), 확인 필드·선택 원천 필드·근거·VERIFIED/ON_HOLD를 기록한다.
  - `row:`의 값은 주택형 값(단지명, 이름, 연결 단지·주택타입, 면적 보정, 공급 PNU, 입주 예정월, 공급 구분, 표시 순서)과 주택형 모집수다.
  - `cell:`의 값은 공급대상 표기·신청 조건, 그 칸만의 모집·예비 수와 임대조건 금액이다.
- GET `/reviews`: 최근 100개 기록, 선택 범위의 확인값·현재값·원천 차이·현재 판단을 반환한다. 확인한 값이나 원천이 바뀌면 `STALE`이다. 확인 범위 밖의 변경과 수집 메타데이터 변경은 검토 무효 사유가 아니다.
- 공통 주택타입의 GET `/api/admin/housing-complexes/{id}/housing-types/{typeId}/impact`는 그 주택타입을 가리키는 공고 주택형과 토큰을 반환한다. 공고 주택형이 하나라도 가리키는 주택타입은 전용면적 수정을 409로 거절한다. 이름·세대수는 영향을 확인한 뒤 수정할 수 있다.
- `V20261019_05`는 의견 처리, `_06`은 공급 보정·제외·공고별 면적, `_07`은 범위 검토를 저장한다. `V20261113_01`이 공고 공급표를 만들고, `V20261114_01`이 옛 공급행 구조를 없앤다. `V20261114_01`은 여러 주택타입을 함께 가리키던 행을 주택타입마다 한 행으로 나누고, 원래 칸의 수·임대조건을 나눈 행들이 함께 쓰는 묶음으로 둔다. 되돌릴 수 없다.
- 주요 검증: `SupplyCorrectionIntegrationTest`, `SupplySheetStoreIntegrationTest`, `SupplySheetOnlyMigrationTest`, `LocalProfileSchemaPersistenceTest`.

조건부 자동 되돌리기, 전체 보호 해제와 원천값 채택 API는 아직 없다.

## 공고 전체 JSON 선택 갱신

- GET `/api/admin/announcements/{id}/json-corrections`: 공고 상세·공급 작업실·일반 일정과 전체 `snapshotToken`을 반환한다.
- POST `/preview`: `{snapshotToken, document, fields, schedules, attachments, correction}`. `document`는 신규 등록과 같은 `admin-announcement-import/v1`이다. `fields`에는 적용할 기본정보 필드명과 접수처를 바꿀 때 `receptionPlaces`(문서의 목록으로 통째로 교체)를, 일정·첨부에는 `{inputIndex, existingId}`를 넣는다. `existingId: null`은 추가이며 기존 ID는 현재 공고 소속이어야 한다. `correction`은 위 [공급정보 보정](#보정-요청과-미리보기) 요청이다.
- POST: `{request, confirmationToken}`. 관리자 인증·CSRF·수집 소유권과 공고 잠금 아래 동일 미리보기를 재검증하고 모든 변경과 `CORRECT_FROM_PDF` 이력을 한 트랜잭션으로 저장한다. 같은 트랜잭션에서 이 공고의 미해결 `myhome_announcement_mapping_failures`·`lh_announcement_enrichment_failures`를 해결로 바꾸고 `last_resolved_by`에 보정한 관리자를 남긴다(`V20261026_01`). 정제가 해결한 실패는 이 값이 비어 있다. 원천 비교 스냅샷은 저장 전·후 한 번씩만 계산한다.
- 선택적 `housingTypeValues: [{rowKey, name, supplyArea, householdCount, basicDeposit, basicMonthlyRent}]`는 공고문에서 읽은 주택타입 값이다. `rowKey`는 `correction.sheet`의 주택형 키이고, 그 주택형이 고른 단지 주택타입(이 저장에서 새로 만드는 주택타입 포함)에 반영한다. 이름이 같은 단지의 다른 주택타입 이름과 같으면 이름만 반영하지 않고 `housingTypeWarnings`로 알린다. 같은 주택타입을 고른 주택형들이 한 필드에 서로 다른 값을 주면 그 필드는 지금 값을 유지하고 경고로 남긴다. 관리자가 직접 고친 필드와 더 최근 공고의 값은 덮지 않는다. 보증금과 월 임대료는 함께 보내야 한다.
- 바꿀 항목이 하나도 없으면(공고문과 지금 값이 같은 요청) 거절하지 않고 미리보기 응답 `unchanged: true`를 준다. 이때 적용은 값을 쓰지 않고 공고문으로 확인한 것만 남긴다: 공급정보 주인을 공고문으로 바꾸고 재확인 표시를 지우며, 남은 정제·보강 실패를 해결로 바꾸고 `CONFIRM_FROM_PDF` 이력을 한 건 남긴다. 공급 보정 작업 번호는 기록하지 않는다. 관리자 공급정보 편집(`/supply-corrections`)은 지금처럼 바뀐 공급정보가 없으면 거절한다.
- 여러 칸이 함께 쓰는 모집·예비 수는 `correction.sheet.quotas`의 멤버가 여럿인 묶음 하나로 보낸다. 공개 상세의 공급 대상은 이를 `sharedSupplyHouseholdCount`·`sharedWaitlistCount: {count, targets}`(함께 쓰는 수와 대상 이름)로, 여러 주택형이 함께 쓰는 모집수는 주택형의 `sharedRecruitment {id, householdCount}`(`id`는 수 묶음 ID)로 보인다.
- 이 경로는 관리자 화면의 PDF 보정만 사용한다. 공급표·기본정보·접수처·일정 변경을 이력 한 건에 남기며 별도의 `CORRECT_SUPPLY` 이력을 만들지 않는다. 공급 보정의 작업 번호는 이 이력에 연결해 같은 작업 번호의 재적용을 막는다. 이전에 저장된 `UPDATE_FROM_JSON` 이력과 함께 남은 `CORRECT_SUPPLY` 이력은 그대로 둔다.
- 미확인·null 기본값과 확인되지 않은 일정·첨부는 선택 적용을 거절한다. 선택하지 않은 값과 하위 항목은 유지하며 생략으로 삭제하지 않는다. 기존 ID·원천 식별자·확인된 접수 일정은 유지한다. 공개된 공고에는 신규 알림을 만들지 않는다.
- 미확정 공고(`releaseStatus=UNCONFIRMED`, SH·GH 원천 공고)는 이 경로에서 관리자가 `publish: true`(선택, 기본 false)를 보낼 때만 공개한다. 주택형이 미리 단지에 연결돼 있어도 요청하지 않으면 공개하지 않는다. 요청하면 미리보기가 적용 뒤 공급표에 삭제되지 않은 단지에 연결된 주택형이 있는지 확인해 없으면 400으로 거절하고, 응답 `publishes`와 확인 토큰에 공개 여부를 담는다. 다른 변경이 없어도 공개만 적용할 수 있다. 적용은 같은 트랜잭션에서 단지 연결을 다시 확인한 뒤 공개하고 신규 공고 알림을 한 번 쌓으며, 이미 공개된 공고는 다시 쌓지 않는다. 관리자 공고 수정(`PUT /api/admin/announcements/{id}`)은 공개 상태를 바꾸지 않는다.
- 전체 스냅샷과 미리보기 토큰에 공급 보정의 후보 검증 토큰까지 포함한다. 동시 변경·이미 적용한 요청은 409다. 저장 응답이 불분명하면 최신 작업실을 조회한 뒤 다시 비교하며 자동 재적용하지 않는다.
- 일반 일정·첨부의 `admin_modified`는 `V20261019_09`에서 추가한다. 관리자 보정 행은 이후 원천값 덮어쓰기와 오래된 원천 행 정리에서 보호한다.
- 입력 형식과 운영 절차: [공고 JSON 가져오기](announcement-import.md). 주요 검증: `AnnouncementJsonCorrectionIntegrationTest`, `AnnouncementJsonCorrectionRollbackIntegrationTest`, `AnnouncementChildProtectionMigrationTest`, `VerifiedAnnouncementEnrichmentIntegrationTest`.

PDF 보정의 미확정 공고는 접수 시작일·접수 종료일·당첨자 발표일과 공급·모집 유형을 함께 채우고 주택형을 단지에 연결해야 저장된다. 날짜는 `접수처·기본정보`, 유형은 `공개 전 확인`에서 정한다. 관리자 화면은 미확정 공고의 보정에 항상 공개 요청(`publish: true`)을 함께 보내므로, 보정을 적용하면 공고가 곧바로 공개되고 신규 공고 알림이 쌓이며 이어서 만드는 신청자격도 공개된다. 미리보기 뒤 유형을 바꾸면 미리보기를 다시 받아야 한다.

### PDF 보정 저장

JSON 전체 갱신에서도 이 공급 보정과 기본정보·일정 변경은 함께 성공하거나 롤백한다.
PDF 보정 저장은 범위 검토 완료를 만들지 않는다. 같은 트랜잭션에서 이 공고에 남은 마이홈 정제·LH 보강 실패를 저장한 관리자의 해결로 바꾸고, 공급정보 주인을 공고문으로 바꾼다.
LH·GH 공고 정제는 공고문 소유 공고의 공급표를 다시 만들지 않으므로 관리자가 저장한 단지·주택타입 연결이 그대로 남는다.

## 단지 기본정보 비교와 수정

일반 단지 상세는 기본정보에서 시작하며 `데이터 검증` 탭에서 현재 등록값과 DB 원천을 대조한다.
기존 확인 범위·검토 기록은 접힌 별도 영역에서 작성한다.

### 조회와 연결

- 기준 경로: `/api/admin/housing-complexes/{id}/verification/comparison`.
- GET은 수정 가능한 현재값, 연결 원천의 저장 행, 비교 토큰을 반환한다.
- 자동 원천은 기존 마이홈 연결 식별자·공급유형으로 조회한다.
- POST `/sources`는 `snapshotToken`, `category`, `sourceKey`, `evidenceNote`로
  비교 근거를 직접 연결한다. 마이홈 단지·LH 임대 단지만 허용한다.
- DELETE `/sources`는 같은 형식과 해제 이유로 직접 연결만 해제한다.
- 직접 연결은 단지별 최대 20개이며 자동 정제용 연결·단지 통합을 변경하지 않는다.
- 재수집으로 연결 키가 없어져도 연결 기록을 유지하고 원천 없음으로 표시한다.
- 같은 키의 여러 원천 행은 모두 보존한다. 이름으로 자동 연결하거나 세대수를 합산하지 않는다.
- LH 임대 카탈로그 키는 지역·공급유형·단지명·전용면적과 동일 식별정보 안의 응답 순번으로
  구성한다. 재수집의 행 ID·전체 응답 순서·금액·세대수 변경은 키를 바꾸지 않는다.
  동일 식별정보가 중복되면 각 행을 별도로 유지하며 중복 안의 순서 변경은 재검토 대상이 될 수 있다.

### 선택 수정

- PUT은 `snapshotToken`, `current`(기존 단지 수정 요청 형식),
  `evidenceNote`, `addressConfirmed`를 받는다.
- 브라우저는 현재값을 초안으로 유지하며 선택한 필드만 바꾼다. 기관·유형·날짜·설치 여부는
  확인 가능한 값만 변환하고, 미제공·알 수 없는 값을 0이나 임의 코드로 채우지 않는다.
- 주소가 바뀌면 주소·PNU·지역 식별정보·좌표의 동시 확인을 요구한다.
- 관리자 권한·CSRF·수집 실행 소유권·단지 행 잠금으로 쓰기를 보호한다.
- 현재값·단지 버전·원천 값·연결이 조회 당시와 다르면 409로 거부한다.
- 연결 시각은 PostgreSQL과 같은 마이크로초 단위로 고정해 저장 직후 토큰이 달라지지 않는다.
- 저장하면 관리자 수정 보호를 적용하고 변경 전후와 당시 원천 전체·메모·확인자를
  `admin_data_changes`에 기록한다. 재수집·원천 삭제 후에도 당시 근거는 보존된다.
- 값 수정은 확인 완료 기록을 만들지 않는다. 기존 검토의 확인값이 바뀌면 재검토 상태가 된다.

### 스키마와 호환성

- `V20261015_04`는 `complex_comparison_links`만 추가한다.
- `V20261019_01`은 현재 LH 카탈로그 행과 대조해 기존 숫자 키의 단지·주택형 비교 연결과
  원천 확인 기록을 새 키로 이관한다. 원문 근거·작성자·감사 이력은 유지한다.
  이미 소실된 행은 추정 연결하지 않으며, 배포 후 카탈로그 재수집 전에 이관을 완료한다.
- 기존 단지·수집 원천·자동 정제 연결은 변경하지 않는다.
- 연결된 원천 행에 외래키를 걸지 않아 원천 재수집·교체를 막지 않는다.
- 기존 수정 API와 검토 API는 유지한다. 새 API는 PDF 근거 입력을 받지 않는다.
- 이 API는 단지의 기본·시설·주소 비교를 다룬다. 공고·주택형·임대조건 비교는
  [관리자 데이터 검토 흐름](review-api.md)의 별도 API를 사용한다.

검증: `ComplexComparisonIntegrationTest`, `ComplexVerificationIntegrationTest`,
`LocalProfileSchemaPersistenceTest`와 프론트엔드 관리자 비교·검토 행동 테스트.

## 단지 검토와 확인 범위

단지 상세의 검증 화면은 수집값 비교와 관리자의 직접 확인을 구분한다.
원천값 일치는 실제 정보 확인 완료를 의미하지 않는다.

### 근거 조회

- `GET /api/admin/housing-complexes/{id}/verification`은 현재값, 연결 마이홈 원천,
  최근 검토 및 최근 20건의 검토 이력을 반환한다.
- 원천은 `myhome_complex_links`의 식별자와 공급유형으로 연결한다. 연결이 없으면
  단지 자체의 정확한 마이홈 식별자만 사용한다. 이름·주소의 유사 검색으로 추정하지 않는다.
- 통합된 단지는 각 원천값을 보존하며 세대수를 합산하거나 대표값으로 바꾸지 않는다.
- 원천에 좌표가 없으므로 저장 좌표와 주소를 지도에서 직접 확인한다.

### 검토 기록

- `POST /api/admin/housing-complexes/{id}/verification/reviews`에 `version`,
  `reviewId`(첫 검토는 0), `snapshotToken`, `fields`, `outcome`,
  `evidenceUrl`(선택), `evidenceNote`(필수)를 전달한다.
- 확인 범위는 NAME, ADDRESS(주소·PNU·지역 식별정보), LOCATION, AGENCY,
  RENTAL_TYPE, HOUSEHOLD_COUNT다. 하나 이상 선택하며 중복 선택을 거부한다.
- 결과는 VERIFIED 또는 ON_HOLD이며 선택한 항목의 당시 값과 확인자·시각을
  수정 불가능한 새 검토 기록으로 보존한다. 단지의 관리자 수정 보호 상태는 변경하지 않는다.
- 관리자 권한과 CSRF를 요구한다. 파이프라인 소유권과 단지 행 잠금으로 쓰기를 보호하며,
  조회 후 현재값·원천·최근 검토가 달라졌으면 409로 다시 조회하도록 안내한다.
- 휴지통 단지는 이전 검토를 조회할 수 있지만 새 검토는 저장할 수 없다.

### 상태와 목록 필터

- 검토 없음은 UNREVIEWED, 선택 항목이 당시 값과 같으면 최근 검토 결과,
  선택 항목이 달라졌으면 STALE다. 확인하지 않은 항목의 변경은 영향을 주지 않는다.
- 상태는 조회 시 현재값과 JSONB 검토 스냅샷을 대조한다. 관리자 수정·자동 정제·위치 보강·
  단지 통합 등 어느 경로에서 변경되어도 별도 초기화 코드 없이 적용된다.
- 검토 상태는 등록값에 대한 확인 범위다. 새 수집 원천과의 차이는 비교 화면과
  기존 `sourceReviewRequired`로 별도 표시한다.
- 목록의 선택적 `verification=UNREVIEWED|VERIFIED|ON_HOLD|STALE` 필터와
  `complex.verificationStatus`, `complex.reviewedFieldCount`로 같은 상태를 제공한다.
- Flyway `V20261009_01`은 검토 이력 테이블과 단지별 최신 기록 인덱스를 추가한다.
  기존 단지 데이터는 변경하지 않는다.

검증: `ComplexVerificationIntegrationTest`, `ComplexVerificationMigrationTest`와
`AdminManagementIntegrationTest`.
