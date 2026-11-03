# 관리자 비교·검토 API

관리자는 `실패·검토 항목`이나 단지·공고 목록에서 대상을 찾는다. 단지는 상세의 `데이터 검증` 탭, 공고는 `출처·수정 이력`의 수집 원천 비교에서 비교·수정한다.
값 수정, 등록값 검토 기록, 재수집 대비 원천 확인은 각각 다른 기록이다.

변경·검토를 수행한 관리자는 인증 정보에서 결정하고 처리 시각과 함께 이력에 남긴다.

## 공고·주택형 비교

- 기준: `/api/admin/quality/{COMPLEX|ANNOUNCEMENT}/{id}/comparison`.
- GET의 `target`: `basic`, `type:{id}`, `row:{id}`, `target:{id}`.
- GET은 현재 상세·원천 행·비교 토큰을 반환한다. 공고는 정확한 공고 식별자·LH PAN ID로 원천을 찾는다.
- 주택형은 단지 원천을 함께 표시하므로 해당 주택형인지 직접 확인한다. 이름 유사도로 자동 연결하지 않는다.
- POST/DELETE `/sources`: 비교용 원천 연결·해제. 자동 정제 연결을 변경하지 않는다.
- PUT은 선택한 필드만 `values`로 받으며 기존 수정 서비스를 통해 검증·보호·이력을 적용한다.
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
과거 원본·초안·담당 업무 테이블을 만든 마이그레이션과 기존 데이터는 보존한다. 기능 제거를 위해 공유 DB 데이터를 삭제하지 않는다.
비교 연결·확인 스냅샷은 `V20261016_01`을 계속 사용한다.

## 공급정보 일괄 보정과 범위 검토

- `/api/admin/announcements/{id}/supply-corrections`: GET 현재 공급행·대상·원천·소실 경고와 snapshotToken.
- GET의 `rowSources`는 공급행이 기록한 원천행 `{rowKey, category, sourceKey}`이며 한 행에 여러 개일 수 있다. 응답 전용이며 보정 요청에는 포함하지 않는다.
  - 마이홈(`MYHOME_ANNOUNCEMENT`)은 공급행이 기록한 원천 키를 그대로 준다.
  - LH(`LH_ANNOUNCEMENT_SUPPLY`)는 공급행이 `LH:{panId}:SUPPLY:{순번}`만 기록하므로, 원천 키 `{panId}:{요청 해시}:{순번}` 중 공고 번호와 순번이 같은 것이 하나일 때 그 키를 준다. 없으면 기록값을 그대로 줘서 찾을 수 없음을 알리고, 여러 개면 추정하지 않고 생략한다.
  - 관리자가 LH 공급 원본 한 행을 주택형별로 나눈 행(`LH_SPLIT`)은 나눈 LH 행 식별자 `LH:{panId}:SUPPLY:{순번}`을 준다. 같은 키를 가진 행들은 중복이 아니라 의도한 분리 형제다.
  - 원천 식별자가 없는 행(직접 추가 등)은 목록에 없다.
- GET의 `lhSupplyRows`는 소유와 관계없이 LH 공고 ID(`lh_pan_id`)로 LH 공고 정제가 읽는 최신 LH 공급 원본 행 전체 `{rowIdentifier, complexLabel, typeName, exclusiveArea, householdCount, attachedRowKey, splitRowKeys}`다. `householdCount`는 LH 이번 공급 수(정수가 아니면 null), `attachedRowKey`는 그 LH 행 식별자를 기록한 공급행(통합행), `splitRowKeys`는 그 LH 행에서 나눈 공급행이다. LH 공고 ID가 없거나 최신 공고목록에 그 공고가 하나로 있지 않으면 빈 목록이다. 이 목록의 행 식별자·붙은 행·분리행은 snapshotToken에 포함된다.
- GET의 `unattachedLhSupplyRows`는 LH 원천 소유 공고의 `lhSupplyRows` 중 어떤 공급행도 LH 행 식별자나 분리 원천으로 가리키지 않는 행이다. 정제 뒤 원천이 늘었으면 여기 남는다. 공고문 소유 공고는 정제가 공급행을 만들지 않으므로 빈 목록이다. 이 목록이 있어도 새 공급행 추가를 막지 않는다. 미리보기는 이 저장 뒤에도 남는 행을 `unattachedLhSupplyRows`와 경고로 돌려준다. 저장하면 공고문 소유가 되어 정제가 그 행을 공급행으로 만들지 않기 때문이다.
- 요청 행의 선택적 `saveMode`와 `lhSourceRowIdentifier`(`lhSupplyRows[].rowIdentifier` 값)로 LH 공급 원본 행에 놓는 방식을 정한다. `saveMode`를 생략하면 `id`가 있는 행은 `UPDATE`, 없는 행은 `ADD`이므로 기존 요청과 같다.
  - `UPDATE`: 기존 행 수정. `ADD`: LH 원천과 무관한 새 행. 두 방식은 `lhSourceRowIdentifier`를 받지 않는다.
  - `ATTACH`: 아직 공급행이 되지 않은 LH 행에 새 행을 붙여 그 행에 LH 행 식별자를 기록한다. 이미 공급행이나 분리행이 있는 LH 행, 한 요청에서 같은 LH 행에 두 번 붙이기, 기존 행은 400이다.
  - `SPLIT`: LH 한 행(예: 모집 240, 면적 미확인)을 주택형별 행으로 나눈다. 그 LH 행에 붙은 통합행은 ID와 LH 행 대응을 유지한 채 첫 분리행으로 넣어야 하고, 나머지는 `id` 없는 새 행이다. 같은 `lhSourceRowIdentifier`의 행이 둘 이상이어야 하며 모든 분리행에 분리 원천(`supply_rows.lh_split_source_identifier`, `V20261103_02`)을 기록한다. 합계는 분리행 합으로만 센다. 통합행을 빼거나, 다른 LH 행에 붙은 기존 행·LH 원천 없이 추가한 행·공통 모집 묶음 행을 넣거나, 최신 LH 공급 원본에 없는 행을 지정하면 400이다. 처음 나누는 요청에서 통합행이 저장된 모집수를 그대로 두고 다른 분리행에 모집수가 있으면 그 합계를 두 번 세므로 400이다(첫 분리행 모집수를 그 주택형 값으로 바꿔야 한다).
  - 미리보기의 `splits: [{lhSourceRowIdentifier, rowKeys, householdCount, lhHouseholdCount}]`는 저장 뒤 분리 묶음이다. `householdCount`는 제외하지 않은 분리행 합계(미확정이 있으면 null)다. 이번에 나눈 합계가 LH 공급 수와 다를 때와 분리 형제행을 지우거나 제외해 합계가 바뀔 때 경고한다.
- POST `/preview`: 전체 행·하위 조건 변경안과 UUID 작업 번호, 근거·이유를 검증하여 변경 전후와 confirmationToken 반환. 저장하지 않는다.
- 행의 `typeName`은 이 공고의 주택형 표기다. 조회는 공개 화면에 보이는 이름을 주고, 저장은 공고 표기로만 한다. 기존 행의 `complexName`은 원천 단지명이라 요청 값과 관계없이 유지한다. 원천이 없는 새 행만 입력값을 원천 이름으로 남긴다.
- 행의 `linkedHousingTypes`는 대표 주택형(`housingTypeId` 또는 `newHousingType`)과 함께 모집하는 주택형이며 대표는 빼고 적는다. LH 한 행이 여러 전용면적(세부 타입 26A·B·C)을 모집하면 공급행 하나가 그 주택형을 모두 가리킨다(`supply_row_housing_types`). GET은 현재 연결을 `[{housingTypeId}]`로 주고 snapshotToken에 포함하므로, 화면을 연 뒤 연결이 바뀌면 미리보기는 409(`ADMIN_DATA_STALE`)다.
  - 요청 항목은 같은 단지의 기존 주택형 `{housingTypeId}` 또는 이 저장에서 만들 `{newHousingType: {name, exclusiveArea, supplyArea}}` 중 하나다. 저장 후 연결은 대표와 이 목록이고 대표는 항상 들어간다.
  - 생략(null)하면 대표가 그대로일 때 저장된 연결을 유지하고, 대표가 바뀌면 대표만 남긴다. 이 필드가 없는 기존 요청과 같은 결과다. 빈 목록은 대표만 남긴다. 연결만 바꿔도 바뀐 행이다.
  - 한 행에는 전용면적마다 주택형 하나만 연결한다. 대표와 같은 주택형·면적, 다른 단지의 주택형, 단지에 같은 면적 주택형이 있거나 휴지통에 있는 새 주택형은 400이다. 대표 주택형이 없는 행과 공통 모집 묶음 행(`groupId`가 있거나 `newGroups`에 든 행)은 빈 목록만 받는다.
  - 한 저장에서 같은 단지·전용면적의 새 주택형은 대표든 함께 모집하는 주택형이든 행이 달라도 하나를 만들어 함께 쓴다. 미리보기 확인 토큰은 연결한 기존 주택형의 값도 포함한다.
  - 공고 단지를 다른 단지로 다시 연결(`POST /announcement-complexes/{id}/link`)하면 그 공고 단지의 공급행은 대표만 새 단지의 같은 면적 주택형으로 옮기고 함께 모집하던 주택형은 빠진다. 빠진 연결은 이력의 변경 전 `housingTypeIds`에 남는다.
- POST: `{correction, confirmationToken}`. 공고 잠금과 수집 소유권 아래 전체 보정·새 주택형·보호·이력을 한 트랜잭션으로 저장한다.
- 기존 행·조건 ID를 생략해 삭제할 수 없다. 제외·복원으로 이력을 유지하고 일정의 단지 참조를 검증한다.
- 예외로 원천 없이 관리자가 추가한 행(`MANUAL:` 식별자, 공통 모집 묶음 밖)은 지울 수 있다. GET의 `deletableRowKeys`가 그 행 키이고, 요청의 `deletedRowKeys`에 키를 넣고 `rows`에서 빼면 행과 하위 조건을 지운다. 원천행은 다음 정제가 다시 만들므로 지울 수 없고 400으로 거절한다. 지운 행은 변경 이력의 변경 전 값과 `deletedRowKeys`로 남는다.
- 동일 작업 UUID·동일 요청은 기존 changeId와 현재 workspace를 반환한다. 같은 UUID의 다른 요청은 409다.
- 원천·현재값·후보의 변경은 오래된 미리보기를 거절한다. 현재 미연결·미확정 상태는 경고이며 검토 완료로 변경하지 않는다.
- 409 응답의 `code`로 대응을 나눈다. 조회 이후 저장값·원천이 바뀌어 snapshotToken이나 confirmationToken이 맞지 않으면 `ADMIN_DATA_STALE`이며, 최신값을 다시 조회해 입력을 맞추면 저장할 수 있다. 일정이 참조하는 단지 제외, 휴지통의 단지 연결, 같은 UUID의 다른 요청 같은 규칙 위반은 `ADMIN_DATA_CONFLICT`이며 다시 조회해도 풀리지 않으므로 `message`를 그대로 보여 준다.
- 금액은 원 단위 18자리 이하 정수 문자열, 수량은 음수가 아닌 정수 또는 null이다. 합계 검증은 명시적으로 동일 범위를 확인한 행에만 적용한다.
- 기존 공통 모집 묶음은 바꿀 수 없다(원천별 공급행 보정은 지원 종료). 공급 보정의 선택적 `newGroups`로 기존 단일 원천행의 새 묶음을 만들 수 있다. 묶음 합계를 개별 행에 복제하지 않는다. 묶음은 마이홈 원천이 키라서 마이홈 공고 원천이 없는 공고(LH 목록으로만 만든 공고)는 `newGroups`를 400으로 거절한다. 그 공고에서 여러 주택형이 모집수를 함께 쓰면 공급행 하나에 모집수를 두고 `linkedHousingTypes`로 주택형을 연결한다.
- 새 주택형·행·조건과 수정·제외·순서는 관리자 보호를 적용한다. 공개 조회·검색·현재 모집조건에서 제외 상태를 반영한다. 신규 알림을 생성하지 않는다.
- POST `/reviews`: snapshotToken, `row:{id}` 또는 `target:{id}`, 확인 필드·선택 원천 필드·근거·VERIFIED/ON_HOLD를 기록한다.
- GET `/reviews`: 최근 100개 기록, 선택 범위의 확인값·현재값·원천 차이·현재 판단을 반환한다. 확인 범위 밖의 변경과 수집 메타데이터 변경은 검토 무효 사유가 아니다.
  - `connection`은 공급행이 가리키는 주택형 전체(대표와 함께 모집하는 주택형) id를 오름차순 쉼표 문자열 `typeIds`로 포함하므로, 연결을 검토한 뒤 함께 모집하는 주택형이 바뀌면 재검토 대상이다. 이 값이 없던 이전 검토 기록은 연결 변경만으로 재검토가 되지 않는다.
- 공통 주택형의 GET `/api/admin/housing-complexes/{id}/housing-types/{typeId}/impact`는 영향 공고·행과 토큰을 반환한다. 공급행이 하나라도 연결된 주택형은 전용면적 수정을 409로 거절한다. 공급행은 (단지, 전용면적)으로 주택형을 찾기 때문이다. 이름·세대수는 영향을 확인한 뒤 수정할 수 있다.
- `V20261019_05`는 의견 처리, `_06`은 공급 보정·제외·공고별 면적, `_07`은 범위 검토, `_08`은 마이홈 원천 대응을 저장한다. `_08`은 업그레이드 당시 LH 확장행의 원천을 고정해 이후 표시순서와 원천 대응을 분리한다.
- 주요 검증: `SupplyCorrectionIntegrationTest`, `LhSupplySplitCorrectionIntegrationTest`, `AnnouncementSupplyMatchingIntegrationTest`, `LocalProfileSchemaPersistenceTest`.

조건부 자동 되돌리기, 전체 보호 해제와 원천값 채택, 일반적인 공급행 합치기·조건 이동 API는 아직 없다.

## 공고 전체 JSON 선택 갱신

- GET `/api/admin/announcements/{id}/json-corrections`: 공고 상세·공급 작업실·일반 일정과 전체 `snapshotToken`을 반환한다.
- POST `/preview`: `{snapshotToken, document, fields, schedules, attachments, correction}`. `document`는 신규 등록과 같은 `admin-announcement-import/v1`이다. `fields`에는 적용할 기본정보·접수처 필드명을, 일정·첨부에는 `{inputIndex, existingId}`를 넣는다. `existingId: null`은 추가이며 기존 ID는 현재 공고 소속이어야 한다. `correction`은 기존 공급 보정 요청 계약이다.
- POST: `{request, confirmationToken}`. 관리자 인증·CSRF·수집 소유권과 공고 잠금 아래 동일 미리보기를 재검증하고 모든 변경과 `CORRECT_FROM_PDF` 이력을 한 트랜잭션으로 저장한다. 같은 트랜잭션에서 이 공고의 미해결 `myhome_announcement_mapping_failures`·`lh_announcement_enrichment_failures`를 해결로 바꾸고 `last_resolved_by`에 보정한 관리자를 남긴다(`V20261026_01`). 정제가 해결한 실패는 이 값이 비어 있다. 원천 비교 스냅샷은 저장 전·후 한 번씩만 계산한다.
- 선택적 `housingTypeValues: [{rowKey, name, supplyArea, householdCount, basicDeposit, basicMonthlyRent, linkedHousingTypeId, linkedNewHousingTypeArea}]`는 공고문에서 읽은 주택형 값이다. 연결 주소가 없으면 그 행의 대표 주택형에 반영한다. `linkedHousingTypeId`는 그 행이 함께 모집하는 기존 주택형, `linkedNewHousingTypeArea`는 함께 모집하는 새 주택형의 전용면적이며 둘 중 하나만 준다. 같은 행의 같은 주택형에 두 번 보내거나 그 행이 함께 모집하지 않는 주택형을 가리키면 400이다. 함께 모집하는 주택형을 생략한 공급 행은 저장된 연결을 유지하므로 그것만으로 공급행 변경이 되지 않는다.
- 이 경로는 관리자 화면의 PDF 보정만 사용한다. 공급행·기본정보·접수처·일정 변경을 이력 한 건에 남기며 별도의 `CORRECT_SUPPLY` 이력을 만들지 않는다. 공급 보정의 작업 번호는 이 이력에 연결해 같은 작업 번호의 재적용을 막는다. 이전에 저장된 `UPDATE_FROM_JSON` 이력과 함께 남은 `CORRECT_SUPPLY` 이력은 그대로 둔다.
- 미확인·null 기본값과 확인되지 않은 일정·첨부는 선택 적용을 거절한다. 선택하지 않은 값과 하위 항목은 유지하며 생략으로 삭제하지 않는다. 기존 ID·원천 식별자·확인된 접수 일정은 유지한다. 신규 알림은 만들지 않는다.
- 전체 스냅샷과 미리보기 토큰에 공급 보정의 후보 검증 토큰까지 포함한다. 동시 변경·이미 적용한 요청은 409다. 저장 응답이 불분명하면 최신 작업실을 조회한 뒤 다시 비교하며 자동 재적용하지 않는다.
- 일반 일정·첨부의 `admin_modified`는 `V20261019_09`에서 추가한다. 관리자 보정 행은 이후 원천값 덮어쓰기와 오래된 원천 행 정리에서 보호한다.
- 입력 형식과 운영 절차: [공고 JSON 가져오기](announcement-import.md). 주요 검증: `AnnouncementJsonCorrectionIntegrationTest`, `AnnouncementJsonCorrectionRollbackIntegrationTest`, `AnnouncementChildProtectionMigrationTest`, `VerifiedAnnouncementEnrichmentIntegrationTest`.

### PDF 보정의 새 공통 모집 묶음

공급 보정 요청은 선택적 `newGroups: [{sourceRowId, rowKeys, sharedHouseholdCount}]`를 받는다. 생략하면 빈 목록이며 기존 요청과 호환된다.
`sourceRowId`는 해당 공고의 유효한 마이홈 원천에 단독으로 대응하는 기존 공급행이다. 기존 묶음이나 여러 행이 이미 대응하는 원천은 거절한다.
마이홈 원천 한 행이 LH 공급정보로 여러 행이 된 공고는 그 LH 행이 묶음의 원천이다. 묶음 키는 그 행의 원천행 식별자이며, 원천 지문에 LH 행의 표기·면적·이번 공급 수를 더한다.
`rowKeys`는 기존 행 하나와 새 행을 포함한 2~50개 작업 키다. 모든 행은 같은 단지·PNU·공급구분이며 서로 다른 기존 주택형에 연결되어야 한다.
행 모집수는 null이고 기존 행·대상 ID는 유지한다. 다른 기존 행의 조건을 이동하거나 새 공통 주택형을 생성하는 요청은 허용하지 않는다.

미리보기는 `newGroups`를 반환하고 원천·후보 주택형의 지문과 묶음을 확인 토큰에 포함한다. 공유 모집수는 합계에 한 번만 더한다.
적용 시 같은 트랜잭션에서 행·조건, 공통 모집 묶음, 관리자 보정 보호, 원천 대응, 감사 기록을 저장한다.
JSON 전체 갱신에서도 이 공급 보정과 기본정보·일정 변경은 함께 성공하거나 롤백한다. 새 DB 스키마는 추가하지 않는다.
PDF 보정 저장은 범위 검토 완료를 만들지 않는다. 같은 트랜잭션에서 이 공고에 남은 마이홈 정제·LH 보강 실패를 저장한 관리자의 해결로 바꾸고, 공급정보 주인을 공고문으로 바꾼다.
LH 공고 정제는 공고문 소유 공고의 공급행을 다시 만들지 않으므로 관리자가 저장한 단지·주택형 연결이 그대로 남는다. 마이홈 정제용 저장 매칭도 함께 기록하지만 지원을 종료한 마이홈 정제만 읽는다.

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
