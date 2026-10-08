# 관리자 원천 데이터 조회

관리자는 `GET /api/admin/ingest/sources`로 저장된 원천 행 전체를 페이지 단위로 조회한다.
수집·정제 상태와 관계없이 저장된 행을 보여 주며 데이터를 변경하거나 외부 API를 호출하지 않는다.
기존 관리자 인증을 적용한다.

| 요청 쿼리 | 계약 |
|---|---|
| `category` | 아래 원천 분류 중 하나, 필수 |
| `page` | 0부터 시작, 기본 0 |
| `size` | 1~100, 기본 20 |
| `keyword` | 이름·원천 식별자의 부분 검색, 최대 200자, 앞뒤 공백 제거 |

| 분류 | 저장 테이블 |
|---|---|
| `MYHOME_COMPLEX` | `myhome_complex_source_rows` |
| `LH_LEASE_CATALOG` | `lh_lease_catalog_source_rows` |
| `MYHOME_ANNOUNCEMENT` | `myhome_announcement_source_rows` |
| `LH_ANNOUNCEMENT_CATALOG` | `lh_announcement_catalog_entries` |
| `LH_ANNOUNCEMENT_DETAIL` | `lh_announcement_detail_rows` |
| `LH_ANNOUNCEMENT_SUPPLY` | `lh_announcement_supply_rows` |

현행 수집 원천만 조회한다. 빈 수집 묶음은 빈 목록으로 표시한다.
활성·비활성 원천 행은 모두 표시하며 정제용 snapshot 중복 제거를 적용하지 않는다.

성공 응답은 `{data: {items, page, totalElements, totalPages, hasNext}}`다.
잘못된 분류·페이지·크기·검색 길이는 HTTP 400 `VALIDATION_FAILED`로 거부한다.
정렬은 수집 시각 내림차순, ID 내림차순, 원천 키 오름차순이며 미기록 수집 시각은 마지막에 둔다.
빈 목록과 마지막 페이지 뒤에도 전체 개수와 전체 페이지 수를 반환한다.

| `items` 필드 | 의미 |
|---|---|
| `id`, `sourceKey`, `name` | 저장 행 ID, 원천 식별자, 저장된 이름(없으면 식별자) |
| `sourceUrl` | 설정된 수집 API base URL과 경로, query·fragment·인증정보 제외 |
| `originalUrl` | 저장된 공식 공고 원문 URL, 없으면 `null` |
| `collectedAt` | UTC ISO 8601 수집 시각, 과거 미기록 행은 `null` |
| `sourceUpdatedAt` | LH 공고 목록의 원천 변경 감지 시각, 나머지는 `null` |
| `raw` | DB에 저장된 원천 항목을 JSON 객체로 전달 |

`raw`는 LH 공고 목록에서는 저장된 `rawPayload` 원천 객체다.
나머지 다섯 분류는 저장 행의 `snake_case` 필드 객체이며 수집 응답 전체를 의미하지 않는다.
LH 상세·공급의 `pan_id`·`request_hash`는 요청 묶음에서 보완한다. 테이블 간 ID 중복은 `sourceKey`와 함께 구분한다.
LH 상세의 첨부파일 URL은 `raw.url`에 보존하고 공고 원문으로 표시하지 않는다.
마이홈 공고의 원문 URL 선택 순서는 기존 정제와 같은 `url` → `pc_url` → `mobile_url`이다.

`raw` 안의 정수는 JavaScript 안전 정수 범위(±9,007,199,254,740,991) 밖이면 문자열로 전달한다.
소수도 반올림 없이 원천값을 표시하기 위해 문자열로 전달하며 중첩 객체·배열에도 같은 규칙을 적용한다.
안전 범위의 원천 정수와 목록 DTO의 `id`·`page`·개수 필드는 숫자 계약을 유지한다.

관리자 화면은 여섯 원천을 탭으로 나누고 저장 행 1건을 표의 한 행에 표시한다.
주요 원천 항목을 앞쪽 열에 두고 나머지 항목은 가로 스크롤로 확인한다. 중첩 항목은 경로를 열 제목에 보존한다.
첫 열과 열 제목을 고정하며 긴 셀 값은 말줄임 뒤에 전체 문자열을 툴팁으로 제공한다.
열 제목은 `한국어 의미 (원천 키)` 형식이다. 확인되지 않은 항목은 `의미 확인 필요`로 표시한다.
마이홈 항목명은 공공데이터포털 활용명세를 따른다. `bass_cnvrs_gtn_lmt`는 기본 전환보증금,
마이홈 공고의 `mt_rntchrg`는 최소 월임대료로 구분하며 LH 금액 원천 문자열의 단위를 임의로 바꾸지 않는다.
제공기관, 공식 API 설명·활용신청 페이지와 수집 API URL은 탭 위에 한 번 표시한다.
