# PostgreSQL 테스트 환경 설계

## 목표

- H2를 제거하고 모든 Spring/JPA 테스트를 PostgreSQL 17에서 실행한다.
- 개발자와 GitHub Actions가 동일한 한 개의 명령으로 테스트 DB 기동, 검증, 정리를 수행한다.
- 개발용 `toadzip` 데이터와 테스트용 `toadzip_test` 데이터를 물리적으로 분리한다.

## 범위

- 독립적인 `compose.test.yaml`과 PostgreSQL 테스트 실행 스크립트
- 테스트 전용 Spring 프로필과 PostgreSQL 연결
- H2 의존성 및 H2 전용 테스트 제거
- 기존 Spring/JPA 테스트의 PostgreSQL 전환
- GitHub Actions와 테스트 실행 문서의 명령 통일

IntelliJ IDEA 또는 DBeaver 연결 방법은 작업 범위에 포함하지 않는다.

## 구성

### 테스트 PostgreSQL

- 이미지: `postgres:17-alpine`
- Compose 프로젝트명: `toadzip-test`
- Database/User/Password: `toadzip_test`
- 호스트 바인딩: `127.0.0.1:${TEST_POSTGRES_PORT:-55432}:5432`
- 저장소: `tmpfs`를 사용해 실행마다 빈 데이터 디렉터리를 제공한다.
- health check: `pg_isready`가 성공한 뒤에만 테스트를 시작한다.
- 개발용 `compose.yaml`, `compose.local.yaml`, `postgres_data` 볼륨을 참조하지 않는다.

### 실행 스크립트

루트의 `scripts/test-postgres.sh`가 테스트 생명주기를 소유한다.

1. `toadzip-test` 프로젝트의 PostgreSQL을 `up --detach --wait`로 시작한다.
2. 인자가 없으면 `backend/gradlew check`를 실행한다.
3. 인자가 있으면 그대로 Gradle에 전달해 focused test를 지원한다.
4. 성공, 실패, 인터럽트와 관계없이 해당 테스트 프로젝트만 종료한다.
5. 실패 시 정리 전에 PostgreSQL 로그를 출력한다.

기본 명령은 다음과 같다.

```shell
./scripts/test-postgres.sh
```

focused test는 다음 형태를 사용한다.

```shell
./scripts/test-postgres.sh test --tests com.toadzip.backend.DomainJpaPersistenceTest
```

### Spring 테스트 프로필

`application-test.yml`은 테스트 PostgreSQL 연결과 Hibernate `create-drop` 전략을 제공한다.
Spring/JPA 통합 테스트는 `test` 프로필을 명시적으로 활성화한다. 테스트 DB는 공유 스키마를
사용하므로 Gradle test fork와 JUnit 실행은 직렬로 유지한다.

로컬 프로필 계약 테스트는 PostgreSQL 테스트 DB에 연결하되 `local` 프로필의
`ddl-auto: update`를 그대로 읽는다. 테스트 URL은 `toadzip_test`만 허용하며 개발용 DB를
대상으로 삼지 않는다.

## 테스트 전략

- DB를 사용하지 않는 도메인 단위 테스트는 기존처럼 독립 실행한다.
- Spring context, JPA metamodel, native SQL과 제약조건 테스트는 PostgreSQL에서 실행한다.
- PostgreSQL metadata를 확인하는 테스트를 추가해 H2나 다른 DB로의 회귀를 차단한다.
- RED 단계에서는 기존 H2 실행에서 PostgreSQL 제품명 검증이 실패함을 확인한다.
- GREEN 단계에서는 `scripts/test-postgres.sh`가 PostgreSQL을 기동하고 전체 `check`를 통과한다.

## CI

GitHub Actions의 백엔드 검증 단계는 로컬과 동일하게 `./scripts/test-postgres.sh`를 실행한다.
기존 하네스 및 PR 계약 검사는 유지한다. CI runner의 Docker에 생성되는 테스트 프로젝트는
스크립트의 trap으로 성공과 실패 모두에서 정리한다.

## 안전과 실패 처리

- 테스트 포트는 loopback에만 노출한다.
- 테스트 자격증명은 폐기 가능한 고정 예시값이며 운영 비밀을 사용하지 않는다.
- 스크립트는 명시적인 Compose 프로젝트명과 파일만 대상으로 정리한다.
- PostgreSQL 기동 실패나 Gradle 실패를 성공으로 처리하지 않는다.
- 테스트 종료 시 개발용 컨테이너, 볼륨과 데이터에는 어떤 정리 명령도 적용하지 않는다.

## 완료 조건

- `backend/build.gradle`과 테스트 코드에 H2 참조가 없다.
- DB-backed test가 PostgreSQL 17에서 실행된다는 자동 검증이 존재한다.
- `./scripts/test-postgres.sh` 한 번으로 기동, 전체 검사와 정리가 완료된다.
- 테스트 실패 시에도 테스트 Compose 프로젝트가 남지 않는다.
- GitHub Actions가 동일한 스크립트로 통과한다.
- IntelliJ 또는 DBeaver 사용법은 변경 파일에 추가되지 않는다.
