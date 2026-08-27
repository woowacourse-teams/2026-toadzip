# Housing Complex Query MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지도 경계 안의 단지 목록·지도 핀과 좌표를 포함한 단지 상세를 제공하는 세 조회 API를 구현한다.

**Architecture:** `HousingComplexController -> HousingComplexQueryService -> housing.repository` 방향을 유지한다. 복잡한 읽기 쿼리는 PostgreSQL projection repository에 두고, 코드 변환·D-Day·응답 조립은 작은 mapper에 분리해 Entity를 HTTP 경계로 전달하지 않는다.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring MVC, Spring Data JPA, Spring JDBC `JdbcClient`, PostgreSQL 17, JUnit 5, MockMvc

**Spec:** GitHub issue #21 `https://github.com/woowacourse-teams/2026-toadzip/issues/21`; API 명세 `/Users/jeongjaemin/.codex/attachments/b942e748-d422-4da6-a7e7-17512e2cb032/pasted-text.txt` sections 1, 3, 5.1-5.3, 8, 9

## Global Constraints

- `GET /api/v1/complexes`와 `GET /api/v1/complexes/map`은 WGS 84 bounds 네 값을 모두 필수로 받는다.
- 위도는 `-90..90`, 경도는 `-180..180`, `southWestLat < northEastLat`, `southWestLng < northEastLng`이고 경계를 포함한다.
- 목록은 커서 방식이며 `size` 기본값 20, 허용 범위 `1..50`, 기본 정렬은 최신 비취소 leaf 공고 게시일 내림차순과 단지 ID 내림차순이다.
- 지도 조회는 페이지네이션 없이 bounds 안의 모든 단지를 단지 ID 오름차순으로 반환한다.
- 상세 응답의 `address`에는 `latitude`와 `longitude`를 포함한다.
- 선택 검색 필터와 비기본 정렬은 #23 범위이며 구현하지 않는다.
- 기존 Entity 필드와 내부 네이밍은 유지한다. 응답 DTO와 mapper가 API 필드명 및 canonical code로 변환한다.
- 대표 접수 기간은 공고의 전용 `applicationStartDate`/`applicationEndDate`를 사용한다. 일정 행은 선택적이고
  복수일 수 있어 대표 기간으로 추정하지 않으며, 현재 도메인의 `LocalDate` 정밀도 그대로 `YYYY-MM-DD`로 응답한다.
- 알 수 없거나 정밀도가 부족한 값은 추정하지 않고 nullable 필드는 `null`, 배열은 빈 배열로 반환한다.
- API에서 nullable이지만 현재 모델이 필수/primitive로 보존하는 값은 저장된 값을 boxed response로 반환한다;
  이번 조회 작업에서 기존 도메인 nullability를 완화하지 않는다.
- 성공 응답은 `{ "data": ... }`, 오류 응답은 `code`, `message`, `traceId` 계약을 따른다.
- 오류 코드는 `INVALID_MAP_BOUNDS`, `INVALID_CURSOR`, `INVALID_REQUEST`, `COMPLEX_NOT_FOUND`를 사용한다.
- 새 production dependency를 추가하지 않는다.
- 실행계획 근거와 배포 마이그레이션이 없는 인덱스·컬럼 변경은 이번 MVP에 추가하지 않는다.
- `HousingComplexQueryService`의 지도·목록·상세 public 조회 메서드는 모두 `@Transactional(readOnly = true)` 경계다.
- 브랜치는 `origin/develop`을 기준으로 하며 #20 브랜치의 커밋이나 코드에 의존하지 않는다. #20은 패턴 참고 자료로만 사용한다.
- 모든 production 동작은 실패 테스트를 먼저 확인한 뒤 구현한다. 새 타입은 최종 public signature의
  compile-only shell을 먼저 만들 수 있지만, RED는 반드시 컴파일을 통과하고 빠진 행동의 assertion으로 실패해야 한다.

---

### Task 1: 공통 bounds·커서·응답·오류 계약

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/global/response/ApiResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/domain/MapBounds.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexCursorCodec.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/exception/InvalidMapBoundsException.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/exception/InvalidComplexCursorException.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/exception/InvalidComplexRequestException.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/exception/HousingComplexNotFoundException.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexExceptionAdvice.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/domain/MapBoundsTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexCursorCodecTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexExceptionAdviceTest.java`

**Interfaces:**
- Produces: `MapBounds.of(BigDecimal, BigDecimal, BigDecimal, BigDecimal)`
- Produces: `HousingComplexCursorCodec.encode(LocalDate, long)` and
  `HousingComplexCursorCodec.HousingComplexCursor decode(String)`
- Produces: `ApiResponse<T>(T data)` and four fixed feature error mappings

- [ ] **Step 0: Add compile-only public shells**

Create the listed records/classes and final method signatures before writing tests. Shell methods contain no
requested behavior: `MapBounds.of` simply constructs the record, cursor round-trip methods return a fixed
invalid value, and feature handlers return an intentionally wrong empty 500 response. This step exists only
to make RED compile; do not count a compiler failure as evidence and do not commit the shells separately.

- [ ] **Step 1: Write failing bounds tests**

```java
@Test
void 네_좌표가_모두_있고_남서쪽이_북동쪽보다_작으면_경계를_생성한다() {
    MapBounds bounds = MapBounds.of(decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("127.0"));
    assertEquals(decimal("37.0"), bounds.southWestLat());
}

@ParameterizedTest
@MethodSource("invalidBounds")
void 누락_범위초과_역전_좌표를_거부한다(BigDecimal swLat, BigDecimal swLng,
        BigDecimal neLat, BigDecimal neLng) {
    assertThrows(InvalidMapBoundsException.class, () -> MapBounds.of(swLat, swLng, neLat, neLng));
}
```

- [ ] **Step 2: Run bounds tests and verify RED**

Run: `cd backend && ./gradlew test --tests '*MapBoundsTest'`

Expected: tests compile, the valid case passes, and invalid cases FAIL because the shell accepts invalid bounds.

- [ ] **Step 3: Implement immutable inclusive bounds validation**

```java
public record MapBounds(BigDecimal southWestLat, BigDecimal southWestLng,
                        BigDecimal northEastLat, BigDecimal northEastLng) {
    public static MapBounds of(BigDecimal swLat, BigDecimal swLng,
                               BigDecimal neLat, BigDecimal neLng) {
        requireAll(swLat, swLng, neLat, neLng);
        requireRange(swLat, MIN_LATITUDE, MAX_LATITUDE);
        requireRange(neLat, MIN_LATITUDE, MAX_LATITUDE);
        requireRange(swLng, MIN_LONGITUDE, MAX_LONGITUDE);
        requireRange(neLng, MIN_LONGITUDE, MAX_LONGITUDE);
        requireAscending(swLat, neLat);
        requireAscending(swLng, neLng);
        return new MapBounds(swLat, swLng, neLat, neLng);
    }
}
```

- [ ] **Step 4: Write cursor round-trip and malformed-cursor tests**

```java
@Test
void 게시일과_단지_ID를_URL_safe_커서로_왕복한다() {
    String cursor = codec.encode(LocalDate.of(2026, 8, 26), 41L);
    assertEquals(
            new HousingComplexCursorCodec.HousingComplexCursor(LocalDate.of(2026, 8, 26), 41L),
            codec.decode(cursor)
    );
}

@Test
void 대표_공고가_없는_단지_커서를_왕복한다() {
    String cursor = codec.encode(null, 41L);
    assertNull(codec.decode(cursor).postedDate());
}

@ParameterizedTest
@ValueSource(strings = {"", " ", "bad", "djJ8MjAyNi0wOC0yNnw0MQ=="})
void 잘못된_커서를_거부한다(String cursor) {
    assertThrows(InvalidComplexCursorException.class, () -> codec.decode(cursor));
}
```

Run: `cd backend && ./gradlew test --tests '*HousingComplexCursorCodecTest'`

Expected: tests compile and FAIL on round-trip/format assertions because the shell has no codec behavior.

- [ ] **Step 5: Implement versioned unpadded Base64 URL cursor codec**

```java
public final class HousingComplexCursorCodec {
    public record HousingComplexCursor(LocalDate postedDate, long complexId) {}
}

String payload = "v1|" + (postedDate == null ? "~" : postedDate) + "|" + complexId;
return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
```

- [ ] **Step 6: Write and implement feature advice contract tests**

```java
assertError(new InvalidMapBoundsException(), BAD_REQUEST, "INVALID_MAP_BOUNDS");
assertError(new InvalidComplexCursorException(), BAD_REQUEST, "INVALID_CURSOR");
assertError(new InvalidComplexRequestException(), BAD_REQUEST, "INVALID_REQUEST");
assertError(new HousingComplexNotFoundException(), NOT_FOUND, "COMPLEX_NOT_FOUND");
```

Run before implementing the mappings:
`cd backend && ./gradlew test --tests '*HousingComplexExceptionAdviceTest'`

Expected: tests compile and FAIL on status/code assertions against the intentionally wrong shell response.

Implement `HousingComplexExceptionAdvice` with `@Order(HIGHEST_PRECEDENCE)` and `ErrorResponse` including request `traceId`, following `backend/docs/exception-handling.md`.

- [ ] **Step 7: Run Task 1 tests and commit**

Run: `cd backend && ./gradlew test --tests '*MapBoundsTest' --tests '*HousingComplexCursorCodecTest' --tests '*HousingComplexExceptionAdviceTest'`

```bash
git add backend/src/main/java/com/toadzip/backend/global/response \
  backend/src/main/java/com/toadzip/backend/housing backend/src/test/java/com/toadzip/backend/housing
git commit -m "feat(housing): 단지 조회 공통 계약 추가 (#21)"
```

---

### Task 2: 지도 영역 단지 요약 조회

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryRow.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepository.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexCodeMapper.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexSummaryMapper.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexQueryService.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexController.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/AgencyResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingComplexMapItemResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingComplexMapResponse.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexMapQueryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java`

**Interfaces:**
- Consumes: `MapBounds`
- Produces: `ComplexSummaryQueryRepository.findAllInBounds(MapBounds)`
- Produces: `HousingComplexQueryService.getComplexesForMap(MapBounds)`
- Produces: `GET /api/v1/complexes/map`

The map response records have this exact public shape:

```java
public record AgencyResponse(String code, String name) {}
public record HousingComplexMapItemResponse(
        long complexId,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String rentalType,
        AgencyResponse agency,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMin,
        Long monthlyRentMax
) {}
public record HousingComplexMapResponse(List<HousingComplexMapItemResponse> items) {
    public HousingComplexMapResponse {
        items = List.copyOf(items);
    }
}
```

- [ ] **Step 0: Add compile-only Task 2 shells**

Create the listed response/projection records and final public method signatures. The repository returns
empty results, the service returns an empty `HousingComplexMapResponse`, and the controller has only
its constructor/class-level mapping with no endpoint. These are compile-only shells with no requested query,
mapping, validation, or HTTP behavior; do not commit them separately.

- [ ] **Step 1: Write PostgreSQL repository tests for inclusive bounds and summary aggregation**

```java
@Test
void 경계_안과_경계선의_단지만_ID_오름차순으로_조회한다() {
    List<ComplexSummaryRow> rows = repository.findAllInBounds(bounds);
    assertEquals(List.of(boundaryComplexId, insideComplexId), ids(rows));
}

@Test
void 대표_공고의_공급대상에서만_가격_범위를_집계한다() {
    ComplexSummaryRow row = repository.findAllInBounds(bounds).getFirst();
    assertAll(
            () -> assertEquals(new BigDecimal("50000000"), row.depositMin()),
            () -> assertEquals(new BigDecimal("70000000"), row.depositMax()),
            () -> assertEquals(new BigDecimal("200000"), row.monthlyRentMin()),
            () -> assertEquals(new BigDecimal("300000"), row.monthlyRentMax())
    );
}
```

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*ComplexSummaryQueryRepositoryTest'`

Expected: tests compile and FAIL on returned IDs/aggregates because the shell repository returns no rows.

- [ ] **Step 2: Implement the read-model SQL and projection**

Create this projection contract so `JdbcClient` values have one unambiguous Java type:

```java
public record ComplexSummaryRow(
        long complexId,
        String name,
        String imageUrl,
        String provinceCode,
        String cityCountyDistrictCode,
        String rentalType,
        String agencyCode,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        BigDecimal depositMin,
        BigDecimal depositMax,
        BigDecimal monthlyRentMin,
        BigDecimal monthlyRentMax,
        Long announcementId,
        String publicationType,
        LocalDate postedDate,
        LocalDate applicationStartDate,
        LocalDate applicationEndDate
) {}
```

Use one PostgreSQL CTE query shared by map and list:

```sql
WITH latest_leaf AS (
    SELECT announcement.*
    FROM announcements announcement
    WHERE NOT EXISTS (
        SELECT 1 FROM announcements successor
        WHERE successor.previous_announcement_id = announcement.id
    )
      AND announcement.status NOT IN ('CANCELLATION', '취소공고')
), representative AS (
    SELECT DISTINCT ON (supply_row.housing_complex_id)
           supply_row.housing_complex_id,
           announcement.id AS announcement_id,
           announcement.status AS publication_type,
           announcement.posted_date,
           announcement.application_start_date,
           announcement.application_end_date
    FROM supply_rows supply_row
    JOIN latest_leaf announcement ON announcement.id = supply_row.announcement_id
    WHERE supply_row.housing_complex_id IS NOT NULL
    ORDER BY supply_row.housing_complex_id, announcement.posted_date DESC, announcement.id DESC
), area_range AS (
    SELECT housing_complex_id, MIN(exclusive_area) AS exclusive_area_min,
           MAX(exclusive_area) AS exclusive_area_max
    FROM housing_types GROUP BY housing_complex_id
), price_range AS (
    SELECT supply_row.housing_complex_id,
           MIN(supply_target.rental_deposit) AS deposit_min,
           MAX(supply_target.rental_deposit) AS deposit_max,
           MIN(supply_target.monthly_rent) AS monthly_rent_min,
           MAX(supply_target.monthly_rent) AS monthly_rent_max
    FROM representative
    JOIN supply_rows supply_row
      ON supply_row.housing_complex_id = representative.housing_complex_id
     AND supply_row.announcement_id = representative.announcement_id
    JOIN supply_targets supply_target ON supply_target.supply_row_id = supply_row.id
    GROUP BY supply_row.housing_complex_id
)
```

The final selection uses `latitude BETWEEN :southWestLat AND :northEastLat` and the equivalent longitude predicate.
The leaf anti-join always checks successors of every publication type before the cancellation predicate is
applied. A cancellation leaf therefore leaves the complex with no representative announcement; it never
revives the cancelled predecessor. Freeze this with a repository fixture and assertion.

- [ ] **Step 3: Write failing map service response tests**

```java
HousingComplexMapResponse response = service.getComplexesForMap(bounds);
HousingComplexMapItemResponse item = response.items().getFirst();
assertAll(
        () -> assertEquals(complexId, item.complexId()),
        () -> assertEquals("HAPPY_HOUSING", item.rentalType()),
        () -> assertEquals("LH", item.agency().code()),
        () -> assertEquals(new BigDecimal("37.500000"), item.latitude())
);
```

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*HousingComplexMapQueryTest'`

Expected: tests compile and FAIL because the shell service returns no map items.

- [ ] **Step 4: Implement canonical code and map response mapping**

For Task 2, `HousingComplexCodeMapper` maps only canonical and approved legacy values for rental type and
agency. Agency mapping returns both code and the fixed display name. Unknown values throw
`IllegalStateException`; they are not silently converted to `ETC`. Publication and building-related
mappings are added only after their failing Task 3/4 tests.
Annotate `HousingComplexQueryService.getComplexesForMap` with `@Transactional(readOnly = true)`.

```java
return switch (storedValue) {
    case "HAPPY_HOUSING", "행복주택" -> "HAPPY_HOUSING";
    case "NATIONAL_RENTAL", "국민임대" -> "NATIONAL_RENTAL";
    case "PERMANENT_RENTAL", "영구임대" -> "PERMANENT_RENTAL";
    case "PUBLIC_RENTAL_50Y", "50년공공임대" -> "PUBLIC_RENTAL_50Y";
    case "INTEGRATED_PUBLIC_RENTAL", "통합공공임대" -> "INTEGRATED_PUBLIC_RENTAL";
    case "REDEVELOPMENT_RENTAL", "재개발임대" -> "REDEVELOPMENT_RENTAL";
    case "ETC", "기타" -> "ETC";
    default -> throw new IllegalStateException("지원하지 않는 공급유형 저장값이다.");
};
```

- [ ] **Step 5: Write the failing map HTTP contract test, then add the endpoint**

Create a `@WebMvcTest(HousingComplexController.class)` test with `HousingComplexQueryService` replaced at
the controller boundary. First verify that a valid four-coordinate request returns the exact
`data.items[]` JSON shape and that an omitted coordinate returns `INVALID_MAP_BOUNDS`; run
`cd backend && ./gradlew test --tests '*HousingComplexControllerTest'` and confirm RED because the shell
controller has no `/map` route. Then add the endpoint below. The test asserts the real controller,
`MapBounds` validation, envelope and feature advice behavior; it does not assert mock call counts.

```java
@GetMapping("/map")
public ApiResponse<HousingComplexMapResponse> getComplexesForMap(
        @RequestParam(required = false) BigDecimal southWestLat,
        @RequestParam(required = false) BigDecimal southWestLng,
        @RequestParam(required = false) BigDecimal northEastLat,
        @RequestParam(required = false) BigDecimal northEastLng
) {
    MapBounds bounds = MapBounds.of(southWestLat, southWestLng, northEastLat, northEastLng);
    return new ApiResponse<>(queryService.getComplexesForMap(bounds));
}
```

- [ ] **Step 6: Run Task 2 tests and commit**

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*ComplexSummaryQueryRepositoryTest' --tests '*HousingComplexMapQueryTest' --tests '*HousingComplexControllerTest'`

```bash
git add backend/src/main backend/src/test
git commit -m "feat(housing): 지도 영역 단지 조회 추가 (#21)"
```

---

### Task 3: 커서 기반 단지 목록 조회

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/region/repository/RegionCodeResolver.java`
- Create: `backend/src/main/java/com/toadzip/backend/region/repository/CsvRegionCodeResolver.java`
- Create: `backend/src/main/resources/region/regions.csv`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryCursor.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepository.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexCodeMapper.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexSummaryMapper.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexQueryService.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexController.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/RepresentativeAnnouncementResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingComplexListItemResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingComplexListResponse.java`
- Modify: `backend/src/test/java/com/toadzip/backend/housing/repository/ComplexSummaryQueryRepositoryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/repository/HousingRepositoryLayerBoundaryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/region/repository/CsvRegionCodeResolverTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexListQueryTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java`

**Interfaces:**
- Consumes: `MapBounds`, optional encoded cursor, `size`
- Produces: `findFirstPage(bounds, limit)` and `findPageAfter(bounds, repositoryCursor, limit)`
- Produces: `HousingComplexQueryService.getComplexes(bounds, cursor, size)`
- Produces: `GET /api/v1/complexes`

The list response records have this exact public shape (`applicationEndAt` is deliberately `LocalDate`
under the global source-precision ruling):

```java
public record RepresentativeAnnouncementResponse(
        long announcementId,
        String publicationType,
        String applicationStatus,
        LocalDate applicationEndAt,
        Integer dDay
) {}
public record HousingComplexListItemResponse(
        long complexId,
        String thumbnailImageUrl,
        String regionName,
        String name,
        String rentalType,
        AgencyResponse agency,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMin,
        Long monthlyRentMax,
        RepresentativeAnnouncementResponse representativeAnnouncement
) {}
public record HousingComplexListResponse(
        List<HousingComplexListItemResponse> items,
        String nextCursor,
        boolean hasNext
) {
    public HousingComplexListResponse {
        items = List.copyOf(items);
    }
}
```

- [ ] **Step 0: Add compile-only Task 3 signatures**

Create the three response records and add the final repository, mapper and service method signatures. The
repository returns empty pages, the mapper returns an empty list, and the service returns an empty list
response; leave the controller collection route absent. These shells contain no cursor, sorting, status or
pagination behavior and are not committed separately.

- [ ] **Step 1: Write the official region resolver tests, verify RED, then implement**

Add compile-only `RegionCodeResolver`/`CsvRegionCodeResolver` types whose resolver initially returns empty,
then write:

```java
assertEquals(Optional.of("서울특별시 중구"), resolver.resolve("11", "11140"));
assertEquals(Optional.empty(), resolver.resolve("99", "99999"));
assertEquals(Optional.empty(), resolver.resolve("1", "11140"));
```

Run: `cd backend && ./gradlew test --tests '*CsvRegionCodeResolverTest'`

Expected: tests compile and FAIL because the shell cannot resolve the official `11140` row. Then reuse the
PR #48 CSV content without checking out that branch: header `regionCode,sido,sigungu,name`, 269 unique
sorted rows, UTF-8 classpath loading, and no heuristic extraction from road addresses. Use `git show` only
to read the reference and create/edit the new file with `apply_patch`; never use checkout or shell redirection.

- [ ] **Step 2: Write failing repository keyset tests**

```java
@Test
void 최신_대표공고_게시일과_단지_ID로_안정적으로_페이지를_나눈다() {
    List<ComplexSummaryRow> first = repository.findFirstPage(bounds, 3);
    ComplexSummaryCursor cursor = cursorOf(first.get(1));
    List<ComplexSummaryRow> second = repository.findPageAfter(bounds, cursor, 3);
    assertEquals(List.of(thirdComplexId, complexWithoutAnnouncementId), ids(second));
}
```

Cover equal posted dates, a correction leaf replacing its original, cancellation leaf exclusion, and complexes with no matched announcement sorted last.

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*ComplexSummaryQueryRepositoryTest'`

Expected: tests compile and FAIL on page IDs because the new page shell methods return empty results.

- [ ] **Step 3: Implement first-page and after-cursor SQL variants**

```sql
ORDER BY representative.posted_date DESC NULLS LAST, housing_complex.id DESC
LIMIT :limit
```

For a non-null cursor date, rows after the cursor satisfy an older date, the same date with a smaller complex ID, or a null date. For a null cursor date, only null-date rows with a smaller complex ID qualify.
The service decodes the public `v1` cursor and adapts it to this repository-owned keyset value so the repository
does not depend on a service type.

- [ ] **Step 4: Write failing service pagination tests**

```java
HousingComplexListResponse response = service.getComplexes(bounds, null, 2);
assertAll(
        () -> assertEquals(2, response.items().size()),
        () -> assertTrue(response.hasNext()),
        () -> assertNotNull(response.nextCursor())
);

assertThrows(InvalidComplexRequestException.class, () -> service.getComplexes(bounds, null, 0));
assertThrows(InvalidComplexRequestException.class, () -> service.getComplexes(bounds, null, 51));
```

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*HousingComplexListQueryTest'`

Expected: tests compile and FAIL on items/cursor/validation assertions because the service shell has no list behavior.

- [ ] **Step 5: Implement page+1 slicing and representative response mapping**

```java
List<ComplexSummaryRow> fetched = findRows(bounds, cursor, size + 1);
boolean hasNext = fetched.size() > size;
List<ComplexSummaryRow> page = fetched.stream().limit(size).toList();
String nextCursor = hasNext ? cursorCodec.encode(page.getLast().postedDate(), page.getLast().complexId()) : null;
return new HousingComplexListResponse(mapper.toListItems(page, today()), nextCursor, hasNext);
```

Inject the existing application `Clock`; compute `today` with its `Asia/Seoul` zone and never call the
system clock directly. Keep `getComplexes` under `@Transactional(readOnly = true)`.

Extend `HousingComplexCodeMapper` only now to map `ORIGINAL`/`원공고` and
`CORRECTION`/`정정공고`; cancellation leaves never reach an API response and unknown publication values
fail explicitly. Use the representative leaf's publication code, derive `BEFORE_APPLICATION`, `APPLYING`,
or `CLOSED` from its required date range, and emit D-Day only before/on the end date. Price ranges remain
scoped to that same representative announcement. Include canonical, legacy, and unknown stored values in
the failing service fixtures so each branch has observable coverage.
Resolve `regionName` with `RegionCodeResolver`; because the field is non-null in the API contract,
unresolvable stored region codes are a data-integrity failure (`IllegalStateException`) rather than a guessed
road-address substring or a nullable response.

- [ ] **Step 6: Extend the HTTP contract test, then add the list endpoint**

Before changing the controller, add failing cases to `HousingComplexControllerTest` for the list success
envelope, default `size=20`, partial bounds as `INVALID_MAP_BOUNDS`, and service-thrown
`InvalidComplexCursorException`/`InvalidComplexRequestException` as the fixed feature error codes. Confirm
RED with `cd backend && ./gradlew test --tests '*HousingComplexControllerTest'` because the unqualified
collection route is absent, then add:

```java
@GetMapping
public ApiResponse<HousingComplexListResponse> getComplexes(
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) BigDecimal southWestLat,
        @RequestParam(required = false) BigDecimal southWestLng,
        @RequestParam(required = false) BigDecimal northEastLat,
        @RequestParam(required = false) BigDecimal northEastLng
) {
    MapBounds bounds = MapBounds.of(southWestLat, southWestLng, northEastLat, northEastLng);
    return new ApiResponse<>(queryService.getComplexes(bounds, cursor, size));
}
```

- [ ] **Step 7: Run Task 3 tests and commit**

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*CsvRegionCodeResolverTest' --tests '*ComplexSummaryQueryRepositoryTest' --tests '*HousingComplexListQueryTest' --tests '*HousingComplexCursorCodecTest' --tests '*HousingComplexControllerTest'`

```bash
git add backend/src/main backend/src/test
git commit -m "feat(housing): 단지 목록 커서 조회 추가 (#21)"
```

---

### Task 4: 좌표·주택형·현재 공급조건·현재 공고 상세 조회

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexDetailRow.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/HousingTypeDetailRow.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/CurrentSupplyConditionRow.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/CurrentAnnouncementRow.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/CurrentAnnouncementTargetRow.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/repository/ComplexDetailQueryRepository.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexDetailMapper.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexCodeMapper.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/service/HousingComplexQueryService.java`
- Modify: `backend/src/main/java/com/toadzip/backend/housing/controller/HousingComplexController.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingComplexAddressResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/CurrentSupplyConditionResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingTypeDetailResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/CurrentAnnouncementResponse.java`
- Create: `backend/src/main/java/com/toadzip/backend/housing/dto/response/HousingComplexDetailResponse.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/repository/ComplexDetailQueryRepositoryTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/service/HousingComplexDetailQueryTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java`

**Interfaces:**
- Produces: `findComplex`, `findHousingTypes`, `findCurrentSupplyConditions`,
  `findCurrentAnnouncements`, and `findCurrentAnnouncementTargets` for one complex ID and Seoul date
- Produces: `HousingComplexQueryService.getComplex(long complexId)`
- Produces: `GET /api/v1/complexes/{complexId}`

The detail response records have this exact public shape. `applicationStartAt`/`applicationEndAt` are
deliberately `LocalDate`, while spec-nullable source primitives are boxed in the response:

```java
public record HousingComplexAddressResponse(
        String regionName,
        String roadAddress,
        BigDecimal latitude,
        BigDecimal longitude
) {}
public record CurrentSupplyConditionResponse(
        String target,
        Long deposit,
        Long monthlyRent,
        Long convertibleDeposit
) {}
public record HousingTypeDetailResponse(
        long housingTypeId,
        String name,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        String floorPlanImageUrl,
        String floorPlan3dImageUrl,
        Boolean isDuplex,
        Long maintenanceFee,
        List<CurrentSupplyConditionResponse> currentSupplyConditions
) {}
public record CurrentAnnouncementResponse(
        long announcementId,
        String title,
        String publicationType,
        String applicationStatus,
        List<String> targets,
        LocalDate applicationStartAt,
        LocalDate applicationEndAt,
        Integer dDay,
        BigDecimal actualCompetitionRate
) {}
public record HousingComplexDetailResponse(
        long complexId,
        String name,
        String rentalType,
        AgencyResponse agency,
        HousingComplexAddressResponse address,
        LocalDate completionDate,
        String buildingType,
        Boolean hasElevator,
        String heatingType,
        String corridorType,
        Integer moveOutCountLastYear,
        Integer totalHouseholdCount,
        Integer totalParkingCount,
        List<String> images,
        String overviewImageUrl,
        List<HousingTypeDetailResponse> housingTypes,
        List<CurrentAnnouncementResponse> currentAnnouncements
) {}
```

Every list component is defensively copied in its compact constructor.

- [ ] **Step 0: Add compile-only Task 4 signatures**

Create the five repository row records, five response records, repository/mapper final method signatures,
and `HousingComplexQueryService.getComplex(long)`. Repository methods return empty results, while the
service shell throws `HousingComplexNotFoundException`; leave the detail HTTP route absent. These shells
contain no detail query/mapping behavior and are not committed separately.

- [ ] **Step 1: Write failing detail repository tests**

```java
@Test
void 최신_leaf이면서_접수전_또는_접수중인_공고만_현재공고로_조회한다() {
    List<CurrentAnnouncementRow> rows = repository.findCurrentAnnouncements(complexId, today);
    assertEquals(List.of(applyingLeafId, beforeApplicationLeafId), announcementIds(rows));
}

@Test
void 미매칭_공급행과_종료_취소_공고의_공급조건을_제외한다() {
    List<CurrentSupplyConditionRow> rows = repository.findCurrentSupplyConditions(complexId, today);
    assertEquals(List.of(activeTargetId), targetIds(rows));
}
```

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*ComplexDetailQueryRepositoryTest'`

Expected: tests compile and FAIL on current announcement/condition/basic row assertions because all repository
shell methods are empty.

- [ ] **Step 2: Implement five bounded detail queries**

Expose these exact repository methods:

```java
Optional<ComplexDetailRow> findComplex(long complexId);
List<HousingTypeDetailRow> findHousingTypes(long complexId);
List<CurrentSupplyConditionRow> findCurrentSupplyConditions(long complexId, LocalDate today);
List<CurrentAnnouncementRow> findCurrentAnnouncements(long complexId, LocalDate today);
List<CurrentAnnouncementTargetRow> findCurrentAnnouncementTargets(long complexId, LocalDate today);
```

`findComplex` returns the stored region codes and coordinates with the basic data. Housing types are ID
ascending. Both current-announcement queries first anti-join against successors of every publication type,
then reject a leaf `CANCELLATION`/`취소공고`, and
require `application_end_date >= :today`; this exactly represents `BEFORE_APPLICATION` or `APPLYING`
because source start/end dates are required. Announcements are posted-date then ID descending. Target
labels are `DISTINCT` per announcement and ordered by supply-row and supply-target display order. Current
supply conditions require non-null matched complex and housing type, join only those same current leaf
announcements, and order by announcement, supply-row display order, then target display order.
The repository test includes an original/cancellation chain and asserts that neither the predecessor nor the
cancellation appears in current announcements or conditions.

- [ ] **Step 3: Write failing detail mapping tests**

```java
HousingComplexDetailResponse response = service.getComplex(complexId);
assertAll(
        () -> assertEquals(latitude, response.address().latitude()),
        () -> assertEquals(longitude, response.address().longitude()),
        () -> assertEquals(List.of(imageUrl), response.images()),
        () -> assertNull(response.overviewImageUrl()),
        () -> assertNull(response.housingTypes().getFirst().floorPlan3dImageUrl()),
        () -> assertEquals(List.of("청년"), response.currentAnnouncements().getFirst().targets()),
        () -> assertNull(response.currentAnnouncements().getFirst().actualCompetitionRate())
);
```

Also assert missing complexes throw `HousingComplexNotFoundException`, money uses `longValueExact()`, and unknown nullable source fields stay null.

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*HousingComplexDetailQueryTest'`

Expected: tests compile and FAIL on the existing-complex response assertions because the service shell always
reports not found; the missing-complex case passes.

- [ ] **Step 4: Implement detail response assembler**

`HousingComplexDetailMapper` groups supply conditions by housing type ID and targets by announcement ID.
It derives `BEFORE_APPLICATION` or `APPLYING` and D-Day from a `LocalDate today`
argument supplied by the service; it does not query repositories or own a `Clock`. Map the single stored
`imageUrl` to a zero-or-one element `images` array, keep `overviewImageUrl` and 3D floor plans `null`, and
keep `actualCompetitionRate` `null` because the current model stores no official rate. Convert every
nullable money value, including maintenance fee, with `longValueExact()`.

Extend `HousingComplexCodeMapper` only after the detail tests are RED: heating values
`INDIVIDUAL`/`개별난방`, `CENTRAL`/`중앙난방`, `DISTRICT`/`지역난방`, `ETC`/`기타`; building values
`APARTMENT`/`아파트`, `OFFICETEL`/`오피스텔`, `ETC`/`기타`; and corridor values
`STAIR`/`계단식`, `CORRIDOR`/`복도식`, `MIXED`/`혼합식`, `UNKNOWN`/`미상`. Detail service fixtures
cover canonical, legacy, and unknown behavior; unknown non-null stored values fail explicitly.

- [ ] **Step 5: Extend the HTTP contract test, then add the detail endpoint**

Before changing the controller, add failing detail success and `COMPLEX_NOT_FOUND` cases to
`HousingComplexControllerTest`. The success case must assert coordinates under `data.address`; confirm RED
with `cd backend && ./gradlew test --tests '*HousingComplexControllerTest'` because the path route is absent,
then add:

```java
@GetMapping("/{complexId}")
public ApiResponse<HousingComplexDetailResponse> getComplex(
        @PathVariable long complexId
) {
    return new ApiResponse<>(queryService.getComplex(complexId));
}
```

- [ ] **Step 6: Run Task 4 tests and commit**

Run: `cd backend && TEST_POSTGRES_PORT=55433 ./gradlew test --tests '*ComplexDetailQueryRepositoryTest' --tests '*HousingComplexDetailQueryTest' --tests '*HousingComplexControllerTest'`

```bash
git add backend/src/main backend/src/test
git commit -m "feat(housing): 단지 상세 조회 추가 (#21)"
```

---

### Task 5: HTTP 계약·통합 시나리오·전체 품질 게이트

**Files:**
- Modify: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexControllerTest.java`
- Test: `backend/src/test/java/com/toadzip/backend/housing/controller/HousingComplexApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/global/config/OpenApiDocumentationIntegrationTest.java`

**Interfaces:**
- Consumes: all three endpoints and feature exceptions
- Produces: frozen JSON/error/OpenAPI contracts and PostgreSQL end-to-end evidence

- [ ] **Step 1: Write controller contract tests for all success shapes**

```java
mockMvc.perform(get("/api/v1/complexes")
                .param("southWestLat", "37.0")
                .param("southWestLng", "126.0")
                .param("northEastLat", "38.0")
                .param("northEastLng", "127.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray())
        .andExpect(jsonPath("$.data.hasNext").value(false));

mockMvc.perform(get("/api/v1/complexes/map")
                .param("southWestLat", "37.0")
                .param("southWestLng", "126.0")
                .param("northEastLat", "38.0")
                .param("northEastLng", "127.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray())
        .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

mockMvc.perform(get("/api/v1/complexes/{complexId}", 1L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.address.latitude").isNumber())
        .andExpect(jsonPath("$.data.address.longitude").isNumber());
```

- [ ] **Step 2: Write controller contract tests for each error code**

Cover missing/partial/reversed bounds as `INVALID_MAP_BOUNDS`, malformed cursor as `INVALID_CURSOR`, size 0/51 as `INVALID_REQUEST`, and absent ID as `COMPLEX_NOT_FOUND`. Assert every error has nonblank `traceId` and no SQL/exception class details.

- [ ] **Step 3: Write a PostgreSQL-backed HTTP integration scenario**

Persist an original/correction/cancellation chain, an unmatched supply row, two complexes on and inside the bounds, one outside, multiple housing types and supply targets. Call all three endpoints through MockMvc and assert latest-leaf selection, cursor non-overlap, inclusive bounds, map all-results, detail coordinates and current conditions.

- [ ] **Step 4: Verify OpenAPI exposure**

Extend local-profile OpenAPI integration to assert the three paths occur in `/v3/api-docs` while the
existing disabled-profile behavior remains unchanged.

- [ ] **Step 5: Review scope and commit test completion**

Confirm no optional search filter, non-default sort, production dependency, Entity response,
Controller-to-Repository call, or unrelated ingest change exists.

```bash
git add backend/src/test
git commit -m "test(housing): 단지 조회 API 통합 검증 추가 (#21)"
```

- [ ] **Step 6: Run targeted and full verification**

Run:

```bash
cd backend
TEST_POSTGRES_PORT=55433 ./gradlew test --tests 'com.toadzip.backend.housing.*'
TEST_POSTGRES_PORT=55433 ./gradlew --rerun-tasks check
cd ..
sh tests/harness/validate-harness-test.sh
sh tests/harness/validate-commit-message-test.sh
sh tests/harness/validate-pr-test.sh
sh scripts/validate-harness.sh
git diff --check origin/develop...HEAD
```
