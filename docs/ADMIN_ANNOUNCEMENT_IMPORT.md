# 관리자 공고 JSON 가져오기 정책

## 적용 범위

관리자는 공식 원공고에서 v1 JSON을 작성하고 `JSON 검증 → 단지 연결 확인 → 명시적 등록` 순서로 가져온다. v1은 새 원공고 등록만 지원하며 정정·취소공고 연결과 기존 공고 갱신은 지원하지 않는다. JSON 검증은 데이터를 저장하지 않는다.

원공고, 이 정책과 [정상 예시](fixtures/announcement-import-v1-valid.json)를 Codex에 제공하고 다음과 같이 요청한다.

```text
원공고에서 admin-announcement-import/v1 JSON 객체만 추출해 주세요.
원문에 없는 값을 추정하거나 내부 DB ID를 만들지 마세요.
확인할 수 없는 값은 null과 unresolvedFields의 경로·사유로 표시하세요.
정정·취소공고라면 JSON을 만들지 말고 알려 주세요.
```

## JSON 작성 규칙

- `schemaVersion`은 `admin-announcement-import/v1`이다. 최상위에는 `source`, `announcement`, `receptionPlace`, `supplyRows`, `schedules`, `attachments`, `unresolvedFields`를 넣고 정의되지 않은 필드는 넣지 않는다. 전체 구조는 [정상 예시](fixtures/announcement-import-v1-valid.json)를 따른다.
- `source.originalUrl`은 공식 원공고의 HTTP(S) URL, `source.sourceDocumentId`는 공식 공고번호다. 공고번호가 없으면 `null`을 쓴다. `source.extractedAt`은 추출 시각과 오프셋을 포함한다.
- 원문에서 확인한 값만 적는다. 확인할 수 없는 값은 추정하거나 `ETC`로 대체하지 않고 `null`로 두며, 확인이 필요한 경우 `unresolvedFields`에 JSON 경로와 사유를 적는다. `ETC`는 원문에 기타 범주가 명시된 경우에만 쓴다. 필수값이 `null`이면 검증 오류가 발생하며, 확인 전에는 등록할 수 없다.
- 내부 DB ID는 JSON에 넣지 않는다. 공급행마다 원문 단지명과 19자리 숫자 PNU를 `complexReference`에 넣는다.
- 날짜는 `YYYY-MM-DD`, 연월은 `YYYY-MM`, 일정 일시는 `YYYY-MM-DDTHH:mm:ss` 형식이다. 금액은 원 단위 정수, 세대수는 0 이상 정수다. 공급행·대상·일정·첨부 배열은 원문 순서를 유지하며 이 순서가 저장 순서가 된다.
- `supplyRows`는 1개 이상이어야 한다. `schedules`, `attachments`, `unresolvedFields`는 값이 없어도 빈 배열로 넣는다. 접수 종료일과 일정 종료일시는 각각 시작보다 빠를 수 없다.
- 정규화된 JSON은 UTF-8 기준 1,000,000바이트 이하여야 한다. 공급행·미확정값은 각각 최대 200개, 일정·첨부와 공급행별 대상은 각각 최대 100개다.

| 필드 | 허용값 |
|---|---|
| `announcement.rentalType` | `HAPPY_HOUSING`, `NATIONAL_RENTAL`, `PERMANENT_RENTAL`, `PUBLIC_RENTAL_5Y`, `PUBLIC_RENTAL_10Y`, `PUBLIC_RENTAL_50Y`, `INTEGRATED_PUBLIC_RENTAL`, `REDEVELOPMENT_RENTAL`, `ETC` |
| `announcement.recruitmentType` | `NEW`, `WAITLIST`, `ETC` |
| `announcement.agencyCode` | `LH`, `SH`, `GH`, `ETC` |
| `receptionPlace.method` | `ONLINE`, `VISIT`, `MAIL`, `ETC` |
| `supplyRows[].supplyCategory` | `NEW_SUPPLY`, `RESUPPLY` |
| `schedules[].scheduleType` | `APPLICATION`, `DOCUMENT_SUBMISSION`, `WINNER_ANNOUNCEMENT`, `CONTRACT`, `MOVE_IN`, `ETC` |
| `attachments[].fileType` | 원공고에서는 `ANNOUNCEMENT`, `REFERENCE`; 실제 기타 자료에 한해 `ETC` |

## 검증과 등록

| API | 요청과 동작 |
|---|---|
| `POST /api/admin/announcement-imports/validate` | v1 JSON 객체를 받아 형식·필수값·기간·중복을 검증하고 공급행별 단지 후보를 돌려준다. 저장하지 않는다. |
| `POST /api/admin/announcement-imports` | `importData`에 검증한 v1 JSON을, `complexSelections[]`에 공급행별 `supplyRowIndex`와 `housingComplexId`를 넣는다. 서버가 재검증한 뒤 한 트랜잭션으로 등록한다. |

- 서버는 각 공급행의 PNU와 공고 임대유형으로 단지 후보를 찾는다. 후보가 하나면 제안하고, 여럿이면 관리자가 선택한다. 후보가 없으면 등록할 수 없다. 등록 요청에는 **모든 공급행**의 인덱스와 후보 ID를 하나씩 포함해야 한다.
- 검증 오류, `unresolvedFields`, 중복 또는 후보 미발견이 있으면 등록할 수 없다. 같은 원천 공고번호, 원문 URL 또는 정규화된 JSON 해시가 이미 등록되었으면 중복으로 처리하며 등록 요청은 `409`를 반환한다.
- 등록 이력에는 스키마 버전, 원문 URL, 원천 공고번호, 정규화된 JSON과 해시, 등록 관리자·시각, 결과 공고 ID를 보존한다.

## 스키마 배포 확인

운영은 `ddl-auto=validate`이며 이 SQL은 자동 실행되지 않는다. 배포 담당자는 **새 백엔드 실행 전에** 대상 DB를 확인하고 다음 SQL을 한 번 적용한다. 아래 명령은 저장소 루트에서 실행하며, `DATABASE_URL`은 승인된 배포 환경에서 주입한다. 연결 문자열을 PR이나 로그에 기록하지 않는다.

```bash
psql "$DATABASE_URL" -X --set ON_ERROR_STOP=1 \
  -c "SELECT current_database(), current_schema(), to_regclass('public.admin_announcement_imports');"
```

대상 DB가 예상과 일치하고 `current_schema()`가 `public`이며 `to_regclass`가 `NULL`일 때만 실행한다. 이미 테이블이 있으면 생성 SQL을 재실행하지 말고 아래 기존 테이블 절차를 따른다.

```bash
psql "$DATABASE_URL" -X --set ON_ERROR_STOP=1 --single-transaction \
  --file backend/src/main/resources/db/migration/V20260920_01__create_admin_announcement_imports.sql
psql "$DATABASE_URL" -X --set ON_ERROR_STOP=1 \
  -c "SELECT conname FROM pg_constraint WHERE conrelid = 'public.admin_announcement_imports'::regclass ORDER BY conname;"
```

`admin_announcement_imports` 테이블과 원문 URL·JSON 해시·원천 공고번호·공고 ID의 고유 제약 및 공고 외래 키가 있는지 확인한다. 이후 새 백엔드를 배포해 스키마 검사와 헬스 체크가 통과하는지 확인한다. 문제가 생기면 이전 백엔드로 되돌리고 등록 이력 테이블은 보존한다. 실제 적용 담당자·대상·시각·결과는 PR 배포 기록에 남긴다.

이미 테이블이 있는 DB에서는 위 제약 조회 결과를 먼저 확인한다. `uk_admin_announcement_import_original_url`만 없다면 중복 URL 건수를 확인하고, 결과가 `0`일 때만 다음 제약을 한 번 추가한다. 중복이 있거나 다른 제약도 누락됐다면 데이터와 스키마를 조사한 뒤 적용 계획을 세운다.

```bash
psql "$DATABASE_URL" -X --set ON_ERROR_STOP=1 \
  -c "SELECT count(*) FROM (SELECT original_url FROM public.admin_announcement_imports GROUP BY original_url HAVING count(*) > 1) duplicates;"
psql "$DATABASE_URL" -X --set ON_ERROR_STOP=1 --single-transaction \
  -c "ALTER TABLE public.admin_announcement_imports ADD CONSTRAINT uk_admin_announcement_import_original_url UNIQUE (original_url);"
```

[미확정값 예시](fixtures/announcement-import-v1-unresolved.json)와 [다중 공급행 예시](fixtures/announcement-import-v1-multiple-supply-rows.json)를 검토에 사용할 수 있다.
