# Housing Complex Search Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이슈 #23의 전체 단지 검색 필터를 목록·지도 API에 동일하게 적용하고, 목록에서 다섯 정렬을 안정적인 keyset cursor로 페이지네이션한다.

**Architecture:** 기존 `HousingComplexController -> HousingComplexQueryService -> ComplexSummaryQueryRepository` 흐름과 #21의 PostgreSQL CTE/JdbcClient 조회를 유지한다. HTTP 값은 `HousingComplexSearchRequest`, 정규화된 불변 조건은 `HousingComplexSearchCondition`, 허용된 SQL 조각은 `ComplexSummarySqlBuilder`가 맡는다. 목록과 지도는 같은 filter builder를 사용하고 목록에만 sort/cursor/limit를 붙인다.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, Bean Validation binding, Spring JDBC `JdbcClient`, Spring Data JPA test slice, PostgreSQL 17, JUnit 5, Mockito, MockMvc

**Spec:** GitHub issue [#23](https://github.com/woowacourse-teams/2026-toadzip/issues/23), [승인된 설계](../specs/2026-08-27-housing-complex-search-filter-design.md), 지역 조회 후속 이슈 [#63](https://github.com/woowacourse-teams/2026-toadzip/issues/63)

## Global Constraints

- 구현 시작 전 `origin/develop`을 fetch한 뒤 새 기능 브랜치/격리 worktree를 만든다. 계획 작성 시 로컬 `develop`은 `origin/develop`보다 109커밋 뒤에 있으므로 현재 checkout의 오래된 source를 기준으로 구현하지 않는다.
- 목록과 지도는 필수 WGS 84 bounds 및 `keyword`, `regionCode`, `rentalTypes`, `applicationStatuses`, `agencyCodes`, `recruitmentTypes`, 보증금·월임대료·전용면적 범위, 준공년도 범위, `hasElevator`를 모두 받는다.
- 같은 enum 그룹은 OR, 서로 다른 그룹 및 bounds는 AND이고 모든 범위 경계는 포함한다. 생략한 조건은 결과를 제한하지 않는다.
- 목록만 `sort`(기본 `LATEST_ANNOUNCEMENT`), `cursor`, `size`(기본 20, 1..50)를 받는다. 지도는 정렬·페이지네이션 없이 `complex_id ASC`다.
- `regionCode`는 #23에서 계속 받는다. 2자리 시·도는 `province_code`, 5자리 현재/과거 시·군·구는 resolver가 돌려준 동등 코드 집합으로 필터링한다.
- `GET /api/v1/regions`는 #63의 **백엔드** 범위다. 지역명 자동완성·선택·코드 전달을 포함한 프런트 구현은 #23과 #63 모두에서 하지 않는다.
- 계획 기준선에는 #22가 병합되지 않았다. `RegionCodeResolver.equivalentCodes`는 #23의 필수 최종 계약으로 구현한다. 실행 시 #22가 먼저 병합돼 같은 default method가 이미 있으면 중복 작성하지 않고 그 구현과 테스트를 보존한 채 `isRegisteredProvinceCode`만 통합한다.
- 대표 공고는 후속 리비전이 없고 취소되지 않은 leaf 중 단지별 `posted_date DESC, id DESC` 첫 행이다. 대표 조건으로 과거 공고를 다시 선택하면 안 된다.
- 면적-only는 실제 `HousingType` 한 행, 가격은 대표 공고의 실제 `SupplyRow -> SupplyTarget` 한 행에서 모든 범위를 동시에 만족시킨다. 가격+면적이면 같은 `SupplyRow`의 `HousingType`까지 일치해야 한다.
- filter용 `EXISTS`는 응답 집계 범위를 축소하지 않는다. 응답 area range는 단지 전체 주택형, price range는 대표 공고 전체 공급대상 기준을 유지한다.
- 다섯 정렬 모두 `NULLS LAST, complex_id DESC` tie-breaker를 사용한다. v2 cursor는 version/sort/null marker/value/complex ID를 담고, v1은 `LATEST_ANNOUNCEMENT`에서만 읽는다. 새 응답은 항상 v2다.
- 응답 DTO, Entity, DB schema, production dependency와 인덱스는 변경하지 않는다. 실행계획 근거가 필요한 성능 변경은 후속 작업으로 남긴다.
- 타입 변환 실패는 `VALIDATION_FAILED`, 의미 검증 실패는 `INVALID_REQUEST`, 지역은 `INVALID_REGION_CODE`, cursor는 `INVALID_CURSOR`, bounds는 `INVALID_MAP_BOUNDS`를 유지한다.
- 모든 동작은 컴파일되는 실패 테스트를 먼저 확인하고 최소 구현으로 통과시킨다. PostgreSQL 의미는 mock/H2가 아니라 `compose.test.yaml`의 PostgreSQL 17로 검증한다.

---

### Task 1: 지역 코드 동등성 및 오류 계약

**Files:**
- Modify: `backend/src/main/java/com/toadzip/backend/region/repository/RegionCodeResolver.java`
- Modify: `backend/src/main/java/com/toadzip/backend/region/repository/CsvRegionCodeResolver.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/exception/InvalidRegionCodeException.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexExceptionAdvice.java`
- Test: `backend/src/test/java/com/toadzip/backend/region/repository/CsvRegionCodeResolverTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexExceptionAdviceTest.java`

**Interfaces:**

```java
@FunctionalInterface
public interface RegionCodeResolver {
    Optional<String> resolve(String provinceCode, String cityCountyDistrictCode);

    default Optional<Set<String>> equivalentCodes(String regionCode) {
        return Optional.empty();
    }

    default boolean isRegisteredProvinceCode(String provinceCode) {
        return false;
    }
}
```

`resolve`를 유일한 abstract method로 유지해 기존 lambda 대역을 깨지 않는다. `CsvRegionCodeResolver`는 현재 코드와 alias가 같은 immutable set을 가리키게 하고, 현재 지역 코드의 두 자리 prefix 집합으로 시·도 등록 여부를 판단한다.

- [ ] **Step 1: 5자리 현재/과거 코드와 2자리 시·도 실패 테스트 작성**

```java
@Test
void 현재와_과거_시군구코드는_같은_동등코드_집합으로_해석한다() {
    CsvRegionCodeResolver resolver = resolverWithContentsAndAliases(
            HEADER + "\n12210,전남광주통합특별시,동구,전남광주통합특별시 동구",
            ALIAS_HEADER + "\n29110,12210"
    );

    Set<String> expected = Set.of("12210", "29110");
    assertEquals(expected, resolver.equivalentCodes("12210").orElseThrow());
    assertEquals(expected, resolver.equivalentCodes("29110").orElseThrow());
}

@Test
void 정본_지역에_존재하는_시도_prefix만_등록된_시도코드다() {
    CsvRegionCodeResolver resolver = resolver("11140,서울특별시,중구,서울특별시 중구");

    assertTrue(resolver.isRegisteredProvinceCode("11"));
    assertFalse(resolver.isRegisteredProvinceCode("99"));
}
```

- [ ] **Step 2: Resolver 테스트를 실행해 RED 확인**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.region.repository.CsvRegionCodeResolverTest'`

Expected: 새 method의 기본값 때문에 동등 코드와 등록 시·도 assertion이 실패한다.

- [ ] **Step 3: immutable 동등 코드·시도 코드 index 구현**

`CsvRegionCodeResolver` 생성 시 다음 세 자료구조를 한 번만 만든다.

```java
private final Map<String, Set<String>> equivalentRegionCodes;
private final Set<String> registeredProvinceCodes;

private static Set<String> buildRegisteredProvinceCodes(Set<String> regionCodes) {
    return regionCodes.stream()
            .map(regionCode -> regionCode.substring(0, 2))
            .collect(Collectors.toUnmodifiableSet());
}
```

`equivalentCodes`는 null·형식 오류·미등록 코드에 `Optional.empty()`, canonical과 legacy 양쪽에는 같은 `Set<String>`을 반환한다. `isRegisteredProvinceCode`는 정확히 두 자리 숫자이고 `registeredProvinceCodes`에 있을 때만 true다.

- [ ] **Step 4: 지역 오류 advice 실패 테스트 작성**

```java
assertError(
        advice.handleInvalidRegionCode(new InvalidRegionCodeException(), new MockHttpServletRequest()),
        BAD_REQUEST,
        "INVALID_REGION_CODE"
);
```

`InvalidRegionCodeException`의 공개 메시지는 `지역 코드를 확인해 주세요.`로 고정한다.

- [ ] **Step 5: 오류 테스트 RED 후 handler 구현**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.controller.HousingComplexExceptionAdviceTest'`

```java
@ExceptionHandler(InvalidRegionCodeException.class)
public ResponseEntity<ErrorResponse> handleInvalidRegionCode(
        InvalidRegionCodeException exception,
        HttpServletRequest request
) {
    return response(HttpStatus.BAD_REQUEST, "INVALID_REGION_CODE", exception.getMessage(), request);
}
```

- [ ] **Step 6: Task 1 검증 및 커밋**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.region.repository.CsvRegionCodeResolverTest' --tests 'com.toadzip.backend.housing.controller.HousingComplexExceptionAdviceTest'`

```bash
git add backend/src/main/java/com/toadzip/backend/region/repository \
  backend/src/main/java/com/toadzip/backend/housing/exception/InvalidRegionCodeException.java \
  backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexExceptionAdvice.java \
  backend/src/test/java/com/toadzip/backend/region/repository/CsvRegionCodeResolverTest.java \
  backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexExceptionAdviceTest.java
git commit -m "feat(housing): 단지 검색 지역 코드 검증 추가 (#23)"
```

---

### Task 2: 검색 요청·조건·정렬 및 v2 cursor 모델

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/request/HousingComplexSearchRequest.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/domain/ComplexSort.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/HousingComplexSearchCondition.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryCursor.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexCursorCodec.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexCursorCodecTest.java`

**Public request shape:**

```java
public record HousingComplexSearchRequest(
        String keyword,
        String regionCode,
        List<RentalType> rentalTypes,
        List<ApplicationStatus> applicationStatuses,
        List<AgencyCode> agencyCodes,
        List<RecruitmentType> recruitmentTypes,
        Long minDeposit,
        Long maxDeposit,
        Long minMonthlyRent,
        Long maxMonthlyRent,
        BigDecimal minExclusiveArea,
        BigDecimal maxExclusiveArea,
        Integer builtYearFrom,
        Integer builtYearTo,
        Boolean hasElevator,
        @Parameter(required = true) BigDecimal southWestLat,
        @Parameter(required = true) BigDecimal southWestLng,
        @Parameter(required = true) BigDecimal northEastLat,
        @Parameter(required = true) BigDecimal northEastLng
) {
}
```

음수·년도·교차 범위는 Service에서 `INVALID_REQUEST`로 처리해야 하므로 `@Min`, `@DecimalMin`, `@NotNull`을 이 DTO에 붙이지 않는다. `@Valid @ModelAttribute`의 binding/type 오류만 기존 global advice의 `VALIDATION_FAILED`로 보낸다.

**Repository condition:**

```java
public record HousingComplexSearchCondition(
        MapBounds bounds,
        String keyword,
        String provinceCode,
        Set<String> cityCountyDistrictCodes,
        Set<RentalType> rentalTypes,
        Set<ApplicationStatus> applicationStatuses,
        Set<AgencyCode> agencyCodes,
        Set<RecruitmentType> recruitmentTypes,
        BigDecimal minDeposit,
        BigDecimal maxDeposit,
        BigDecimal minMonthlyRent,
        BigDecimal maxMonthlyRent,
        BigDecimal minExclusiveArea,
        BigDecimal maxExclusiveArea,
        Integer builtYearFrom,
        Integer builtYearTo,
        Boolean hasElevator,
        LocalDate today
) {
    public HousingComplexSearchCondition {
        cityCountyDistrictCodes = Set.copyOf(cityCountyDistrictCodes);
        rentalTypes = Set.copyOf(rentalTypes);
        applicationStatuses = Set.copyOf(applicationStatuses);
        agencyCodes = Set.copyOf(agencyCodes);
        recruitmentTypes = Set.copyOf(recruitmentTypes);
    }
}
```

Request의 Long 금액은 Service에서 `BigDecimal.valueOf`로 변환해 DB numeric 타입과 맞춘다.

**Sort/cursor contracts:**

```java
public enum ComplexSort {
    LATEST_ANNOUNCEMENT,
    DEPOSIT_ASC,
    MONTHLY_RENT_ASC,
    AREA_DESC,
    COMPLETION_DATE_DESC
}

public record ComplexSummaryCursor(
        ComplexSort sort,
        SortValue primaryValue,
        long complexId
) {
    public sealed interface SortValue permits DateValue, DecimalValue {
        Object jdbcValue();
        String encodedValue();
    }

    public record DateValue(LocalDate value) implements SortValue {
        public DateValue {
            Objects.requireNonNull(value);
        }

        @Override
        public Object jdbcValue() {
            return value;
        }

        @Override
        public String encodedValue() {
            return value.toString();
        }
    }

    public record DecimalValue(BigDecimal value) implements SortValue {
        public DecimalValue {
            Objects.requireNonNull(value);
        }

        @Override
        public Object jdbcValue() {
            return value;
        }

        @Override
        public String encodedValue() {
            return value.toPlainString();
        }
    }
}
```

`primaryValue == null`은 null 정렬값을 뜻한다. v2의 원문 payload는 정확히 `v2|SORT|0-or-1|value-or-~|complexId`이고 URL-safe unpadded Base64로 감싼다. null marker `1`이면 value는 반드시 `~`, marker `0`이면 값이 반드시 존재해야 한다.

- [ ] **Step 0: 컴파일 전용 타입과 compatibility shell 추가**

먼저 `ComplexSort`, request/condition record, 최종 cursor nested value type 및 새 codec overload의 signature를
선언한다. 새 `encode(ComplexSummaryCursor)`/`decode(String, ComplexSort)` shell은 고정된 잘못된 값 반환 또는
`InvalidComplexCursorException`만 수행하며 요청 동작을 구현하지 않는다. 기존 #21 코드가 계속 컴파일되도록
`ComplexSummaryCursor(LocalDate, long)` constructor/`postedDate()` accessor와 v1 codec overload는 유지한다.
이 단계의 컴파일 성공은 RED 근거가 아니며 별도 commit하지 않는다.

- [ ] **Step 1: 다섯 정렬, null, v1 호환의 cursor 실패 테스트 작성**

```java
@ParameterizedTest
@MethodSource("typedCursors")
void v2_커서는_정렬과_typed_value와_ID를_왕복한다(ComplexSummaryCursor expected) {
    String encoded = codec.encode(expected);

    assertEquals(expected, codec.decode(encoded, expected.sort()));
}

@Test
void 요청_sort와_커서_sort가_다르면_거부한다() {
    String cursor = codec.encode(new ComplexSummaryCursor(
            ComplexSort.DEPOSIT_ASC,
            new ComplexSummaryCursor.DecimalValue(new BigDecimal("50000000")),
            41L
    ));

    assertThrows(
            InvalidComplexCursorException.class,
            () -> codec.decode(cursor, ComplexSort.AREA_DESC)
    );
}

@Test
void v1은_최신공고_정렬에서만_해석한다() {
    String v1 = legacyCursor("2026-08-27", 41L);

    assertEquals(ComplexSort.LATEST_ANNOUNCEMENT, codec.decode(v1, ComplexSort.LATEST_ANNOUNCEMENT).sort());
    assertThrows(InvalidComplexCursorException.class, () -> codec.decode(v1, ComplexSort.DEPOSIT_ASC));
}
```

`typedCursors`에는 두 date sort, 세 decimal sort 및 각 타입의 null 값 cursor를 포함한다. malformed Base64, 잘못된 version/field count/null marker, 날짜 자리에 decimal, decimal 자리에 날짜, 0 이하 ID도 별도 parameterized test로 고정한다.
기존 test의 v1 round-trip/null/bad-input도 old codec overload를 더는 호출하지 않게 이관한다. v1 token은 test
helper가 payload를 직접 Base64 encoding하고, assertion은 `decode(raw, LATEST_ANNOUNCEMENT)`가 반환한
`ComplexSummaryCursor`를 사용한다. Task 2 종료 시 test source에는 `encode(LocalDate, long)` 또는
`decode(String)` 호출이 남지 않는다.

- [ ] **Step 2: Codec 테스트 RED 확인**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.service.HousingComplexCursorCodecTest'`

Expected: 기존 v1 전용 codec에는 새 encode/decode 계약이 없어 compile-only shell을 추가한 뒤 round-trip과 검증 assertion이 실패한다. 컴파일 실패를 RED 근거로 삼지 않는다.

- [ ] **Step 3: typed v2 encode/decode와 v1 reader 구현**

```java
public String encode(ComplexSummaryCursor cursor);

public ComplexSummaryCursor decode(String rawCursor, ComplexSort requestedSort);
```

- `LATEST_ANNOUNCEMENT`, `COMPLETION_DATE_DESC`는 `LocalDate.parse`로 검증한다.
- `DEPOSIT_ASC`, `MONTHLY_RENT_ASC`, `AREA_DESC`는 `new BigDecimal(value)`로 검증하고 음수는 거부한다.
- null 정렬값의 내부 표현은 오직 `primaryValue == null`이다. v1의 `~`는 primaryValue가 null인
  `LATEST_ANNOUNCEMENT` cursor로, 날짜 token은 non-null `DateValue`로 변환한다.
- encode도 `complexId > 0` 및 sort와 SortValue 타입의 조합을 검증한다. date sort에
  `DecimalValue`, decimal sort에 `DateValue`가 들어오면 발급 전에 `InvalidComplexCursorException`으로 거부한다.
- decode 과정의 `IllegalArgumentException`, `DateTimeParseException`, `ArithmeticException`은 모두 public detail을 노출하지 않는 `InvalidComplexCursorException`으로 변환한다.
- Task 2 commit도 전체 production compile이 가능해야 한다. Task 3 전까지 `ComplexSummaryCursor(LocalDate, long)`
  compatibility constructor와 `postedDate()` accessor, 현재 codec의 `encode(LocalDate, long)` 및
  `decode(String)`/legacy nested result를 명시적으로 유지한다. 이 adapter는 기존 #21 경로만 위한 것이며
  Task 3에서 Service/Repository를 최종 계약으로 전환한 직후 모두 제거한다.

- [ ] **Step 4: Task 2 검증 및 커밋**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.service.HousingComplexCursorCodecTest'`

```bash
git add backend/src/main/java/com/toadzip/backend/housing/dto/request \
  backend/src/main/java/com/toadzip/backend/housing/domain/ComplexSort.java \
  backend/src/main/java/com/toadzip/backend/housing/repository/HousingComplexSearchCondition.java \
  backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryCursor.java \
  backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexCursorCodec.java \
  backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexCursorCodecTest.java
git commit -m "feat(housing): 단지 검색 조건과 v2 커서 계약 추가 (#23)"
```

---

### Task 3: Controller binding과 Service 정규화 경계

**Files:**
- Modify: `backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexController.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexQueryService.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummarySqlQuery.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummarySqlBuilder.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepository.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexListQueryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexMapQueryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexDetailQueryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java`

**Final public flow:**

```java
// Controller
public ApiResponse<HousingComplexListResponse> getComplexes(
        @Valid @ModelAttribute HousingComplexSearchRequest request,
        @RequestParam(defaultValue = "LATEST_ANNOUNCEMENT") ComplexSort sort,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int size
);

public ApiResponse<HousingComplexMapResponse> getComplexesForMap(
        @Valid @ModelAttribute HousingComplexSearchRequest request
);

// Service
public HousingComplexListResponse getComplexes(
        HousingComplexSearchRequest request,
        ComplexSort sort,
        String cursor,
        int size
);

public HousingComplexMapResponse getComplexesForMap(HousingComplexSearchRequest request);

// Repository
public List<ComplexSummaryRow> findPage(
        HousingComplexSearchCondition condition,
        ComplexSort sort,
        ComplexSummaryCursor cursor,
        int limit
);

public List<ComplexSummaryRow> findAll(HousingComplexSearchCondition condition);
```

- [ ] **Step 0: 새 계층 signature의 컴파일 전용 shell 추가**

Controller test 전에 Service의 최종 두 overload와 Repository의 `findPage`/`findAll`,
`ComplexSummarySqlQuery`/`ComplexSummarySqlBuilder`의 최종 signature를 먼저 선언한다. 새 Service method는 빈 response,
새 Repository method는 empty list, builder는 고정 SQL/query object를 반환하게 해 production과 test source를
컴파일시킨다. 기존 #21 method는 이 Task의 Step 6까지 유지한다. shell은 별도 commit하지 않으며 다음 테스트가
mock verification/condition assertion으로 실패해야 RED다.

- [ ] **Step 1: Controller 반복 query binding과 기본값 실패 테스트 작성**

목록 테스트는 모든 query를 한 번에 보내고 `ArgumentCaptor<HousingComplexSearchRequest>`로 각 필드를 검증한다. 반복 enum은 같은 key를 두 번 사용한다.

```java
mockMvc.perform(get("/api/v1/complexes")
                .param("keyword", " 행복 단지 ")
                .param("regionCode", "11140")
                .param("rentalTypes", "HAPPY_HOUSING", "NATIONAL_RENTAL")
                .param("applicationStatuses", "APPLYING", "CLOSED")
                .param("agencyCodes", "LH", "SH")
                .param("recruitmentTypes", "NEW", "WAITLIST")
                .param("minDeposit", "10000000")
                .param("maxDeposit", "70000000")
                .param("minMonthlyRent", "100000")
                .param("maxMonthlyRent", "300000")
                .param("minExclusiveArea", "36.12")
                .param("maxExclusiveArea", "44.87")
                .param("builtYearFrom", "2018")
                .param("builtYearTo", "2026")
                .param("hasElevator", "true")
                .param("sort", "DEPOSIT_ASC")
                .param("cursor", "next-cursor")
                .param("size", "7")
                .param("southWestLat", "37.4")
                .param("southWestLng", "126.8")
                .param("northEastLat", "37.6")
                .param("northEastLng", "127.1"))
        .andExpect(status().isOk());

verify(queryService).getComplexes(
        requestCaptor.capture(),
        eq(ComplexSort.DEPOSIT_ASC),
        eq("next-cursor"),
        eq(7)
);
assertThat(requestCaptor.getValue().rentalTypes())
        .containsExactly(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL);
```

이 테스트에서 request record의 15개 공통 filter component와 네 bounds를 모두 assert한다. 별도 테스트로 목록
생략값이 `LATEST_ANNOUNCEMENT`, null cursor, size 20인지 고정한다. 지도 endpoint에도 같은 15개 공통 filter와
네 bounds를 보내 captor의 모든 component가 동일하게 binding되고 Service에는 request만 전달되는지 검증한다.
malformed enum/Long/Decimal/Boolean은 `VALIDATION_FAILED`와 해당 field를 반환해야 한다.

- [ ] **Step 2: Controller 테스트 RED 후 최종 binding signature 구현**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.controller.HousingComplexControllerTest'`

`MapBounds.of` 호출을 Controller에서 제거하고, Service가 request의 네 좌표로 bounds를 만든다. 상세 `GET /{complexId}`는 수정하지 않는다.

- [ ] **Step 3: Service 정규화·검증 실패 테스트 작성**

`HousingComplexListQueryTest`와 `HousingComplexMapQueryTest`는 고정 Seoul Clock과 mock repository를 사용한다.

```java
ArgumentCaptor<HousingComplexSearchCondition> conditionCaptor =
        ArgumentCaptor.forClass(HousingComplexSearchCondition.class);

service.getComplexes(request, ComplexSort.LATEST_ANNOUNCEMENT, null, 20);

verify(repository).findPage(
        conditionCaptor.capture(),
        eq(ComplexSort.LATEST_ANNOUNCEMENT),
        isNull(),
        eq(21)
);
HousingComplexSearchCondition condition = conditionCaptor.getValue();
assertAll(
        () -> assertEquals("행복 단지", condition.keyword()),
        () -> assertEquals("11", condition.provinceCode()),
        () -> assertEquals(LocalDate.of(2026, 8, 27), condition.today())
);
```

각 실패는 repository 호출 전 발생하도록 `verifyNoInteractions(repository)`를 함께 검증한다.

- 공백 keyword
- 음수 금액·면적
- 각 min > max
- year가 1..9999 밖이거나 from > to
- list 안의 null element
- `applicationStatuses`의 `CANCELLED`
- 공백/형식 오류/미등록 2자리/미해결 5자리 region
- size 0 또는 51
- 누락·범위초과·역전 bounds

Controller/API 경계에는 `agencyCodes=LH&agencyCodes=`처럼 빈 enum element가 포함된 요청도 추가한다. DTO element에
`@NotNull`을 붙이지 않고 Spring binding 결과의 null element를 Service가 감지해 `INVALID_REQUEST`로 변환하는지
고정한다. 임의 enum 문자열(`agencyCodes=UNKNOWN`)의 conversion 실패는 별도 `VALIDATION_FAILED` 테스트다.

지역 성공 fixture는 `11 -> provinceCode="11"`, `12210/29110 -> cityCountyDistrictCodes={"12210","29110"}` 두 경로를 각각 검증한다.

- [ ] **Step 4: Service 테스트 RED 후 condition 조립 구현**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.service.HousingComplexListQueryTest' --tests 'com.toadzip.backend.housing.service.HousingComplexMapQueryTest'`

Service 내부 순서를 고정한다.

```text
request null 확인
  -> MapBounds.of
  -> size/sort 정규화(목록)
  -> keyword trim 및 빈 값 거부
  -> list null/empty를 empty immutable Set으로 변환
  -> CANCELLED 및 숫자/년도/교차 범위 검증
  -> 2자리 province 또는 5자리 equivalent district 해석
  -> Long 금액을 BigDecimal로 변환
  -> Asia/Seoul today 포함 condition 생성
  -> cursor decode
  -> repository 호출
```

Service에 private `RegionSelection(String provinceCode, Set<String> districtCodes)` record를 두어 두 지역 분기를 동시에 채우지 않게 한다. `sort == null`은 `LATEST_ANNOUNCEMENT`로 정규화한다. map과 list는 같은 `searchCondition(request)`를 호출한다.
요청 의미 검증과 지역 해석이 모두 성공한 뒤 cursor를 decode한다. filter/region과 cursor가 동시에
잘못된 요청은 이 순서에 따라 `INVALID_REQUEST` 또는 `INVALID_REGION_CODE`가 `INVALID_CURSOR`보다 우선하며,
Service test 하나로 이 우선순위를 고정한다.

`HousingComplexQueryService`의 Spring constructor에 `RegionCodeResolver`를 추가하고 목록·지도 test의
package-private constructor도 명시적으로 resolver를 받게 갱신한다. 상세 조회 동작은 resolver를 사용하지
않지만 `HousingComplexDetailQueryTest`가 production constructor를 직접 호출하므로 no-op resolver를 추가해
생성자 컴파일과 기존 상세 assertion을 보존한다.

- [ ] **Step 5: Query object/builder shell과 Repository 최종 실행 경계 구현**

```java
record ComplexSummarySqlQuery(String sql, Map<String, Object> parameters) {
    ComplexSummarySqlQuery {
        parameters = Map.copyOf(parameters);
    }
}

@Component
final class ComplexSummarySqlBuilder {
    ComplexSummarySqlQuery buildMapQuery(HousingComplexSearchCondition condition);

    ComplexSummarySqlQuery buildListQuery(
            HousingComplexSearchCondition condition,
            ComplexSort sort,
            ComplexSummaryCursor cursor,
            int limit
    );
}
```

Task 3에서는 기존 CTE/projection/bounds, 지도 `complex_id ASC`, 기본 최신공고 sort/keyset 동작을 builder로 옮긴다. Repository는 SQL을 만들지 않고 다음 방식으로만 실행·매핑한다.

```java
ComplexSummarySqlQuery query = sqlBuilder.buildMapQuery(condition);
return jdbcClient.sql(query.sql())
        .params(query.parameters())
        .query(this::mapRow)
        .list();
```

기존 Repository 테스트의 호출을 `findAll(noFilters(bounds))`, `findPage(noFilters(bounds), LATEST_ANNOUNCEMENT, cursor, limit)`로 바꾸고 #21의 bounds/대표공고/집계/기본 pagination assertion을 그대로 유지한다. `@Import`에는 `ComplexSummarySqlBuilder.class`도 추가한다.

- [ ] **Step 6: Task 3 회귀 검증 및 old API 제거**

Run:

```bash
cd backend
./gradlew test \
  --tests 'com.toadzip.backend.housing.controller.HousingComplexControllerTest' \
  --tests 'com.toadzip.backend.housing.service.HousingComplexListQueryTest' \
  --tests 'com.toadzip.backend.housing.service.HousingComplexMapQueryTest' \
  --tests 'com.toadzip.backend.housing.service.HousingComplexDetailQueryTest' \
  --tests 'com.toadzip.backend.housing.repository.ComplexSummaryQueryRepositoryTest'
```

`findAllInBounds`, `findFirstPage`, `findPageAfter`, 기존 Service overload와 v1-only codec overload의 참조가 `rg`에서 0건인지 확인한다.

Run: `rg 'findAllInBounds|findFirstPage|findPageAfter|encode\(.*postedDate|decode\(cursor\)' backend/src/main backend/src/test`

- [ ] **Step 7: Task 3 커밋**

```bash
git add backend/src/main/java/com/toadzip/backend/housing \
  backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java \
  backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexListQueryTest.java \
  backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexMapQueryTest.java \
  backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexDetailQueryTest.java \
  backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java
git commit -m "feat(housing): 단지 검색 요청과 조회 조건 연결 (#23)"
```

---

### Task 4: 모든 filter predicate와 same-target 의미 구현

**Files:**
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummarySqlBuilder.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepository.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java`

**SQL invariant:** `latest_leaf`, `representative`, `area_range`, `price_range` CTE 및 응답 projection은 필터와 무관하게 먼저 계산한다. `representative` CTE에는 최종 검색을 위해 `announcement.recruitment_type`을 projection하되 recruitment/application filter를 CTE 내부에 넣지 않는다.

- [ ] **Step 1: keyword·지역·직접 단지 filter PostgreSQL 실패 테스트 작성**

각 테스트는 한 동작만 검증한다. 동일한 `HousingComplexSearchCondition`을 `findAll(condition)`과
`findPage(condition, LATEST_ANNOUNCEMENT, null, 충분한_limit)` 양쪽에 전달하고 기대 ID를 모두 assert해
지도/list query 중 한쪽에서 predicate가 누락되는 회귀를 막는다.

- 단지명 또는 `road_address`의 대소문자 무시 부분 일치
- `%`, `_`, `\`가 wildcard가 아닌 literal인 fixture
- 2자리 `province_code`, 5자리 canonical/legacy district set
- `RentalType`, `AgencyCode`의 enum name 및 한글 legacy 저장값
- `completion_date` year 양끝 포함
- `elevator_installed` true/false 정확 일치
- 같은 enum 그룹 OR, 서로 다른 그룹과 bounds AND, 최종 단지 ID 중복 없음

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.repository.ComplexSummaryQueryRepositoryTest'`

Expected: 새 조건을 전달해도 기존 builder가 bounds만 적용하므로 포함/제외 ID assertion이 실패한다.

- [ ] **Step 2: 직접 predicate와 parameter escaping 구현**

사용자 문자열은 SQL에 이어 붙이지 않는다. keyword parameter만 다음처럼 만든다.

```java
private String keywordPattern(String keyword) {
    String escaped = keyword.toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    return "%" + escaped + "%";
}
```

```sql
AND (
    LOWER(housing_complex.name) LIKE :keywordPattern ESCAPE '\'
    OR LOWER(housing_complex.road_address) LIKE :keywordPattern ESCAPE '\'
)
AND housing_complex.province_code = :provinceCode
AND housing_complex.city_county_district_code IN (:districtCodes)
AND housing_complex.supply_type IN (:rentalTypeValues)
AND housing_complex.provider IN (:agencyCodeValues)
AND EXTRACT(YEAR FROM housing_complex.completion_date) >= :builtYearFrom
AND EXTRACT(YEAR FROM housing_complex.completion_date) <= :builtYearTo
AND housing_complex.elevator_installed = :hasElevator
```

각 fragment는 해당 조건이 있을 때만 추가한다. enum parameter는 `LegacyStoredValue.storedValues()`를 flatten한 immutable 문자열 set이다. SQL expression, operator, parameter name은 builder의 고정 문자열만 사용한다.

- [ ] **Step 3: 대표 공고 recruitment/application status 실패 테스트 작성**

- `RecruitmentType` canonical/legacy 저장값
- `BEFORE_APPLICATION`: start > today
- `APPLYING`: start <= today <= end, 양끝 날짜 포함
- `CLOSED`: end < today
- 복수 status OR
- 대표 공고 filter가 있을 때 대표 공고 없는 단지 제외
- 조건에 맞는 과거 공고가 있어도 최신 대표 공고가 불일치하면 제외. 이 fixture는 `findAll`과 `findPage`
  양쪽에서 해당 complex ID가 없음을 assert한다.

- [ ] **Step 4: 대표 공고 predicate 구현**

```sql
AND representative.recruitment_type IN (:recruitmentTypeValues)
AND representative.announcement_id IS NOT NULL
AND (
    representative.application_start_date > :today
    OR (
        representative.application_start_date <= :today
        AND representative.application_end_date >= :today
    )
    OR representative.application_end_date < :today
)
```

위 세 status clause 중 요청된 값만 OR 그룹에 넣는다. `CANCELLED`는 Service에서 이미 거부되어 builder에 도달하지 않는다.

- [ ] **Step 5: 면적 gap과 동일 공급대상 실패 테스트 작성**

```java
@Test
void 면적_범위는_실제_주택형_한_개가_양끝을_만족해야_한다() {
    // 20㎡와 60㎡만 있는 단지에 min=30, max=50을 주면 aggregate range는 겹쳐도 제외한다.
}

@Test
void 보증금과_월세는_같은_공급대상이_동시에_만족해야_한다() {
    // target A만 보증금, target B만 월세를 만족하는 단지는 제외한다.
}

@Test
void 가격과_면적은_같은_공급행의_주택형에서_만족해야_한다() {
    // 한 supply row는 가격만, 다른 row의 housing type은 면적만 만족하면 제외한다.
}
```

null 가격이 숫자 filter와 일치하지 않는 경우, filter가 있어도 response area/price range가 전체 집계값으로 유지되는 경우도 별도 테스트로 둔다.

- [ ] **Step 6: area-only와 price(+area) 상관 EXISTS 구현**

면적-only:

```sql
AND EXISTS (
    SELECT 1
    FROM housing_types housing_type
    WHERE housing_type.housing_complex_id = housing_complex.id
      AND housing_type.exclusive_area >= :minExclusiveArea
      AND housing_type.exclusive_area <= :maxExclusiveArea
)
```

전달되지 않은 면적 bound의 비교문은 생략한다.

가격 조건이 하나라도 있는 경우:

```sql
AND EXISTS (
    SELECT 1
    FROM supply_rows matched_supply_row
    JOIN supply_targets matched_supply_target
      ON matched_supply_target.supply_row_id = matched_supply_row.id
    LEFT JOIN housing_types matched_housing_type
      ON matched_housing_type.id = matched_supply_row.housing_type_id
    WHERE matched_supply_row.housing_complex_id = housing_complex.id
      AND matched_supply_row.announcement_id = representative.announcement_id
      AND matched_supply_target.rental_deposit >= :minDeposit
      AND matched_supply_target.rental_deposit <= :maxDeposit
      AND matched_supply_target.monthly_rent >= :minMonthlyRent
      AND matched_supply_target.monthly_rent <= :maxMonthlyRent
      AND matched_housing_type.exclusive_area >= :minExclusiveArea
      AND matched_housing_type.exclusive_area <= :maxExclusiveArea
)
```

전달되지 않은 bound의 비교문은 생략한다. 가격-only일 때 housing type 존재를 요구하지 않으며, 가격+면적일 때만 `matched_housing_type` 비교가 참이어야 한다. final SELECT에는 collection join을 추가하지 않는다.

- [ ] **Step 7: 전체 filter Repository 테스트 통과 및 커밋**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.housing.repository.ComplexSummaryQueryRepositoryTest'`

```bash
git add backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummarySqlBuilder.java \
  backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepository.java \
  backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java
git commit -m "feat(housing): 단지 복합 검색 조건 적용 (#23)"
```

---

### Task 5: 다섯 정렬의 keyset pagination 완성

**Files:**
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryRow.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummarySqlBuilder.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepository.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexQueryService.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexListQueryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexMapQueryTest.java`

`ComplexSummaryRow`의 마지막 필드에 HTTP 응답에는 노출하지 않는 `LocalDate completionDate`를 추가하고 SELECT/mapRow에도 `housing_complex.completion_date`를 넣는다. 기존 mapper는 이 값을 무시한다.

**Whitelisted sort table:**

| Sort | SQL expression | Direction | Cursor value |
|---|---|---|---|
| `LATEST_ANNOUNCEMENT` | `representative.posted_date` | DESC | `DateValue` |
| `DEPOSIT_ASC` | `price_range.deposit_min` | ASC | `DecimalValue` |
| `MONTHLY_RENT_ASC` | `price_range.monthly_rent_min` | ASC | `DecimalValue` |
| `AREA_DESC` | `area_range.exclusive_area_max` | DESC | `DecimalValue` |
| `COMPLETION_DATE_DESC` | `housing_complex.completion_date` | DESC | `DateValue` |

- [ ] **Step 1: 정렬별 null-last/tie-breaker 실패 테스트 작성**

`LATEST_ANNOUNCEMENT`, `DEPOSIT_ASC`, `MONTHLY_RENT_ASC`, `AREA_DESC`에는 non-null 동률 두 단지와
null 한 단지를 만들고 null-last 및 전체 ID 순서를 검증한다. `completion_date`는 Entity/DB에서
NOT NULL이므로 `COMPLETION_DATE_DESC`에는 null fixture를 만들지 않고 동률의 ID DESC만 검증한다.
모든 sort는 두 페이지 keyset 검증 대상이다.

- [ ] **Step 2: 고정 SortSpec과 ORDER BY 구현**

```java
private record SortSpec(String expression, Direction direction) {
}

private SortSpec sortSpec(ComplexSort sort) {
    return switch (sort) {
        case LATEST_ANNOUNCEMENT -> new SortSpec("representative.posted_date", Direction.DESC);
        case DEPOSIT_ASC -> new SortSpec("price_range.deposit_min", Direction.ASC);
        case MONTHLY_RENT_ASC -> new SortSpec("price_range.monthly_rent_min", Direction.ASC);
        case AREA_DESC -> new SortSpec("area_range.exclusive_area_max", Direction.DESC);
        case COMPLETION_DATE_DESC -> new SortSpec("housing_complex.completion_date", Direction.DESC);
    };
}
```

ORDER BY는 builder 내부의 이 table만 사용해 `<expression> <direction> NULLS LAST, housing_complex.id DESC`를 만든다. request 문자열을 SQL fragment로 사용하지 않는다.

- [ ] **Step 3: 정렬별 두 페이지 중복·누락 실패 테스트 작성**

각 sort에서 size 2의 first page와 마지막 row cursor 이후 second page를 조회한다. 두 page ID intersection은 empty이고 union은 필터 결과 전체 ID와 같아야 한다. non-null cursor와 null cursor를 모두 지나도록 fixture를 구성한다.

- [ ] **Step 4: 방향·null별 keyset predicate 구현**

non-null cursor의 DESC:

```sql
AND (
    sort_expression < :cursorValue
    OR (sort_expression = :cursorValue AND housing_complex.id < :cursorComplexId)
    OR sort_expression IS NULL
)
```

ASC는 첫 비교만 `>`로 바꾼다. null cursor는 다음 조건만 사용한다.

```sql
AND sort_expression IS NULL
AND housing_complex.id < :cursorComplexId
```

`cursor.sort() != requested sort`와 SortValue 타입 불일치는 Repository 진입 전에 codec/Service에서 `INVALID_CURSOR`다. cursor value와 ID 및 limit는 named parameter다.

- [ ] **Step 5: Service가 row별 typed next cursor를 발급하는 테스트 작성·구현**

```java
private ComplexSummaryCursor cursorOf(ComplexSummaryRow row, ComplexSort sort) {
    return switch (sort) {
        case LATEST_ANNOUNCEMENT -> dateCursor(sort, row.postedDate(), row.complexId());
        case DEPOSIT_ASC -> decimalCursor(sort, row.depositMin(), row.complexId());
        case MONTHLY_RENT_ASC -> decimalCursor(sort, row.monthlyRentMin(), row.complexId());
        case AREA_DESC -> decimalCursor(sort, row.exclusiveAreaMax(), row.complexId());
        case COMPLETION_DATE_DESC -> dateCursor(sort, row.completionDate(), row.complexId());
    };
}
```

`size + 1`, `hasNext`, 실제 page 마지막 row 기준 cursor 발급은 기존 #21 동작을 유지한다. next page 요청은 같은 sort를 codec decode에 넘긴다. v1 cursor + default/latest 성공, v1 + 다른 sort 실패, v2 sort mismatch 실패를 Service test로 고정한다.

- [ ] **Step 6: 지도는 sort/cursor 변경과 무관함을 회귀 검증**

`HousingComplexMapQueryTest`에서 같은 condition으로 `repository.findAll(condition)`만 호출하고 map item을 `complex_id ASC`로 유지하는지 확인한다. 지도 응답에는 `nextCursor`와 `hasNext`가 생기지 않는다.

- [ ] **Step 7: Task 5 검증 및 커밋**

Run:

```bash
cd backend
./gradlew test \
  --tests 'com.toadzip.backend.housing.service.HousingComplexCursorCodecTest' \
  --tests 'com.toadzip.backend.housing.repository.ComplexSummaryQueryRepositoryTest' \
  --tests 'com.toadzip.backend.housing.service.HousingComplexListQueryTest' \
  --tests 'com.toadzip.backend.housing.service.HousingComplexMapQueryTest'
```

```bash
git add backend/src/main/java/com/toadzip/backend/housing/repository \
  backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexQueryService.java \
  backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java \
  backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexListQueryTest.java \
  backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexMapQueryTest.java
git commit -m "feat(housing): 단지 검색 정렬과 keyset 커서 적용 (#23)"
```

---

### Task 6: HTTP 통합·OpenAPI·회귀 검증

**Files:**
- Modify: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/global/config/OpenApiDocumentationIntegrationTest.java`

- [ ] **Step 1: 목록/지도 filter parity 통합 테스트 작성**

실제 PostgreSQL fixture에 포함/제외 단지를 만들고 동일한 전체 filter query를 두 endpoint에 보낸다.

```java
@Test
void 같은_검색조건의_목록과_지도는_같은_단지_ID_집합을_반환한다() throws Exception {
    Set<Long> listIds = fetchEveryListPageWithAllFilters();
    Set<Long> mapIds = fetchMapWithAllFilters();

    assertEquals(mapIds, listIds);
}
```

query에는 keyword, 5자리 alias region, 반복 enum, application status, agency/recruitment, 네 가격 bound, 두 면적 bound, 두 built year, elevator, bounds를 모두 포함한다. map JSON 순서는 ID ASC로 별도 확인한다.

- [ ] **Step 2: 다섯 sort의 실제 HTTP 두 페이지 검증**

`@EnumSource(ComplexSort.class)` 또는 sort별 parameter source로 first/second page를 호출한다. 각 다음 요청은 first response의 cursor와 같은 filter/sort를 다시 전달한다. 모든 sort에는 동률을 포함하고, null이 가능한 네 sort에는 null도 포함한 fixture를 사용해 page 간 중복이 없고 전체 expected ID를 빠짐없이 합치는지 검증한다. v1 latest cursor 요청 후 응답 cursor를 decode했을 때 v2인지도 고정한다.

- [ ] **Step 3: 오류 HTTP 계약 검증**

각 오류 응답은 `code`, `message`, nonblank `traceId`를 가지며 SQL/exception class/stack trace를 노출하지 않는다.

| 요청 | 기대 code |
|---|---|
| blank keyword, 음수 금액·면적, 역전 범위, 잘못된 year, `CANCELLED` | `INVALID_REQUEST` |
| 공백·형식 오류·미등록 `regionCode` | `INVALID_REGION_CODE` |
| malformed enum/number/decimal/boolean | `VALIDATION_FAILED` + field |
| malformed/typed-value 오류/sort 불일치 cursor | `INVALID_CURSOR` |
| 누락·범위초과·역전 bounds | `INVALID_MAP_BOUNDS` |

`agencyCodes=LH&agencyCodes=` 같은 빈 enum element는 `INVALID_REQUEST`로 별도 행위를 고정한다.

- [ ] **Step 4: 기존 #21 공개 계약 회귀 확인**

- query filter가 없는 목록은 `LATEST_ANNOUNCEMENT`, size 20이며 기존 응답 key가 그대로다.
- filter가 없는 지도는 bounds 안의 전체 핀을 ID ASC로 반환한다.
- 대표 correction/cancellation 처리와 response price/area 집계가 유지된다.
- `GET /api/v1/complexes/{complexId}` 상세 테스트는 수정 없이 통과한다.

- [ ] **Step 5: OpenAPI parameter 계약 확장**

`OpenApiDocumentationIntegrationTest`에서 `/v3/api-docs`의 두 path가 모든 공통 filter query를 노출하는지 검증한다. 목록에만 `sort`, `cursor`, `size`가 있고 sort enum 다섯 값 및 size 기본값 20이 보이며, map에는 이 세 parameter가 문서화되지 않아야 한다. 기존 네 bounds의 `required=true`와 local profile 활성/비활성 계약을 유지한다.

- [ ] **Step 6: PostgreSQL 기동 후 targeted/full verification**

Run from repository root. Targeted test, full check 또는 harness 중 하나가 실패해도 DB가 정리되도록 전체 검증을
하나의 보호된 subshell에서 실행한다.

```bash
(
  set -e
  cleanup_housing_test_db() {
    docker compose --project-name toadzip-test --file compose.test.yaml \
      down --volumes --remove-orphans
  }
  trap cleanup_housing_test_db EXIT
  docker compose --project-name toadzip-test --file compose.test.yaml \
    up --detach --wait --wait-timeout 60 --force-recreate db
  (
    cd backend
    ./gradlew test \
      --tests 'com.toadzip.backend.housing.*' \
      --tests 'com.toadzip.backend.region.repository.CsvRegionCodeResolverTest' \
      --tests 'com.toadzip.backend.global.config.OpenApiDocumentationIntegrationTest'
    ./gradlew --rerun-tasks check
  )
  sh tests/harness/validate-harness-test.sh
  sh tests/harness/validate-commit-message-test.sh
  sh tests/harness/validate-pr-test.sh
  sh scripts/validate-harness.sh
  git diff --check origin/develop
)
```

Expected: 모든 filter, five-sort cursor, list/map parity, error/OpenAPI, 기존 detail 회귀, 전체 Gradle check,
네 harness/diff 검사가 모두 exit 0이고 compose project가 제거된다.

- [ ] **Step 7: 범위 감사**

Run:

```bash
git diff --name-only origin/develop
git diff --stat origin/develop
git status --short --untracked-files=all
```

다음을 확인한다.

- region controller/response 또는 `/api/v1/regions` 구현 없음 (#63)
- frontend 파일 변경 없음
- Entity/migration/schema/dependency/index 변경 없음
- 응답 DTO key 변경 없음
- 상세 조회 production flow 변경 없음
- 사용자 값이 SQL 문법으로 직접 연결되는 코드 없음

- [ ] **Step 8: 통합 테스트 커밋**

```bash
git add backend/src/test/java/com/toadzip/backend/housing/controller \
  backend/src/test/java/com/toadzip/backend/global/config/OpenApiDocumentationIntegrationTest.java
git commit -m "test(housing): 단지 검색 필터 통합 검증 추가 (#23)"
git diff --check origin/develop...HEAD
```

Step 6의 EXIT trap이 DB를 정리한다.

---

## Completion Checklist

- [ ] 모든 단일 filter가 목록과 지도에서 독립적으로 동작한다.
- [ ] 같은 그룹 OR, 그룹/bounds 간 AND가 실제 PostgreSQL 테스트로 증명된다.
- [ ] area gap, same `SupplyTarget`, same `SupplyRow`/`HousingType`, null 가격 규칙이 증명된다.
- [ ] 대표 공고 filter가 과거 공고를 되살리지 않고 canonical/legacy enum 값을 모두 찾는다.
- [ ] 다섯 sort가 null-last 및 ID tie-breaker로 두 페이지를 중복·누락 없이 잇는다.
- [ ] v1 latest cursor는 읽히고 모든 새 cursor는 v2이며 sort/type 불일치는 거부된다.
- [ ] 같은 조건의 목록 전체 ID와 지도 ID 집합이 일치한다.
- [ ] 정의된 오류 코드와 OpenAPI query 계약이 고정된다.
- [ ] #21 무필터 목록·지도·상세 회귀와 전체 check가 통과한다.
- [ ] #63 백엔드 지역 조회 API와 모든 프런트 구현이 diff에 포함되지 않는다.
