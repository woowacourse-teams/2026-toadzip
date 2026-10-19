# 관리자 데이터 검토 흐름

관리자는 `검토할 데이터`에서 필요한 대상을 찾고 상세의 `데이터 검증`에서 비교·수정한다.
값 수정, 등록값 검토 기록, 재수집 대비 원천 확인은 각각 다른 기록이다.

담당자·기한·업무 알림은 [검토 업무](admin-review-tasks.md)를 따른다.

## 검토 목록

- `GET /api/admin/quality/queue`: `resource`, `reason`, `keyword`, `page`, `size` 필터.
- 전체 DB에서 필터링·정렬한 뒤 페이지를 반환한다. 휴지통은 제외한다.
- 사유: 정보 누락, 수집값 불일치, 확인값 변경, 미검토, 보류, 원천 변경·소실.
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

## PDF 원본 보관

- `/api/admin/quality/{resource}/{id}/documents`: POST multipart(`file`, `sourceUrl`), GET 목록(20개 단위).
- GET `/{sha256}`은 관리자에게 원본을 attachment·no-store로 전달한다.
- 25MB 이하 PDF 서명·출처 URL을 검증하고 서버에서 SHA-256을 계산한다. 동일 대상·파일은 중복 저장하지 않는다.
- 원본 바이트·파일명·출처·보관자·시각을 DB에 보존한다. 덮어쓰기·삭제 API는 제공하지 않는다.
- PDF 서명 검사는 문서 내용의 정확성·안전성을 인증하지 않는다. 공식 문서만 보관한다.
- 작업 초안·OCR·에이전트 사용법은 [화면 안내](../../frontend/docs/admin-quality-workflow.md)를 참고한다.

## 스키마·검증

- `V20261016_01`: 비교 연결·확인 스냅샷. `V20261016_02`: 원본 PDF 보관.
- 기존 데이터 자동 수정·자동 등록·외부 AI 호출은 수행하지 않는다.
- `AdminQualityQueueIntegrationTest`, `QualityComparisonIntegrationTest`, `ReviewDocumentIntegrationTest`.

## 공유 검토 초안

- GET/PUT `/api/admin/quality/{resource}/{id}/draft`: `target`별 원본 해시·DB 버전·쪽수·수정안·메모 보관.
- PDF는 같은 대상의 기존 원본 보관함을 사용한다. 수정안은 1MB 이하이며 대상·버전·해시를 검사한다.
- PUT은 `expectedRevision`(신규 0)을 요구한다. 다른 저장이 먼저 완료되면 409이며 덮어쓰지 않는다.
- 관리자·CSRF·소속 대상 검증을 적용하며 작성자·시각과 변경 전후 초안을 이력에 기록한다.
- 과거 DB 버전의 초안도 보관할 수 있다. 복원은 원본 해시 확인·재추출·수정안 재검증을 거치며 확인 체크는 복원하지 않는다.
- `V20261016_03`은 테이블만 추가한다. 기존 등록값은 변경하지 않으며 이전 앱 버전과 공존한다.
- `SharedReviewDraftIntegrationTest`: 공유·revision 충돌·관리자·CSRF·다른 대상 원본 차단.

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
