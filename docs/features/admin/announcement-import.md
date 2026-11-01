# 관리자 공고 JSON 가져오기 정책

## 적용 범위

`admin-announcement-import/v1` JSON은 새 공고 등록 API(`/api/admin/announcement-imports`)와 기존 공고 갱신 API(`json-corrections`)가 받는 형식이다. 관리자 화면에는 이 JSON을 직접 붙여넣는 화면이 없고, PDF 보정 화면이 추출 결과로 v1 문서를 만들어 기존 공고 갱신 API를 호출한다.

검증과 미리보기는 저장하지 않는다. 기존 공고 갱신은 해당 공고 ID를 유지하며 새 공고나 신규 공고 알림을 만들지 않는다. 정정·취소공고를 별도 공고로 등록하고 원공고에 연결하는 기능은 이 계약에 포함하지 않는다.

원공고, 이 정책과 [정상 예시](../../fixtures/announcement-import-v1-valid.json)를 Codex에 제공하고 다음과 같이 요청한다.

```text
원공고에서 admin-announcement-import/v1 JSON 객체만 추출해 주세요.
원문에 없는 값을 추정하거나 내부 DB ID를 만들지 마세요.
확인할 수 없는 값은 null과 unresolvedFields의 경로·사유로 표시하세요.
정정·취소공고라면 JSON을 만들지 말고 알려 주세요.
```

## JSON 작성 규칙

- `schemaVersion`은 `admin-announcement-import/v1`이다. 최상위에는 `source`, `announcement`, `receptionPlace`, `supplyRows`, `schedules`, `attachments`, `unresolvedFields`를 넣고 정의되지 않은 필드는 넣지 않는다. 전체 구조는 [정상 예시](../../fixtures/announcement-import-v1-valid.json)를 따른다.
- `source.originalUrl`은 공식 원공고의 HTTP(S) URL, `source.sourceDocumentId`는 공식 공고번호다. 공고번호가 없으면 `null`을 쓴다. `source.extractedAt`은 추출 시각과 오프셋을 포함한다.
- 원문에서 확인한 값만 적는다. 확인할 수 없는 값은 추정하거나 `ETC`로 대체하지 않고 `null`로 두며, 확인이 필요한 경우 `unresolvedFields`에 JSON 경로와 사유를 적는다. `ETC`는 원문에 기타 범주가 명시된 경우에만 쓴다. 신규 등록은 필수값이 `null`이면 차단한다. 기존 갱신은 선택하지 않은 항목의 기존값을 유지하며, `null`·누락·`unresolvedFields`에 표시된 항목을 적용하도록 선택하면 요청 전체를 거절한다.
- 내부 DB ID는 JSON에 넣지 않는다. 공급행마다 원문 단지명과 19자리 숫자 PNU를 `complexReference`에 넣는다.
- 날짜는 `YYYY-MM-DD`, 연월은 `YYYY-MM`, 일정 일시는 `YYYY-MM-DDTHH:mm:ss` 형식이다. 금액은 원 단위 18자리 이하 정수 또는 정확한 숫자 문자열이며 쉼표·만원·지수 표기를 쓰지 않는다. 세대수는 0 이상 정수다. 공급행·대상·일정·첨부 배열은 원문 순서를 유지한다. 신규 등록은 이 순서로 저장하며, 기존 갱신은 기존 순서를 유지하고 새 항목을 뒤에 추가한다.
- 공급행의 `exclusiveArea`·`supplyArea`는 선택 항목으로 ㎡ 단위 소수 넷째 자리까지 입력한다. 모르면 생략하거나 `null`로 둔다. 기존 공고 갱신 시 해당 공고의 면적만 보정·보호하며 공통 주택형은 바꾸지 않는다.
- 신규 등록의 `supplyRows`는 1개 이상이어야 한다. 기존 갱신은 `[]`로 공급정보를 유지할 수 있다. `schedules`, `attachments`, `unresolvedFields`는 값이 없어도 빈 배열로 넣는다. 접수 종료일과 일정 종료일시는 각각 시작보다 빠를 수 없다.
- 정규화된 JSON은 UTF-8 기준 1,000,000바이트 이하여야 한다. 공급행·미확정값은 각각 최대 200개, 일정·첨부와 공급행별 대상은 각각 최대 100개다.

| 필드 | 허용값 |
|---|---|
| `announcement.rentalType` | `HAPPY_HOUSING`, `NATIONAL_RENTAL`, `PERMANENT_RENTAL`, `PUBLIC_RENTAL_5Y`, `PUBLIC_RENTAL_10Y`, `PUBLIC_RENTAL_50Y`, `INTEGRATED_PUBLIC_RENTAL`, `REDEVELOPMENT_RENTAL`, `LONG_TERM_JEONSE`, `ETC` |
| `announcement.recruitmentType` | `NEW`, `WAITLIST`, `ETC` |
| `announcement.agencyCode` | `LH`, `SH`, `GH`, `ETC` |
| `receptionPlace.method` | `ONLINE`, `VISIT`, `MAIL`, `ETC` |
| `supplyRows[].supplyCategory` | `NEW_SUPPLY`, `RESUPPLY` |
| `schedules[].scheduleType` | `APPLICATION`, `DOCUMENT_SUBMISSION`, `WINNER_ANNOUNCEMENT`, `CONTRACT`, `MOVE_IN`, `ETC` |
| `attachments[].fileType` | `ANNOUNCEMENT`, `REFERENCE`, `CORRECTION`, `CANCELLATION`, `ETC`. 원공고에서는 `ANNOUNCEMENT`, `REFERENCE`를 쓰고 실제 기타 자료에 한해 `ETC`를 쓴다 |

## 검증과 등록

| API | 요청과 동작 |
|---|---|
| `POST /api/admin/announcement-imports/validate` | v1 JSON 객체를 받아 형식·필수값·기간·중복을 검증하고 공급행별 단지 후보를 돌려준다. 저장하지 않는다. |
| `POST /api/admin/announcement-imports` | `importData`에 검증한 v1 JSON을, `complexSelections[]`에 공급행별 `supplyRowIndex`와 `housingComplexId`를 넣는다. 서버가 재검증한 뒤 한 트랜잭션으로 등록한다. |

- 서버는 각 공급행의 PNU와 공고 임대유형으로 단지 후보를 찾는다. 후보가 하나면 제안하고, 여럿이면 관리자가 선택한다. 후보가 없으면 등록할 수 없다. 등록 요청에는 **모든 공급행**의 인덱스와 후보 ID를 하나씩 포함해야 한다.
- 검증 오류, `unresolvedFields`, 중복 또는 후보 미발견이 있으면 등록할 수 없다. 같은 원천 공고번호, 원문 URL 또는 정규화된 JSON 해시가 이미 등록되었으면 중복으로 처리하며 등록 요청은 `409`를 반환한다.
- 등록 이력에는 스키마 버전, 원문 URL, 원천 공고번호, 정규화된 JSON과 해시, 등록 관리자·시각, 결과 공고 ID를 보존한다.

## 기존 공고 선택 갱신

기본정보·접수처는 요청에서 선택한 필드만, 일반 일정·첨부는 항목별로 적용한다.

- 선택하지 않은 필드는 기존값을 유지한다. 실제 0은 확인된 값으로 적용할 수 있다.
- 일반 일정·첨부는 `existingId`가 없으면 추가, 기존 ID면 그 항목 갱신이다. 요청에서 빠진 기존 항목은 삭제하지 않는다.
- 단지·순위별로 확인된 접수 일정은 보존한다. JSON의 일반 일정은 별도 행사 정보이며 확인된 접수 일정이나 공고 접수 기간을 대신하지 않는다.
- 이유와 공식 근거 URL을 입력하고 전체 변경 미리보기를 확인한 뒤 최종 적용한다. 기본정보·공급정보·일정·첨부와 처리 이력은 한 트랜잭션으로 저장한다. 저장 실패 시 일부만 반영하지 않는다.
- 비교 후 다른 수정·수집이 있으면 `409`로 거절한다. JSON 입력을 유지한 채 최신 저장값을 불러와 다시 비교한다. 저장 응답을 받지 못했을 때도 최신값부터 확인하며 자동 재적용하지 않는다.
- 변경 이력은 기존 공고의 처리자·시각·근거·변경 전후를 기록한다. 공급정보와 수정한 일반 일정·첨부는 재정제 시 보호한다. 원천 식별자와 JSON의 출처 공고번호는 혼동하지 않으며 기존 공고의 원천 식별자는 교체하지 않는다.

API 계약은 [백오피스 품질 작업 흐름](review-api.md)을 참고한다.

## 스키마 배포 확인

Flyway가 `V20260924_01__create_admin_announcement_imports.sql`을 적용한 뒤 `ddl-auto=validate`가 스키마를 검사한다. 빈 DB에는 기준선 `B20260922_01` 다음에, 기존 DB에는 [Flyway 도입 절차](../../operations/database/flyway.md)에 따라 명시적 baseline과 통합 보정을 마친 다음에 이 마이그레이션이 적용된다. SQL을 별도로 수동 실행하지 않는다.

기존 공고의 일반 일정·첨부 보정 보호에는 `V20261019_09__protect_admin_announcement_documents.sql`이 필요하다. 기존 행은 보호하지 않는 상태(`false`)로 유지하고 관리자 갱신 시 해당 행에만 보호를 적용한다.

배포 전에 승인된 대상 DB에서 `flyway_schema_history`와 `admin_announcement_imports` 테이블 유무를 확인한다. 이미 테이블이 있는데 마이그레이션 이력이 없다면 자동 적용을 진행하지 않고 기존 테이블의 컬럼·제약·데이터와 이력을 먼저 대조한다. 적용 후에는 테이블, 원문 URL·JSON 해시·원천 공고번호·공고 ID의 고유 제약, 공고 외래 키 및 백엔드 헬스 체크를 확인한다. 문제가 생기면 이전 백엔드로 되돌리고 등록 이력 테이블은 보존한다. 실제 적용 담당자·대상·시각·결과는 PR 배포 기록에 남긴다.

[미확정값 예시](../../fixtures/announcement-import-v1-unresolved.json)와 [다중 공급행 예시](../../fixtures/announcement-import-v1-multiple-supply-rows.json)를 검토에 사용할 수 있다.
