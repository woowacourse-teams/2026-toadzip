# PostgreSQL Test Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** H2를 제거하고 개발자와 GitHub Actions가 `./scripts/test-postgres.sh` 한 번으로 격리된 PostgreSQL 17에서 모든 Spring/JPA 검증을 실행하게 한다.

**Architecture:** 독립적인 `compose.test.yaml`이 loopback 포트의 일회성 PostgreSQL만 제공하고, 호스트의 실행 스크립트가 기동·health 대기·Gradle 검사·정리를 소유한다. Spring 통합 테스트는 `test` 프로필과 안전한 `toadzip_test` 연결만 허용하며, local 프로필 계약 테스트도 같은 실제 PostgreSQL에서 `ddl-auto: update`를 검증한다.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Data JPA, Hibernate, PostgreSQL 17, Docker Compose, Bash, JUnit 5, GitHub Actions

**Spec:** `docs/superpowers/specs/2026-08-24-postgresql-test-environment-design.md`

## Global Constraints

- Testcontainers와 embedded database를 추가하지 않는다.
- H2의 development/test 의존성과 테스트 코드 참조를 모두 제거한다.
- 개발 DB `toadzip`, 기본 포트 `5432`, `postgres_data` 볼륨을 테스트가 사용하거나 정리하지 않는다.
- 테스트 DB/User/Password는 `toadzip_test`, 기본 호스트 포트는 `55432`로 고정한다.
- 테스트 PostgreSQL 포트는 `127.0.0.1`에만 바인딩하고 데이터 디렉터리는 `tmpfs`로 제공한다.
- 기본 명령 `./scripts/test-postgres.sh`는 캐시와 무관하게 Gradle `check`의 test task를 실제 실행한다.
- 성공, 실패와 인터럽트 모두 `toadzip-test` Compose 프로젝트만 정리한다.
- Spring/JPA 테스트는 PostgreSQL 제품명을 자동 검증하고 공유 스키마에서 직렬 실행한다.

---

### Task 1: PostgreSQL 통합 테스트 런타임

**Files:**

- Create: `compose.test.yaml`
- Create: `scripts/test-postgres.sh`
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/com/toadzip/backend/PostgreSqlIntegrationTest.java`
- Create: `backend/src/test/java/com/toadzip/backend/PostgreSqlTestEnvironmentGuard.java`
- Modify: `backend/build.gradle`
- Modify: `backend/src/test/java/com/toadzip/backend/BackendApplicationTests.java`
- Modify: `backend/src/test/java/com/toadzip/backend/DomainJpaPersistenceTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/LocalProfileSchemaPersistenceTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/housing/domain/HousingJpaMappingTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/interest/domain/InterestJpaMappingTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/notice/domain/NoticeJpaMappingTest.java`
- Modify: `backend/src/test/java/com/toadzip/backend/user/domain/UserJpaMappingTest.java`

**Interfaces:**

- Produces: `./scripts/test-postgres.sh [gradle arguments...]`; default Gradle arguments are `check`.
- Produces: `TEST_POSTGRES_PORT` override with default `55432`.
- Produces: `@PostgreSqlIntegrationTest`, which combines `@SpringBootTest`, `@ActiveProfiles("test")` and `PostgreSqlTestEnvironmentGuard`.
- Consumes: `application-local.yml`의 `spring.jpa.hibernate.ddl-auto: update` for the local-profile contract test.

- [ ] **Step 1: PostgreSQL 제품명 계약을 먼저 추가**

  `DomainJpaPersistenceTest`에 `DataSource`를 주입하고 실제 JDBC metadata를 검증한다.

  ```java
  @Test
  void 영속성_통합_테스트는_PostgreSQL에서_실행한다() throws SQLException {
      try (Connection connection = dataSource.getConnection()) {
          assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
      }
  }
  ```

- [ ] **Step 2: RED 확인**

  Run:

  ```shell
  cd backend
  ./gradlew --rerun-tasks test --tests com.toadzip.backend.DomainJpaPersistenceTest
  ```

  Expected: 기존 H2 connection metadata가 `H2`이므로 PostgreSQL 제품명 assertion failure.

- [ ] **Step 3: 독립 테스트 PostgreSQL 구성**

  `compose.test.yaml`은 PostgreSQL 한 서비스만 포함한다.

  ```yaml
  services:
    db:
      image: postgres:17-alpine
      environment:
        POSTGRES_DB: toadzip_test
        POSTGRES_USER: toadzip_test
        POSTGRES_PASSWORD: toadzip_test
      ports:
        - "127.0.0.1:${TEST_POSTGRES_PORT:-55432}:5432"
      tmpfs:
        - /var/lib/postgresql/data
      healthcheck:
        test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
        interval: 2s
        timeout: 5s
        retries: 30
      restart: "no"
  ```

- [ ] **Step 4: 한 명령 테스트 생명주기 구현**

  `scripts/test-postgres.sh`는 repository root를 계산하고 명시적인 project/file 배열을 사용한다.

  ```shell
  compose=(docker compose --project-name toadzip-test --file "$repository_root/compose.test.yaml")
  test_port="${TEST_POSTGRES_PORT:-55432}"
  ```

  EXIT/INT/TERM/HUP trap을 먼저 설치하고, 실패 시 `logs --no-color db`, 항상
  `down --volumes --remove-orphans`를 실행한다. 이후 `up --detach --wait --wait-timeout 60 db`를
  호출한다. Gradle 실행 전 ambient `SPRING_DATASOURCE_*`, `SPRING_PROFILES_ACTIVE`,
  `SPRING_JPA_HIBERNATE_DDL_AUTO`를 unset하고 `TEST_POSTGRES_PORT`만 전달한다.

  인자가 없으면 `check`, 있으면 전달받은 인자를 사용하며 다음처럼 실행한다.

  ```shell
  ./gradlew --rerun-tasks "$@"
  ```

- [ ] **Step 5: test 프로필과 안전 경계 구현**

  `application-test.yml`은 URL
  `jdbc:postgresql://127.0.0.1:${TEST_POSTGRES_PORT:55432}/toadzip_test`, 고정 test 자격증명,
  PostgreSQL driver, `ddl-auto: create-drop`, `web-application-type: none`을 설정한다.

  `PostgreSqlTestEnvironmentGuard`는 Spring context refresh 전에 다음을 검증하고 하나라도 다르면
  `IllegalStateException`을 던진다.

  ```text
  URL: jdbc:postgresql://127.0.0.1:<numeric-port>/toadzip_test
  username: toadzip_test
  database product target: PostgreSQL driver
  ddl-auto: create-drop
  ```

  `PostgreSqlIntegrationTest`는 기존 여섯 `@SpringBootTest` 클래스에서 공통으로 사용한다.
  `backend/build.gradle`에서는 두 H2 dependency를 제거하고 `test.maxParallelForks = 1`을 명시한다.

- [ ] **Step 6: local 프로필 계약을 PostgreSQL로 전환**

  `LocalProfileSchemaPersistenceTest`는 고정 DB명 `toadzip_test`와
  `${TEST_POSTGRES_PORT:-55432}`로 PostgreSQL URL을 만들고 driver를 `org.postgresql.Driver`로
  바꾼다. 기존처럼 system environment/property source를 제거하고 안전한 PostgreSQL 연결값과
  non-web 속성만 first-priority로 추가하며 활성 프로필은 `local`만 사용한다.

  컨텍스트 종료 뒤 JDBC metadata에서 다음 두 동작을 검증한다.

  ```text
  database product name == PostgreSQL
  NOTICES table exists
  ```

- [ ] **Step 7: focused GREEN과 실패 정리 검증**

  Run:

  ```shell
  ./scripts/test-postgres.sh test --tests com.toadzip.backend.DomainJpaPersistenceTest
  ./scripts/test-postgres.sh test --tests com.toadzip.backend.LocalProfileSchemaPersistenceTest
  ```

  Expected: both `BUILD SUCCESSFUL`; each run ends with no `toadzip-test` containers.

  Run an intentional failure:

  ```shell
  ./scripts/test-postgres.sh test --tests com.toadzip.backend.DoesNotExist
  ```

  Expected: non-zero Gradle result, PostgreSQL log output, and no remaining `toadzip-test` containers.

- [ ] **Step 8: 전체 GREEN과 commit**

  Run:

  ```shell
  ./scripts/test-postgres.sh
  rg -n "com\\.h2database|jdbc:h2|org\\.h2" backend
  ```

  Expected: `BUILD SUCCESSFUL`; H2 search has no matches.

  Commit:

  ```text
  test(db): PostgreSQL 통합 테스트 환경 전환 (#14)
  ```

---

### Task 2: CI와 개발 문서의 PostgreSQL 테스트 경로

**Files:**

- Modify: `.github/workflows/harness-check.yml`
- Modify: `CONTRIBUTING.md`
- Modify: `backend/docs/testing.md`
- Modify: `backend/docs/quality-gates.md`
- Modify: `backend/docs/development-cycle.md`
- Modify: `docs/superpowers/plans/2026-08-24-local-database-setup.md`

**Interfaces:**

- Consumes: `./scripts/test-postgres.sh [gradle arguments...]` from Task 1.
- Produces: local and CI canonical backend verification command `./scripts/test-postgres.sh`.

- [ ] **Step 1: GitHub Actions backend gate 전환**

  기존 Java setup과 하네스 단계는 유지하고 backend verification command만 다음으로 바꾼다.

  ```yaml
  - name: Verify backend against PostgreSQL
    if: ${{ hashFiles('gradlew', 'backend/gradlew') != '' }}
    run: ./scripts/test-postgres.sh
  ```

- [ ] **Step 2: 테스트 실행 문서 통일**

  다음 문서에서 전체 backend 검증 명령을 root 기준 `./scripts/test-postgres.sh`로 바꾼다.

  ```text
  CONTRIBUTING.md
  backend/docs/testing.md
  backend/docs/quality-gates.md
  backend/docs/development-cycle.md
  ```

- [ ] **Step 3: 이전 계획의 H2 참조 제거**

  `docs/superpowers/plans/2026-08-24-local-database-setup.md`의 Architecture, Tech Stack과 local
  schema test 단계가 최종 PostgreSQL 방식과 일치하도록 수정하고 새 spec/plan 링크를 추가한다.

- [ ] **Step 4: 정적·하네스·전체 실행 검증**

  Run:

  ```shell
  docker compose --project-name toadzip-test --file compose.test.yaml config
  bash -n scripts/test-postgres.sh
  sh tests/harness/validate-harness-test.sh
  sh scripts/validate-harness.sh
  ./scripts/test-postgres.sh
  git diff --check
  ```

  Expected: every command succeeds and Compose output contains only the test `db` service.

- [ ] **Step 5: 범위 자체 검토와 commit**

  Commit:

  ```text
  ci(test): PostgreSQL 테스트 실행 경로 통일 (#14)
  ```
