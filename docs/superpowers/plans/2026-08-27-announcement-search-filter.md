# Announcement Search Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 공고명, 지역, 공급기관, 공급유형, 모집유형, 공고 발행유형, 접수상태와 접수기간을 조합해 최신 공고 목록을 안정적인 커서 페이지로 조회할 수 있게 한다.

**Architecture:** Controller가 HTTP 검색값을 `AnnouncementSearchRequest`로 묶고 Service가 교차 검증, 서울 기준 날짜와 지역 동등 코드를 해석해 repository-owned `AnnouncementSearchCondition`을 만든다. 조회 전용 `AnnouncementSearchRepository`는 하나의 Criteria 쿼리에서 최신 leaf 규칙, 모든 활성 필터, `(postedDate, id)` keyset cursor와 `size + 1` 제한을 함께 적용하며 기존 응답 매퍼와 커서 codec은 재사용한다.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring MVC, Spring Data JPA, Hibernate Criteria, PostgreSQL 17, JUnit 5, MockMvc

**Spec:** `/Users/jeongjaemin/.codex/attachments/5f65383c-2679-4a9a-ae3d-65f64e2c82a6/pasted-text.txt`, GitHub issue `https://github.com/woowacourse-teams/2026-toadzip/issues/22`, prerequisite issue `https://github.com/woowacourse-teams/2026-toadzip/issues/20`

## Global Constraints

- `GET /api/v1/announcements`는 `keyword`, `regionCode`, `rentalTypes`, `applicationStatuses`, `publicationTypes`, `agencyCodes`, `recruitmentTypes`, `applicationFrom`, `applicationTo`, `cursor`, `size`를 받는다.
- `applicationFrom`과 `applicationTo`는 `YYYY-MM-DD`이며 공고 기간과 요청 기간이 하루라도 겹치면 포함한다. 양 끝 날짜는 포함한다.
- 기간 predicate는 `applicationEndDate >= applicationFrom`과 `applicationStartDate <= applicationTo`다. 한쪽 경계만 있으면 해당 predicate만 적용한다.
- 같은 배열 안의 값은 OR, 서로 다른 필터 그룹은 AND다. null 또는 빈 배열은 해당 필터를 적용하지 않는다.
- `publicationTypes`에 `CANCELLATION`이 하나라도 있거나 `applicationStatuses`에 `CANCELLED`가 하나라도 있으면 `INVALID_REQUEST`를 반환한다.
- 목록은 원공고·정정공고 중 연결된 최신 leaf만 포함하며 최신 공고가 취소공고인 체인은 제외한다.
- 정렬은 `postedDate DESC, id DESC`, cursor payload는 기존 `v1|postedDate|id`, `size` 기본값은 20이고 허용 범위는 1~50이다.
- 후속 페이지는 첫 페이지와 같은 필터를 재전송한다. 필터가 바뀌면 기존 cursor를 사용하지 않는다. 서버는 cursor fingerprint를 추가하지 않는다.
- `applicationStatuses`는 저장 컬럼이 아니라 동일한 서울 기준 `today`로 DB predicate와 응답 상태를 모두 계산한다.
- 지역 필터는 canonical 코드와 그 코드의 모든 legacy alias를 동일하게 취급한다. 매칭된 `HousingComplex`가 없는 공급행은 지역 필터에 일치하지 않는다.
- enum 필터는 영문 이름과 레거시 한글 저장값을 모두 일치시킨다.
- `keyword`는 앞뒤 공백을 제거한 공고명 부분검색이며 `%`, `_`, `\\`는 LIKE wildcard가 아니라 문자 그대로 검색한다. 공백만 있는 keyword는 `INVALID_REQUEST`다.
- 잘못된 enum·날짜 형식은 `VALIDATION_FAILED`, 역전 기간·금지된 취소 상태·공백 keyword는 `INVALID_REQUEST`, 미등록 지역은 `INVALID_REGION_CODE`, 잘못된 cursor는 `INVALID_CURSOR`다.
- 목록 응답 DTO와 기존 상세 조회 계약은 변경하지 않는다.
- production dependency와 선제 인덱스를 추가하지 않는다. 인덱스는 PostgreSQL 실행계획 근거가 생길 때 별도 변경으로 다룬다.
- Controller → Service → Repository·Domain 의존 방향과 Entity 응답 금지를 유지한다.
- 모든 production behavior는 실패 테스트를 먼저 실행하고 Red 원인을 확인한 뒤 최소 구현한다.

---

### Task 1: Region Code Equivalence

**Files:**
- Modify: `backend/src/main/java/com/toadzip/backend/region/repository/RegionCodeResolver.java`
- Modify: `backend/src/main/java/com/toadzip/backend/region/repository/CsvRegionCodeResolver.java`
- Test: `backend/src/test/java/com/toadzip/backend/region/repository/CsvRegionCodeResolverTest.java`

**Interfaces:**
- Consumes: 현재 CSV의 canonical `regionCode`와 `legacyRegionCode,currentRegionCode` alias 데이터.
- Produces: `Optional<Set<String>> equivalentCodes(String regionCode)`. canonical 또는 legacy 입력을 받으면 canonical과 모든 legacy alias의 불변 집합을 반환하고 미등록 코드는 `Optional.empty()`를 반환한다.

- [ ] **Step 1: canonical·legacy·invalid 동작의 실패 테스트 작성**

```java
@Test
void 현재_지역코드는_자신과_모든_과거_코드를_반환한다() {
    assertThat(regionCodeResolver.equivalentCodes("12210"))
            .contains(Set.of("12210", "29110"));
}

@Test
void 과거_지역코드도_동일한_현재_지역_코드_집합을_반환한다() {
    assertThat(regionCodeResolver.equivalentCodes("29110"))
            .contains(Set.of("12210", "29110"));
}

@Test
void 미등록_지역코드는_해석하지_않는다() {
    assertThat(regionCodeResolver.equivalentCodes("99999")).isEmpty();
}
```

- [ ] **Step 2: 테스트가 새 interface 부재 때문에 실패하는지 확인**

Run: `cd backend && ./gradlew test --tests '*CsvRegionCodeResolverTest'`

Expected: FAIL at test compilation because `equivalentCodes(String)` does not exist.

- [ ] **Step 3: 역방향 alias index와 interface 구현**

```java
public interface RegionCodeResolver {
    Optional<String> resolve(String provinceCode, String cityCountyDistrictCode);

    Optional<Set<String>> equivalentCodes(String regionCode);
}
```

`CsvRegionCodeResolver`는 입력을 alias map으로 canonicalize하고 canonical 존재 여부를 검증한 뒤, `currentRegionCode`가 같은 모든 legacy key와 canonical code를 `Set.copyOf(...)`로 반환한다. 기존 `resolve(...)` 동작은 변경하지 않는다.

- [ ] **Step 4: 지역 resolver 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests '*CsvRegionCodeResolverTest'`

Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/toadzip/backend/region/repository \
  backend/src/test/java/com/toadzip/backend/region/repository/CsvRegionCodeResolverTest.java
git commit -m "feat(region): 검색용 동등 지역 코드 해석 추가 (#22)"
```

---

### Task 2: Latest-Leaf Query and Direct Filters

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/announcement/repository/AnnouncementSearchCondition.java`
- Create: `backend/src/main/java/com/toadzip/backend/announcement/repository/AnnouncementSearchRepository.java`
- Modify: `backend/src/main/java/com/toadzip/backend/announcement/repository/AnnouncementRepository.java`
- Modify: `backend/src/test/java/com/toadzip/backend/announcement/repository/AnnouncementQueryRepositoryTest.java`

**Interfaces:**
- Consumes: 기존 `Announcement` entity와 `(postedDate, id)` cursor values.
- Produces: `List<Announcement> findLatestLeaves(AnnouncementSearchCondition condition, LocalDate cursorPostedDate, Long cursorId, int limit)`.

```java
public record AnnouncementSearchCondition(
        String keyword,
        Set<String> regionCodes,
        Set<RentalType> rentalTypes,
        Set<ApplicationStatus> applicationStatuses,
        Set<AnnouncementPublicationType> publicationTypes,
        Set<AgencyCode> agencyCodes,
        Set<RecruitmentType> recruitmentTypes,
        LocalDate applicationFrom,
        LocalDate applicationTo,
        LocalDate today
) {
}
```

- [ ] **Step 1: latest leaf·cursor·직접 필터의 PostgreSQL 실패 테스트 작성**

테스트 fixture는 다음 관찰값을 각각 별도 테스트로 고정한다.

```java
assertThat(search(noFilters(), null, null, 20)).extracting(Announcement::getId)
        .containsExactly(latestCorrection.getId(), originalLeaf.getId());

assertThat(search(withAgencyCodes(Set.of(AgencyCode.LH, AgencyCode.SH)), null, null, 20))
        .extracting(Announcement::getProvider)
        .containsExactly(AgencyCode.LH, AgencyCode.SH);

assertThat(search(withKeyword("100%_행복"), null, null, 20))
        .extracting(Announcement::getName)
        .containsExactly("100%_행복 공고");
```

추가로 `RentalType`, `RecruitmentType`, `AnnouncementPublicationType`, 같은 게시일 cursor, 미연결 정정공고 제외, 취소 leaf 체인 제외를 각각 검증한다. native update fixture로 enum 영문 이름과 한글 legacy 저장값을 모두 만든다.

- [ ] **Step 2: 새 조회 repository 부재 때문에 Red인지 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementQueryRepositoryTest'`

Expected: FAIL at compilation because `AnnouncementSearchRepository` and `AnnouncementSearchCondition` do not exist.

- [ ] **Step 3: 하나의 Criteria query로 base rule과 direct filters 구현**

```java
@Repository
public class AnnouncementSearchRepository {
    public List<Announcement> findLatestLeaves(
            AnnouncementSearchCondition condition,
            LocalDate cursorPostedDate,
            Long cursorId,
            int limit
    ) {
        // Build one root Announcement criteria query, add predicates,
        // order postedDate DESC then id DESC, and setMaxResults(limit).
    }
}
```

base predicates는 기존 JPQL과 동일하게 original/correction stored values, original 또는 연결된 correction, successor `NOT EXISTS`를 적용한다. enum path 비교는 `HibernateCriteriaBuilder.cast(path, String.class)`와 `LegacyStoredValue.storedValues()`를 한 private helper에서 사용한다. keyword는 lower-case literal LIKE pattern을 만들고 `\\` escape를 명시한다. cursor 두 값은 함께 있을 때만 기존 keyset predicate를 추가한다.

- [ ] **Step 4: 기존 `AnnouncementRepository`의 중복 목록 JPQL 제거**

`findLatestLeaves(Pageable)`와 `findLatestLeavesAfter(...)`를 제거하고 `findDetailById(...)`와 `JpaRepository` 책임만 남긴다.

- [ ] **Step 5: repository 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementQueryRepositoryTest'`

Expected: PASS for base leaf, direct filter, legacy value and cursor cases.

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/toadzip/backend/announcement/repository \
  backend/src/test/java/com/toadzip/backend/announcement/repository/AnnouncementQueryRepositoryTest.java
git commit -m "feat(announcement): 공고 직접 검색 조건 조회 추가 (#22)"
```

---

### Task 3: Derived Status, Application Period, and Region Predicates

**Files:**
- Modify: `backend/src/main/java/com/toadzip/backend/announcement/repository/AnnouncementSearchRepository.java`
- Modify: `backend/src/test/java/com/toadzip/backend/announcement/repository/AnnouncementQueryRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2의 `AnnouncementSearchCondition`; Task 1이 나중에 Service에 제공할 equivalent region code set.
- Produces: direct filters와 함께 AND되는 application-status OR group, inclusive overlap period predicates, correlated region `EXISTS` predicate.

- [ ] **Step 1: 접수상태 날짜 경계의 실패 테스트 작성**

```java
LocalDate today = LocalDate.of(2026, 8, 27);

assertThat(search(withApplicationStatuses(Set.of(ApplicationStatus.APPLYING), today), null, null, 20))
        .extracting(Announcement::getApplicationStartDate, Announcement::getApplicationEndDate)
        .containsExactly(tuple(today, today));
```

`BEFORE_APPLICATION`은 `start > today`, `APPLYING`은 `start <= today && end >= today`, `CLOSED`는 `end < today`를 각각 검증한다. 두 status 값은 OR로 동작해야 한다.

- [ ] **Step 2: 기간 겹침과 지역 중복 방지의 실패 테스트 작성**

```java
assertThat(search(withApplicationPeriod(
        LocalDate.of(2026, 8, 10),
        LocalDate.of(2026, 8, 20)
), null, null, 20)).extracting(Announcement::getId)
        .containsExactly(overlapsAtStart.getId(), contained.getId(), overlapsAtEnd.getId());

assertThat(search(withRegionCodes(Set.of("12210", "29110")), null, null, 20))
        .extracting(Announcement::getId)
        .containsExactlyOnce(multiSupplyRowAnnouncement.getId());
```

`applicationFrom`만, `applicationTo`만, 양 경계 같은 날, 매칭 단지가 없는 공급행 제외도 별도 테스트로 고정한다.

- [ ] **Step 3: 테스트가 미구현 predicate 때문에 예상 결과와 다르게 실패하는지 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementQueryRepositoryTest'`

Expected: FAIL because derived filters do not restrict Task 2 search results.

- [ ] **Step 4: 파생 predicate 최소 구현**

application status predicate는 선택된 상태별 predicate를 OR하고 다른 그룹과 AND한다. 기간은 `end >= from`, `start <= to`를 선택적으로 추가한다. 지역은 root query에 collection join을 추가하지 않고 `SupplyRow` 상관 subquery에서 `supplyRow.announcement = root`와 `housingComplex.address.cityCountyDistrictCode IN regionCodes`를 만족하는 `EXISTS`만 추가한다.

- [ ] **Step 5: repository 전체 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementQueryRepositoryTest'`

Expected: PASS with no duplicated announcement and stable cursor order.

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/toadzip/backend/announcement/repository/AnnouncementSearchRepository.java \
  backend/src/test/java/com/toadzip/backend/announcement/repository/AnnouncementQueryRepositoryTest.java
git commit -m "feat(announcement): 접수기간과 지역 검색 조건 추가 (#22)"
```

---

### Task 4: HTTP Contract, Validation, and Service Integration

**Files:**
- Create: `backend/src/main/java/com/toadzip/backend/announcement/dto/request/AnnouncementSearchRequest.java`
- Create: `backend/src/main/java/com/toadzip/backend/announcement/exception/InvalidRegionCodeException.java`
- Modify: `backend/src/main/java/com/toadzip/backend/announcement/controller/AnnouncementController.java`
- Modify: `backend/src/main/java/com/toadzip/backend/announcement/controller/AnnouncementExceptionAdvice.java`
- Modify: `backend/src/main/java/com/toadzip/backend/announcement/service/AnnouncementQueryService.java`
- Modify: `backend/src/test/java/com/toadzip/backend/announcement/controller/AnnouncementControllerTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/announcement/service/AnnouncementQueryServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `RegionCodeResolver.equivalentCodes`, Task 2·3의 `AnnouncementSearchRepository.findLatestLeaves`.
- Produces: `AnnouncementQueryService.getAnnouncements(AnnouncementSearchRequest request, String cursor, int size)`와 공개 GET query contract.

```java
public record AnnouncementSearchRequest(
        String keyword,
        String regionCode,
        List<RentalType> rentalTypes,
        List<ApplicationStatus> applicationStatuses,
        List<AnnouncementPublicationType> publicationTypes,
        List<AgencyCode> agencyCodes,
        List<RecruitmentType> recruitmentTypes,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate applicationFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate applicationTo
) {
}
```

- [ ] **Step 1: 기존 MVC slice baseline을 선행 fix와 동일하게 격리**

`AnnouncementControllerTest`에 `@AutoConfigureMockMvc(addFilters = false)`를 추가한다. 이는 `origin/develop`의 #36 보안 merge 이후 생긴 401 baseline 회귀를 격리하며 제품 코드는 변경하지 않는다.

Run: `cd backend && ./gradlew test --tests '*AnnouncementControllerTest'`

Expected: 기존 7 tests PASS before adding new feature tests.

- [ ] **Step 2: HTTP binding과 표준 오류의 실패 테스트 작성**

```java
mockMvc.perform(get("/api/v1/announcements")
                .param("agencyCodes", "LH", "SH")
                .param("applicationFrom", "2026-08-01")
                .param("applicationTo", "2026-08-31"))
        .andExpect(status().isOk());

assertValidationError(
        mockMvc.perform(get("/api/v1/announcements").param("applicationFrom", "2026/08/01")),
        "applicationFrom"
);
```

반복 enum query parameter, 전체 query parameter 전달, 잘못된 enum·날짜 형식, 기존 size/cursor 동작을 별도 테스트로 추가한다.

- [ ] **Step 3: Service 교차 검증의 실패 테스트 작성**

```java
assertThatThrownBy(() -> service.getAnnouncements(
        requestWithPeriod(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)), null, 20
)).isInstanceOf(InvalidAnnouncementRequestException.class);

assertThatThrownBy(() -> service.getAnnouncements(
        requestWithPublicationTypes(List.of(AnnouncementPublicationType.CANCELLATION)), null, 20
)).isInstanceOf(InvalidAnnouncementRequestException.class);

assertThatThrownBy(() -> service.getAnnouncements(
        requestWithApplicationStatuses(List.of(ApplicationStatus.CANCELLED)), null, 20
)).isInstanceOf(InvalidAnnouncementRequestException.class);

assertThatThrownBy(() -> service.getAnnouncements(requestWithRegion("99999"), null, 20))
        .isInstanceOf(InvalidRegionCodeException.class);
```

같은 `today`가 repository condition과 response mapper에 전달되고, 필터된 `size + 1` 결과로 `hasNext`와 cursor를 계산하는 동작도 검증한다.

- [ ] **Step 4: 새 API 부재로 Controller·Service 테스트가 Red인지 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementControllerTest' --tests '*AnnouncementQueryServiceTest'`

Expected: FAIL because request DTO and new service signature do not exist.

- [ ] **Step 5: Controller binding과 Service validation 최소 구현**

Controller는 `@ModelAttribute AnnouncementSearchRequest request`와 기존 `cursor`, `size`를 받는다. Service는 null list를 empty set으로 정규화하고 keyword trim, 기간 순서, 취소 상태, region code를 repository 호출 전에 검증한다. LIKE 문법 escaping은 query를 소유하는 `AnnouncementSearchRepository`가 담당한다. 유효한 region은 `equivalentCodes` 결과를 condition에 넣는다. 오늘 날짜는 한 번만 계산하고 condition과 mapper에 재사용한다.

- [ ] **Step 6: `INVALID_REGION_CODE` advice 계약 구현**

```java
@ExceptionHandler(InvalidRegionCodeException.class)
public ResponseEntity<ErrorResponse> handleInvalidRegionCode(HttpServletRequest request) {
    return badRequest("INVALID_REGION_CODE", "지역 코드를 확인해 주세요.", request);
}
```

- [ ] **Step 7: Controller와 Service 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementControllerTest' --tests '*AnnouncementQueryServiceTest'`

Expected: PASS, including previous size/cursor/detail contracts.

- [ ] **Step 8: 커밋**

```bash
git add backend/src/main/java/com/toadzip/backend/announcement \
  backend/src/test/java/com/toadzip/backend/announcement/controller/AnnouncementControllerTest.java \
  backend/src/test/java/com/toadzip/backend/announcement/service/AnnouncementQueryServiceTest.java
git commit -m "feat(announcement): 공고 검색 요청과 검증 연결 (#22)"
```

---

### Task 5: End-to-End Filter and Pagination Regression

**Files:**
- Modify: `backend/src/test/java/com/toadzip/backend/announcement/controller/AnnouncementApiIntegrationTest.java`
- Modify if test-driven correction is required: files created or modified in Tasks 1–4 only.

**Interfaces:**
- Consumes: 완성된 `GET /api/v1/announcements` 검색 계약.
- Produces: 실제 PostgreSQL과 HTTP 경계에서 #22 완료 조건을 증명하는 회귀 테스트.

- [ ] **Step 1: 복수 조건 조합 HTTP 실패 테스트 작성**

```java
mockMvc.perform(get("/api/v1/announcements")
                .param("agencyCodes", "LH", "SH")
                .param("rentalTypes", "HAPPY_HOUSING")
                .param("applicationStatuses", "APPLYING")
                .param("regionCode", "12210")
                .param("applicationFrom", "2026-08-27")
                .param("applicationTo", "2026-08-27")
                .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].announcementId").value(firstMatchingId))
        .andExpect(jsonPath("$.data.items[1].announcementId").value(secondMatchingId));
```

fixture는 직접 조건 하나가 다른 공고, 지역만 다른 공고, 접수기간이 하루도 겹치지 않는 공고를 포함해 그룹 간 AND를 증명한다.

- [ ] **Step 2: 필터 상태 cursor 2페이지 실패 테스트 작성**

같은 게시일의 matching 공고를 최소 세 개 만들고 `size=2` 첫 응답의 `nextCursor`와 같은 필터를 두 번째 요청에 전달한다. 두 페이지 ID 합집합에 중복·누락이 없고 각 페이지가 `postedDate DESC, id DESC`인지 literal ID 순서로 검증한다.

- [ ] **Step 3: 금지된 취소 상태와 잘못된 지역 HTTP 오류 실패 테스트 작성**

`publicationTypes=CANCELLATION`, `applicationStatuses=CANCELLED`, 역전된 기간은 `400 INVALID_REQUEST`, `regionCode=99999`는 `400 INVALID_REGION_CODE`와 비어 있지 않은 `traceId`를 반환해야 한다.

- [ ] **Step 4: 통합 테스트가 누락된 behavior 때문에 Red인지 확인**

Run: `cd backend && ./gradlew test --tests '*AnnouncementApiIntegrationTest'`

Expected: any uncovered binding, query or error-contract behavior fails with an assertion mismatch, not a setup error.

- [ ] **Step 5: Red가 드러낸 최소 integration defect만 수정**

수정 범위는 Tasks 1–4 파일로 제한하고 각 수정 전 실패 assertion이 어떤 production branch를 보호하는지 기록한다. 새 동작을 추가하지 않는다.

- [ ] **Step 6: 공고 기능 테스트 묶음 통과 확인**

Run: `cd backend && ./gradlew test --tests 'com.toadzip.backend.announcement.*'`

Expected: PASS.

- [ ] **Step 7: PostgreSQL 전체 품질 게이트 실행**

```bash
cd backend
TEST_POSTGRES_PORT=55434 ./gradlew --rerun-tasks check
cd ..
git diff --check
sh tests/harness/validate-harness-test.sh
sh scripts/validate-harness.sh
```

Expected: all commands PASS. The PostgreSQL container is already provided by compose project `toadzip-issue22-test` on port 55434.

- [ ] **Step 8: 커밋**

```bash
git add backend/src/test/java/com/toadzip/backend/announcement/controller/AnnouncementApiIntegrationTest.java \
  backend/src/main/java/com/toadzip/backend/announcement \
  backend/src/test/java/com/toadzip/backend/announcement \
  backend/src/main/java/com/toadzip/backend/region/repository \
  backend/src/test/java/com/toadzip/backend/region/repository
git commit -m "test(announcement): 검색 조합과 커서 회귀 검증 (#22)"
```
