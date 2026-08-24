# Local Database Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan.

**Goal:** 각 개발자가 Docker Compose로 격리된 PostgreSQL을 실행하고, `local` 프로필의 Hibernate `update` 전략으로 현재 JPA 엔티티의 테이블을 생성한 뒤 DBeaver에서 조회할 수 있게 한다.

**Architecture:** 운영용 기본 Compose 파일은 유지하고 `compose.local.yaml`을 오버레이로 적용한다. 로컬 전용 JPA 정책은 `application-local.yml`에만 두며, 개발자별 비밀값은 추적하지 않는 `.env`에서 주입한다. 자동 테스트는 독립된 `toadzip_test` PostgreSQL에서 `local` 프로필이 애플리케이션 종료 시 스키마를 삭제하지 않는 `ddl-auto: update` 계약을 검증하고, 최종 확인은 실제 PostgreSQL 컨테이너의 테이블 목록으로 수행한다.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Data JPA, Hibernate, PostgreSQL 17, Docker Compose, Bash, JUnit 5

**Spec:** GitHub issue #14 및 사용자 승인 범위: `application-local.yml`, `compose.local.yaml`, `.env.example`의 `POSTGRES_PORT`, 로컬 DB/DBeaver 문서, Hibernate 테이블 생성 검증, `develop` 대상 PR.

테스트 실행 경로의 최종 설계와 구현 단계는 [PostgreSQL 테스트 환경 설계](../specs/2026-08-24-postgresql-test-environment-design.md)와 [PostgreSQL 테스트 환경 구현 계획](2026-08-24-postgresql-test-environment.md)을 따른다.

## Global Constraints

- 작업 브랜치는 최신 `origin/develop`에서 만든 `chore/14-local-db-setup`을 사용한다.
- 이 설정은 개발자 로컬 환경 전용이며 운영 배포 설정을 변경하지 않는다.
- `application-local.yml`은 추적하되 실제 비밀번호를 포함하지 않는다.
- 각 개발자의 실제 값은 Git에서 제외된 `.env`에 저장하고 `.env.example`에는 예시값만 둔다.
- PostgreSQL 호스트 포트는 로컬 머신에서만 접근하도록 `127.0.0.1`에 바인딩한다.
- Hibernate 전략은 `local` 프로필에서만 `update`이며 운영 마이그레이션 수단으로 설명하지 않는다.
- 기존 엔티티나 기본 `compose.yaml`은 요구에 필요하지 않으면 수정하지 않는다.

### Task 1: 로컬 PostgreSQL 개발 환경

**Files:**

- Create: `backend/src/test/java/com/toadzip/backend/LocalProfileSchemaPersistenceTest.java`
- Create: `backend/src/main/resources/application-local.yml`
- Create: `compose.local.yaml`
- Modify: `.env.example`
- Modify: `docs/SETUP.md`

- [ ] **Step 1: local 프로필 스키마 유지 동작의 실패 테스트 작성**
  - `SpringApplicationBuilder`로 `local` 프로필과 독립된 `toadzip_test` PostgreSQL을 사용해 애플리케이션 컨텍스트를 실행하고 종료한다.
  - 종료 후 JDBC metadata로 PostgreSQL에서 `NOTICES` 테이블이 남아 있음을 검증한다.
  - 실행: `./scripts/test-postgres.sh test --tests com.toadzip.backend.LocalProfileSchemaPersistenceTest`
  - 기대: `application-local.yml`이 없으므로 기본 `create-drop` 동작으로 테이블이 삭제되어 실패한다.

- [ ] **Step 2: local 프로필 JPA 설정의 최소 구현**
  - `application-local.yml`에 `spring.jpa.hibernate.ddl-auto: update`만 추가한다.
  - 같은 PostgreSQL 테스트를 다시 실행해 통과를 확인한다.

- [ ] **Step 3: 로컬 Compose 오버레이와 예시 환경변수 추가**
  - `compose.local.yaml`에서 DB의 `${POSTGRES_PORT:-5432}`를 컨테이너 5432에 `127.0.0.1`로 바인딩한다.
  - backend에 `SPRING_PROFILES_ACTIVE: local`을 주입한다.
  - `.env.example`에 `POSTGRES_PORT=5432`를 추가한다.
  - 실행: `POSTGRES_PASSWORD=change-me docker compose -f compose.yaml -f compose.local.yaml config`
  - 기대: 병합된 설정에서 DB 포트와 backend local 프로필이 확인된다.

- [ ] **Step 4: 실행·검증·DBeaver 연결 문서화**
  - `.env` 생성, 전체/DB/backend 실행, 상태 확인, 테이블 확인, DBeaver 연결값, 종료와 데이터 초기화를 설명한다.
  - 데이터 초기화 명령에는 로컬 볼륨 삭제 경고를 표시한다.

- [ ] **Step 5: 실제 PostgreSQL 스키마 생성 검증**
  - 충돌 없는 임시 Compose 프로젝트명과 포트로 전체 스택을 실행한다.
  - backend 로그에서 local 프로필과 정상 기동을 확인한다.
  - PostgreSQL의 `public` 스키마에서 현재 엔티티 테이블 목록을 조회한다.
  - 검증용으로 만든 임시 Compose 프로젝트와 볼륨만 정리한다.

- [ ] **Step 6: 전체 품질 게이트와 자체 리뷰**
  - `./scripts/test-postgres.sh`
  - `sh tests/harness/validate-harness-test.sh`
  - `sh scripts/validate-harness.sh`
  - `git diff --check`
  - 비밀값, 범위 이탈, 운영 설정 영향, 문서와 실제 명령의 일치를 검토한다.

- [ ] **Step 7: 커밋과 PR 준비**
  - 커밋 메시지: `chore(db): 로컬 PostgreSQL 개발 환경 구성 (#14)`
  - 원격 작업 브랜치에 push하고 저장소 PR 템플릿으로 `develop` 대상 PR을 생성한다.
