# 단지 검색 필터 설계

## 목적

사용자가 지도 영역 안에서 원하는 단지를 여러 조건으로 좁혀 보고, 같은 조건을 유지한 채
목록을 정렬하고 다음 페이지를 조회할 수 있게 한다. 목록과 지도 핀은 같은 검색 조건을
사용해 서로 다른 단지 집합을 보여 주지 않는다.

## 기준선과 연관 이슈

- 구현 기준선은 단지 조회 MVP #21이 병합된 `origin/develop`이다.
- #23은 백엔드가 전달받은 `regionCode`로 단지를 필터링하는 책임을 유지한다.
- 지역명 검색과 지역 후보 제공 API는 #63에서 구현한다. 프런트 자동완성·선택 UI는 #63과
  #23 모두의 범위 밖이다. #63은 #23의 백엔드 완료를 막지 않지만 사용자에게 지역 코드를
  노출하지 않는 전체 흐름의 백엔드 계약에는 필요하다.
- #22가 먼저 병합되면 `RegionCodeResolver.equivalentCodes`와 검색 요청 정규화 방식을
  재사용한다. 두 이슈가 같은 지역 Resolver를 수정하면 최신 `develop` 기준으로 통합한다.

## 범위

### 포함

- `GET /api/v1/complexes`의 검색 필터, 다섯 가지 정렬과 커서 페이지네이션
- `GET /api/v1/complexes/map`의 같은 검색 필터
- 필터 독립 동작, 복수 조건 조합, 범위 검증과 표준 오류
- 현재 enum 이름과 과거 한글 저장값의 동등 검색
- 기존 단지 목록·지도·상세 조회 회귀 검증

### 제외

- `GET /api/v1/regions`: #63
- 지역명 자동완성·선택 UI를 포함한 프런트 구현
- 응답 필드, Entity 또는 DB 스키마 변경
- 새 production dependency
- 실행계획 근거가 없는 선제 인덱스

## 공개 API 계약

두 단지 검색 API는 기존의 필수 WGS 84 지도 영역 네 값과 아래 선택 필터를 받는다.

| Query | Type | 의미 |
|---|---|---|
| `keyword` | String | 단지명 또는 도로명주소 부분 검색 |
| `regionCode` | String | 2자리 시·도 또는 5자리 시·군·구 코드 |
| `rentalTypes` | RentalType[] | 공급유형 |
| `applicationStatuses` | ApplicationStatus[] | 대표 공고의 접수 상태 |
| `agencyCodes` | AgencyCode[] | 공급기관 |
| `recruitmentTypes` | RecruitmentType[] | 대표 공고의 모집유형 |
| `minDeposit` | Long | 최소 임대보증금 |
| `maxDeposit` | Long | 최대 임대보증금 |
| `minMonthlyRent` | Long | 최소 월임대료 |
| `maxMonthlyRent` | Long | 최대 월임대료 |
| `minExclusiveArea` | Decimal | 최소 전용면적 |
| `maxExclusiveArea` | Decimal | 최대 전용면적 |
| `builtYearFrom` | Integer | 최소 준공년도 |
| `builtYearTo` | Integer | 최대 준공년도 |
| `hasElevator` | Boolean | 승강기 설치 여부 |

복수 enum은 같은 query key를 반복해 전달한다. 같은 필터 그룹의 값은 OR, 서로 다른 그룹과
지도 영역은 AND로 결합한다. 경계값은 모두 포함한다. 생략한 필터는 결과를 제한하지 않는다.

목록 API만 다음 값을 추가로 받는다.

| Query | Type | 기본값 |
|---|---|---|
| `sort` | ComplexSort | `LATEST_ANNOUNCEMENT` |
| `cursor` | String | 없음 |
| `size` | Integer | 20, 허용 범위 1..50 |

지도 API는 정렬과 페이지네이션을 받지 않고, 필터링된 핀을 단지 ID 오름차순으로 반환한다.

## 필터 의미

### 직접 단지 조건

- `keyword`는 앞뒤 공백을 제거한 뒤 단지명과 도로명주소를 대소문자 구분 없이 부분
  검색한다. `%`, `_`, `\\`는 LIKE wildcard가 아니라 문자 그대로 처리한다.
- 2자리 `regionCode`는 `province_code`와 일치시킨다. 5자리 코드는 현재 코드와 모든 과거
  alias를 해석해 `city_county_district_code`와 일치시킨다.
- `rentalTypes`와 `agencyCodes`는 현재 enum 이름과 `LegacyStoredValue`의 과거 저장값을 모두
  일치시킨다.
- `builtYearFrom`과 `builtYearTo`는 `completion_date`의 연도에 양끝 포함으로 적용한다.
- `hasElevator`는 `elevator_installed`와 정확히 일치시킨다.

### 대표 공고 조건

대표 공고는 #21과 동일하게 후속 리비전이 없고 취소되지 않은 공고 중 단지별 게시일과 ID가
가장 큰 공고다.

- `recruitmentTypes`는 대표 공고의 모집유형에 적용한다.
- `applicationStatuses`는 서울 기준 조회일로 계산한다.
  - `BEFORE_APPLICATION`: 접수 시작일이 조회일보다 뒤다.
  - `APPLYING`: 접수 시작일 이상이고 접수 종료일 이하다.
  - `CLOSED`: 접수 종료일이 조회일보다 앞이다.
  - `CANCELLED`: 비취소 대표 공고 검색과 모순되므로 요청을 거부한다.
- 대표 공고 조건이 있는데 대표 공고가 없는 단지는 일치하지 않는다.

### 가격과 면적 조건

- 면적 조건만 있으면 한 개의 실제 `HousingType`이 전달된 면적 하한과 상한을 모두 만족해야
  한다. 단지 전체 min/max 구간만 겹치고 실제 주택형이 없는 경우는 일치하지 않는다.
- 가격 조건이 하나라도 있으면 대표 공고에 속한 한 개의 동일한
  `SupplyRow -> SupplyTarget`이 모든 보증금·월임대료 조건을 만족해야 한다.
- 가격 조건과 면적 조건을 함께 전달하면 그 공급행에 매칭된 한 개의 동일한 `HousingType`이
  면적 조건도 만족해야 한다. 서로 다른 공급대상이나 주택형의 값을 조합해 일치시키지 않는다.
- 필터가 적용돼도 응답의 면적 min/max는 단지 전체 주택형, 가격 min/max는 대표 공고 전체
  공급대상 기준을 유지한다. 필터에 일치한 한 행만으로 응답 범위를 축소하지 않는다.
- null 가격은 숫자 범위 필터에 일치하지 않는다.

## 정렬과 커서

| ComplexSort | 주 정렬값 | 방향 |
|---|---|---|
| `LATEST_ANNOUNCEMENT` | 대표 공고 게시일 | DESC |
| `DEPOSIT_ASC` | 대표 공고 최소 보증금 | ASC |
| `MONTHLY_RENT_ASC` | 대표 공고 최소 월임대료 | ASC |
| `AREA_DESC` | 단지 최대 전용면적 | DESC |
| `COMPLETION_DATE_DESC` | 단지 준공일 | DESC |

- 모든 정렬은 null을 마지막에 두고 동률은 `complex_id DESC`로 고정한다.
- 목록은 필터를 적용한 결과에서 `size + 1`개를 조회해 `hasNext`를 판단한다.
- v2 커서는 버전, 정렬 종류, 주 정렬값의 null 여부와 값, 마지막 단지 ID를 담는다.
- 커서의 정렬 종류와 요청 `sort`가 다르거나 값 형식이 정렬 타입과 맞지 않으면
  `INVALID_CURSOR`다.
- #21 v1 커서는 `LATEST_ANNOUNCEMENT` 요청에서만 계속 해석하고 새 응답은 v2로 발급한다.
- 다음 페이지 요청은 같은 필터와 정렬을 다시 전달한다. 커서는 필터 집합의 fingerprint를
  저장하지 않는다.

## 컴포넌트와 책임

```text
HousingComplexController
  -> HousingComplexSearchRequest
  -> HousingComplexQueryService
       -> RegionCodeResolver
       -> HousingComplexSearchCondition
  -> ComplexSummaryQueryRepository
       -> ComplexSummarySqlBuilder
  -> PostgreSQL
```

- `HousingComplexSearchRequest`: HTTP query parameter만 표현한다.
- `HousingComplexQueryService`: keyword·목록 정규화, 범위 교차 검증, 지역 코드 해석, 서울 기준
  조회일 결정, 커서 decode와 응답 page 조립을 담당한다.
- `HousingComplexSearchCondition`: Repository가 사용하는 불변 검색 조건이다.
- `ComplexSummarySqlBuilder`: 허용된 predicate·sort·cursor SQL 조각과 named parameter를
  조립한다. 사용자 문자열을 SQL 문법으로 삽입하지 않는다.
- `ComplexSummaryQueryRepository`: 생성된 SQL을 실행하고 `ComplexSummaryRow`로 매핑한다.
- 목록과 지도 Service는 같은 조건을 만들고 같은 검색 predicate를 사용한다. 목록만 정렬,
  cursor와 limit를 추가한다.
- 기존 응답 mapper와 응답 DTO는 검색 결과 행을 그대로 변환한다.

## SQL 구성

기존 `latest_leaf`, `representative`, `area_range`, `price_range` CTE와 응답 projection을
유지한다. 직접 단지 조건은 최종 `housing_complex` WHERE에, 대표 공고 조건은 대표 공고와
연결된 predicate에 적용한다.

면적 전용 조건은 `housing_types` 상관 `EXISTS`를 사용한다. 가격 조건은 대표 공고의
`supply_rows`와 `supply_targets`를 묶은 상관 `EXISTS`를 사용하며, 면적도 함께 전달되면
같은 subquery에서 매칭된 `housing_type`을 검사한다. collection join으로 최종 단지 행을
증식시키지 않는다.

정렬 enum은 사전에 정의된 SQL expression, 방향과 cursor 비교 연산자에만 매핑한다. 모든
필터 값, cursor 값과 limit는 named parameter로 전달한다. 목록과 지도는 요청당 요약 SQL을
한 번만 실행하고 Entity lazy loading을 사용하지 않는다.

## 검증과 오류

- 금액과 면적은 0 이상이어야 하며 각 하한은 상한보다 클 수 없다.
- 준공년도는 1..9999이고 `builtYearFrom <= builtYearTo`여야 한다.
- 공백 keyword, 빈 enum 요소, `CANCELLED` 상태와 역전된 범위는 `INVALID_REQUEST`다.
- 등록되지 않은 2자리 시·도 코드와 5자리 현재·과거 시·군·구 코드는
  `INVALID_REGION_CODE`다.
- enum·숫자·Boolean 형식 변환 실패는 기존 공통 계약의 `VALIDATION_FAILED`다.
- 잘못된 cursor는 `INVALID_CURSOR`, 잘못된 지도 영역은 `INVALID_MAP_BOUNDS`다.
- 모든 오류는 기존 `ErrorResponse` 형식과 trace ID를 유지한다.

## 테스트 전략

1. Controller 계약 테스트로 반복 enum binding, 기본 정렬, 모든 query 전달과 변환 오류를
   검증한다.
2. Service 테스트로 정규화, 범위 검증, 지역 해석, 고정 Clock 사용, 검색 조건과 cursor 전달을
   검증한다.
3. 실제 PostgreSQL Repository 통합 테스트로 각 필터의 독립 동작을 한 테스트에 한 동작씩
   고정한다.
4. 같은 그룹 OR, 그룹 간 AND, 지도 영역 AND, 면적 gap, 동일 공급대상, 최신 비취소 공고와
   legacy enum 저장값을 별도 회귀 테스트로 검증한다.
5. Cursor Codec 테스트로 다섯 정렬 타입, null 값, 잘못된 타입, sort 불일치와 v1 호환을
   검증한다.
6. API 통합 테스트로 복수 필터와 각 정렬을 사용한 2페이지에서 중복·누락이 없고, 같은
   조건의 목록 ID와 지도 핀 ID가 일치하는지 검증한다.
7. 기존 #21의 무필터 목록·지도·상세 테스트와 전체 `./gradlew --rerun-tasks check`를 실행한다.

## 완료 조건

- 모든 필터가 목록과 지도에서 독립적으로 동작한다.
- 같은 그룹 OR, 그룹 간 AND와 동일 공급대상 규칙이 테스트로 증명된다.
- 다섯 정렬이 null과 동률을 포함해 안정적으로 페이지를 이어 간다.
- 필터가 적용된 두 페이지에 중복·누락이 없다.
- 같은 조건의 목록과 지도 결과 집합이 일치한다.
- 잘못된 요청은 정의된 표준 오류로 반환된다.
- 기존 단지 조회 공개 응답과 무필터 동작이 회귀하지 않는다.

## 성능과 후속 판단

이번 변경에서는 인덱스를 추가하지 않는다. PostgreSQL 통합 테스트와 실제에 가까운 데이터로
쿼리 실행계획을 확인한 뒤, 병목이 확인되면 조건·정렬별 인덱스를 근거와 함께 별도 변경으로
다룬다. 지도 API의 무제한 결과 계약도 #23에서 바꾸지 않는다.
