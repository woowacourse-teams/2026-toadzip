# 관리자 데이터 관리

관리자 세션으로 단지·공고를 수정·삭제·복원한다.
쓰기 요청에는 CSRF 토큰을 사용하고 수정 충돌은 버전으로 확인한다.

## 수정·삭제

### 화면

- `/admin/complexes`: 단지 검색, 기관·임대유형·지역 필터, 검토 대상과 휴지통 조회.
- `/admin/announcements`: 공고 검색, 단지 연결 조건과 공통 필터.
- 공고 상세에서 공급정보의 단지·주택형 연결과 근거가 있는 접수 일정을 수정한다.
- 단지 상세의 주택형·연결 공고 탭에서 주택형 이름·전용면적·세대수를 수정하고, 연결 공고의 기본정보·일정·공급정보를 같은 탭에서 편집한다.
- 주소 자동 채우기는 기존 등록 단지의 주소를 검색한다. 외부 주소 검색 서비스는 사용하지 않는다.

### API

#### 공통 요청

`/api/admin/housing-complexes`, `/api/admin/announcements`에 다음 경로를 제공한다.

| 요청 | 동작 |
|---|---|
| `GET /` | 필터와 페이지 기반 목록 |
| `GET /{id}` | 관리자 상세 |
| `PUT /{id}` | 현재 `version`과 변경값으로 수정 |
| `DELETE /{id}?version=...` | 휴지통 이동 |
| `POST /{id}/restore?version=...` | 복원 |
| `GET /{id}/changes?page=...` | 변경 전후 값·작업자·시각 이력 |

#### 공고와 주택형

- 공고의 `PUT /{id}/supply-rows/{rowId}`로 공급정보와 연결 대상을 수정한다.
- 단지의 `PUT /{id}/housing-types/{typeId}`는 단지 `version`, `name`, `exclusiveArea`, `householdCount`를 받는다. 면적은 소수점 네 자리까지, 세대수는 `null`(미확인)과 0을 구분한다.
- 공고 수정의 접수처 연락처는 미확인이면 `null`을 유지한다. 접수처명·접수방식·URL 검증과 공고 신규 등록의 연락처 필수 규칙은 유지한다.
- 기존 `PUT /{id}/application-schedules`는 선택적 `version` 쿼리로 충돌을 확인한다.

#### 조회 조건

- 이름·주소는 부분 일치, 원천 식별자는 정확한 일치로 검색한다. 장기전세도 조회·수정한다.
- 목록은 `totalElements`와 `totalPages`를 반환하고 등록 시각 내림차순, 같은 시각은 ID 내림차순으로 정렬한다.
- 과거 미기록 등록 시각은 추정하지 않고 NULL로 유지하며, 새 등록 건 뒤에서 ID 내림차순으로 표시한다.

### 데이터 보존

#### 삭제와 복원

- 삭제는 복구 가능한 상태 변경이며 공개 목록·검색·지도·상세에서 제외한다.
- 사용 중인 연결 공고가 있는 단지는 삭제할 수 없다.
- 후속 정정·취소 공고가 있으면 이전 공고를 먼저 삭제할 수 없다.
- 복원 시 이전 공고와 연결 단지가 먼저 복원되어 있어야 한다. 재수집은 삭제된 데이터를 자동 복원하지 않는다.
- 버전이 다르면 409로 응답한다. 입력은 유지하고 새 조회 후 다시 수정한다.

#### 수정과 자동 정제

- 변경과 이력 저장은 한 트랜잭션이다. 데이터 수집 실행권으로 동시 정제와 충돌을 차단한다.
- 관리자 수정 단지·공고는 레코드 단위로 자동 덮어쓰기를 막는다. 원천과 차이가 있으면 검토 대상으로 표시한다.
- 관리자 수정 공급정보 역시 자동 덮어쓰기를 막는다. 원천 식별자와 원본 수집 데이터는 보존한다.
- 관리자 수정 주택형은 마이홈 재정제·LH 세대수 보강·사라진 주택형 정리에서 제외한다. 단지 기본정보의 자동 정제 보호와는 별도로 적용한다.

### 배포·롤백

Flyway 적용 후 서버·관리자 화면을 함께 교체한다. 구버전은 삭제 상태·주택형 수정 보호를 해석하지 못하므로 혼용하지 않는다.
롤백 시 추가 열·수정 이력을 보존하고 주택형 정제를 중지한다.

## 공고 JSON 가져오기

### 원공고 준비

공식 원공고에서 v1 JSON을 작성하고 `JSON 검증 → 단지 연결 확인 → 등록` 순서로 가져온다.
v1은 새 원공고 등록만 지원한다. 정정·취소공고 연결과 기존 공고 갱신은 지원하지 않는다.

원공고, 이 정책과 [정상 예시](../../../docs/fixtures/announcement-import-v1-valid.json)를 Codex에 제공하고 다음과 같이 요청한다.

```text
원공고에서 admin-announcement-import/v1 JSON 객체만 추출해 주세요.
원문에 없는 값을 추정하거나 내부 DB ID를 만들지 마세요.
확인할 수 없는 값은 null과 unresolvedFields의 경로·사유로 표시하세요.
정정·취소공고라면 JSON을 만들지 말고 알려 주세요.
```

### JSON 작성

#### 구조와 근거

- `schemaVersion`은 `admin-announcement-import/v1`이다. 전체 구조는 [정상 예시](../../../docs/fixtures/announcement-import-v1-valid.json)를 따른다.
  최상위에는 `source`, `announcement`, `receptionPlace`, `supplyRows`, `schedules`, `attachments`, `unresolvedFields`만 넣는다.
- `source.originalUrl`은 공식 원공고의 HTTP(S) URL, `source.sourceDocumentId`는 공식 공고번호다. 공고번호가 없으면 `null`을 쓴다. `source.extractedAt`은 추출 시각과 오프셋을 포함한다.
- 원문에서 확인한 값만 적는다. 확인할 수 없는 값은 추정하거나 `ETC`로 대체하지 않고 `null`로 두며, 확인이 필요한 경우 `unresolvedFields`에 JSON 경로와 사유를 적는다. `ETC`는 원문에 기타 범주가 명시된 경우에만 쓴다. 필수값이 `null`이면 검증 오류가 발생하며, 확인 전에는 등록할 수 없다.
- 내부 DB ID는 JSON에 넣지 않는다. 공급행마다 원문 단지명과 19자리 숫자 PNU를 `complexReference`에 넣는다.

#### 형식과 제한

- 날짜는 `YYYY-MM-DD`, 연월은 `YYYY-MM`, 일정 일시는 `YYYY-MM-DDTHH:mm:ss` 형식이다. 금액은 원 단위 정수, 세대수는 0 이상 정수다. 공급행·대상·일정·첨부 배열은 원문 순서를 유지하며 이 순서가 저장 순서가 된다.
- `supplyRows`는 1개 이상이어야 한다. `schedules`, `attachments`, `unresolvedFields`는 값이 없어도 빈 배열로 넣는다. 접수 종료일과 일정 종료일시는 각각 시작보다 빠를 수 없다.
- 정규화된 JSON은 UTF-8 기준 1,000,000바이트 이하여야 한다. 공급행·미확정값은 각각 최대 200개, 일정·첨부와 공급행별 대상은 각각 최대 100개다.

#### 허용값

| 필드 | 허용값 |
|---|---|
| `announcement.rentalType` | `HAPPY_HOUSING`, `NATIONAL_RENTAL`, `PERMANENT_RENTAL`, `PUBLIC_RENTAL_5Y`, `PUBLIC_RENTAL_10Y`, `PUBLIC_RENTAL_50Y`, `INTEGRATED_PUBLIC_RENTAL`, `REDEVELOPMENT_RENTAL`, `ETC` |
| `announcement.recruitmentType` | `NEW`, `WAITLIST`, `ETC` |
| `announcement.agencyCode` | `LH`, `SH`, `GH`, `ETC` |
| `receptionPlace.method` | `ONLINE`, `VISIT`, `MAIL`, `ETC` |
| `supplyRows[].supplyCategory` | `NEW_SUPPLY`, `RESUPPLY` |
| `schedules[].scheduleType` | `APPLICATION`, `DOCUMENT_SUBMISSION`, `WINNER_ANNOUNCEMENT`, `CONTRACT`, `MOVE_IN`, `ETC` |
| `attachments[].fileType` | 원공고에서는 `ANNOUNCEMENT`, `REFERENCE`; 실제 기타 자료에 한해 `ETC` |

### 검증과 등록

#### 화면

1. **JSON 가져오기**에 JSON을 붙여넣거나 UTF-8 `.json` 파일 하나를 선택한다.
2. **JSON 검증**으로 공고 내용·연결 단지를 확인한다. 내용을 수정하면 다시 검증한다.
3. **검토한 내용으로 등록**으로 저장한다. 파일 선택만으로 저장되지는 않는다.

큰 금액의 정확한 숫자는 원본 JSON 전체 보기에서 확인한다. 검증·등록 요청에는 원문 숫자를 그대로 보낸다.

#### API

| API | 요청과 동작 |
|---|---|
| `POST /api/admin/announcement-imports/validate` | v1 JSON 객체를 받아 형식·필수값·기간·중복을 검증하고 공급행별 단지 후보를 돌려준다. 저장하지 않는다. |
| `POST /api/admin/announcement-imports` | `importData`에 검증한 v1 JSON을, `complexSelections[]`에 공급행별 `supplyRowIndex`와 `housingComplexId`를 넣는다. 서버가 재검증한 뒤 한 트랜잭션으로 등록한다. |

- 서버는 각 공급행의 PNU와 공고 임대유형으로 단지 후보를 찾는다. 후보가 하나면 제안하고, 여럿이면 관리자가 선택한다. 후보가 없으면 등록할 수 없다. 등록 요청에는 **모든 공급행**의 인덱스와 후보 ID를 하나씩 포함해야 한다.
- 검증 오류, `unresolvedFields`, 중복 또는 후보 미발견이 있으면 등록할 수 없다. 같은 원천 공고번호, 원문 URL 또는 정규화된 JSON 해시가 이미 등록되었으면 중복으로 처리하며 등록 요청은 `409`를 반환한다.
- 등록 이력에는 스키마 버전, 원문 URL, 원천 공고번호, 정규화된 JSON과 해시, 등록 관리자·시각, 결과 공고 ID를 보존한다.

### 배포 확인

스키마는 [DB 운영](DATABASE.md#flyway)의 Flyway 경로로 적용한다. SQL을 별도로 수동 실행하지 않는다.

| 시점 | 확인할 내용 |
|---|---|
| 적용 전 | 대상 DB의 `flyway_schema_history`와 `admin_announcement_imports` 존재 여부 |
| 적용 후 | 테이블, 원문 URL·JSON 해시·원천 공고번호·공고 ID의 고유 제약, 공고 외래 키, 백엔드 헬스 체크 |

테이블이 있는데 적용 이력이 없으면 자동 적용을 중단하고 컬럼·제약·데이터와 이력을 먼저 대조한다.
문제가 생기면 이전 백엔드로 되돌리고 등록 이력 테이블은 보존한다.

[미확정값 예시](../../../docs/fixtures/announcement-import-v1-unresolved.json)와 [다중 공급행 예시](../../../docs/fixtures/announcement-import-v1-multiple-supply-rows.json)를 검토에 사용할 수 있다.
