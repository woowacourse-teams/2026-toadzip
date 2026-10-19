# 관리자 데이터 검토 흐름

관리자는 `검토할 데이터`에서 필요한 대상을 찾고 상세의 `데이터 검증`에서 비교·수정한다.
값 수정, 등록값 검토 기록, 재수집 대비 원천 확인은 각각 다른 기록이다.

변경·검토를 수행한 관리자는 인증 정보에서 결정하고 처리 시각과 함께 이력에 남긴다.

## 검토 목록

- `GET /api/admin/quality/queue`: `resource`, `reason`, `keyword`, `sourceIdentifier`, `announcementId`, `complexId`, `provider`, `applicationStatus`, `page`, `size` 필터.
- 원천 식별자는 정확히 일치하는 마이홈 식별자 또는 LH PAN ID다. 단지 ID는 해당 단지 및 제외되지 않은 공급행으로 연결된 공고를 찾는다. 모집상태는 확인한 일정을 우선하고 취소공고를 구분한다.
- 전체 DB에서 필터링·정렬한 뒤 페이지를 반환한다. 휴지통은 제외한다.
- 사유: 정보 누락, 수집값 불일치, 확인값 변경, 미검토, 보류, 원천 변경·소실.
- `sourceChanges`는 원천 변경·소실의 출처다. `COMPARISON_CHECK`는 데이터 검증에서 확인한 원천의 변경·소실, `SUPPLY_SOURCE`는 공급행이 참조하는 원천의 소실·비활성이다. 해당이 없으면 빈 목록이다.
- 0·false는 누락이 아니다. 공고의 미확인 접수 일정과 단지·주택형 연결 누락도 표시한다.
- 목록은 정확성 점수가 아니다. 누락 필드·선택 검토 범위와 원천 적용 범위를 사람이 확인한다.

## 공고·주택형 비교

- 기준: `/api/admin/quality/{COMPLEX|ANNOUNCEMENT}/{id}/comparison`.
- GET의 `target`: `basic`, `type:{id}`, `row:{id}`, `target:{id}`.
- GET은 현재 상세·원천 행·비교 토큰을 반환한다. 공고는 정확한 공고 식별자·LH PAN ID로 원천을 찾는다.
- 주택형은 단지 원천을 함께 표시하므로 해당 주택형인지 직접 확인한다. 이름 유사도로 자동 연결하지 않는다.
- POST/DELETE `/sources`: 비교용 원천 연결·해제. 자동 정제 연결을 변경하지 않는다.
- PUT은 선택한 필드만 `values`로 받으며 기존 수정 서비스를 통해 검증·보호·이력을 적용한다.
- 단지 기본정보는 기존 [단지 원천 비교](complex-comparison.md)를 사용한다.
- 조회 후 현재 상세·원천·연결이 달라지면 409로 거부한다. 관리자·CSRF·파이프라인 소유권·행 잠금 적용.
- 금액은 단위·최소/전체 범위가 불명확한 원천 문자열을 자동 환산하지 않는다. 원 단위 정수 문자열로 직접 확인한다.
- 변경 전후와 당시 원천 행 전체·근거·수정 대상을 이력에 보존한다.

## 재수집 후 재검토

- POST `/checks`: 수정 없이 현재 원천 확인 근거를 기록한다. 단지 기본 비교는 `/checks/legacy`를 사용한다.
- 확인한 원천의 값이 바뀌거나 사라지면 다음 목록 조회부터 `SOURCE_CHANGED`로 표시한다.
- 단순 수집 시각·저장 행 ID·수집 실행 식별자 변경은 데이터 변경으로 분류하지 않는다.
- 확인 기준이 없는 과거 원천 변경을 소급해 판정하지 않는다. 기존 수집값 불일치·미검토 사유는 별도로 유지한다.
- 확인 기록은 새 행으로 추가하며 이전 근거를 덮어쓰지 않는다. 값 수정이나 전체 검증 완료를 의미하지 않는다.

## 공고문 조회와 기존 스키마

공고 관리 상세는 기존 `announcement_attachments`의 파일 식별자·이름·종류·URL을 표시순서로 반환한다.
첨부파일 본문은 기존 공개 공고 첨부 미리보기·다운로드 API를 사용한다. 휴지통 공고는 공식 원문 링크로 안내한다.
PDF 업로드·보관과 공유 초안 API는 제공하지 않는다. 로컬 폴더 연동이나 외부 AI 호출도 없다.
과거 원본·초안·담당 업무 테이블을 만든 마이그레이션과 기존 데이터는 보존한다. 기능 제거를 위해 공유 DB 데이터를 삭제하지 않는다.
비교 연결·확인 스냅샷은 `V20261016_01`을 계속 사용한다.

## 공급정보 일괄 보정과 범위 검토

- `/api/admin/announcements/{id}/supply-corrections`: GET 현재 공급행·대상·원천·소실 경고와 snapshotToken.
- POST `/preview`: 전체 행·하위 조건 변경안과 UUID 작업 번호, 근거·이유를 검증하여 변경 전후와 confirmationToken 반환. 저장하지 않는다.
- POST: `{correction, confirmationToken}`. 공고 잠금과 수집 소유권 아래 전체 보정·새 주택형·보호·이력을 한 트랜잭션으로 저장한다.
- 기존 행·조건 ID를 생략해 삭제할 수 없다. 제외·복원으로 이력을 유지하고 일정의 단지 참조를 검증한다.
- 동일 작업 UUID·동일 요청은 기존 changeId와 현재 workspace를 반환한다. 같은 UUID의 다른 요청은 409다.
- 원천·현재값·후보의 변경은 오래된 미리보기를 거절한다. 현재 미연결·미확정 상태는 경고이며 검토 완료로 변경하지 않는다.
- 금액은 원 단위 18자리 이하 정수 문자열, 수량은 음수가 아닌 정수 또는 null이다. 합계 검증은 명시적으로 동일 범위를 확인한 행에만 적용한다.
- 공통 모집 묶음은 기존 원천별 보정 계약을 사용한다. 묶음 합계를 개별 행에 복제하지 않는다.
- 새 주택형·행·조건과 수정·제외·순서는 관리자 보호를 적용한다. 공개 조회·검색·현재 모집조건에서 제외 상태를 반영한다. 신규 알림을 생성하지 않는다.
- POST `/reviews`: snapshotToken, `row:{id}` 또는 `target:{id}`, 확인 필드·선택 원천 필드·근거·VERIFIED/ON_HOLD를 기록한다.
- GET `/reviews`: 최근 100개 기록, 선택 범위의 확인값·현재값·원천 차이·현재 판단을 반환한다. 확인 범위 밖의 변경과 수집 메타데이터 변경은 검토 무효 사유가 아니다.
- 공통 주택형의 GET `/api/admin/housing-complexes/{id}/housing-types/{typeId}/impact`는 영향 공고·행과 토큰을 반환한다. 공통 면적 수정은 검토한 공고의 면적 보호 여부를 검사한다.
- `V20261019_05`는 의견 처리, `_06`은 공급 보정·제외·공고별 면적, `_07`은 범위 검토, `_08`은 마이홈 원천 대응을 저장한다. `_08`은 업그레이드 당시 LH 확장행의 원천을 고정해 이후 표시순서와 원천 대응을 분리한다.
- 주요 검증: `SupplyCorrectionIntegrationTest`, `AnnouncementSupplyMatchingIntegrationTest`, `SupplySourceOwnershipMigrationTest`, `LocalProfileSchemaPersistenceTest`.

조건부 자동 되돌리기, 전체 보호 해제와 원천값 채택, 일반적인 공급행 합치기·조건 이동 API는 아직 없다.

## 공고 전체 JSON 선택 갱신

- GET `/api/admin/announcements/{id}/json-corrections`: 공고 상세·공급 작업실·일반 일정과 전체 `snapshotToken`을 반환한다.
- POST `/preview`: `{snapshotToken, document, fields, schedules, attachments, correction}`. `document`는 신규 등록과 같은 `admin-announcement-import/v1`이다. `fields`에는 적용할 기본정보·접수처 필드명을, 일정·첨부에는 `{inputIndex, existingId}`를 넣는다. `existingId: null`은 추가이며 기존 ID는 현재 공고 소속이어야 한다. `correction`은 기존 공급 보정 요청 계약이다.
- POST: `{request, confirmationToken}`. 관리자 인증·CSRF·수집 소유권과 공고 잠금 아래 동일 미리보기를 재검증하고 모든 변경과 `UPDATE_FROM_JSON` 이력을 한 트랜잭션으로 저장한다.
- 미확인·null 기본값과 확인되지 않은 일정·첨부는 선택 적용을 거절한다. 선택하지 않은 값과 하위 항목은 유지하며 생략으로 삭제하지 않는다. 기존 ID·원천 식별자·확인된 접수 일정은 유지한다. 신규 알림은 만들지 않는다.
- 전체 스냅샷과 미리보기 토큰에 공급 보정의 후보 검증 토큰까지 포함한다. 동시 변경·이미 적용한 요청은 409다. 저장 응답이 불분명하면 최신 작업실을 조회한 뒤 다시 비교하며 자동 재적용하지 않는다.
- 일반 일정·첨부의 `admin_modified`는 `V20261019_09`에서 추가한다. 관리자 보정 행은 이후 원천값 덮어쓰기와 오래된 원천 행 정리에서 보호한다.
- 입력 형식과 운영 절차: [공고 JSON 가져오기](../../docs/ADMIN_ANNOUNCEMENT_IMPORT.md). 주요 검증: `AnnouncementJsonCorrectionIntegrationTest`, `AnnouncementJsonCorrectionRollbackIntegrationTest`, `AnnouncementChildProtectionMigrationTest`, `VerifiedAnnouncementEnrichmentIntegrationTest`.
