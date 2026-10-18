# Testing Strategy

> 기본 규칙과 현재 PostgreSQL 테스트의 실행·격리 기준을 정리한다.

## 테스트 피라미드

| 대상 | 기본 형태 | 검증 내용 |
|---|---|---|
| Domain | 빠른 단위 테스트 | 행위, 불변식, 경계값 |
| Service | 유스케이스 테스트 | 흐름, Repository 협력, 실패 전파 |
| Controller | 계약 테스트 | 검증, 상태, 오류 매핑 |
| Repository | DB 통합 테스트 | 매핑, 쿼리, 제약조건 |
| 외부 연동 | 계약·통합 테스트 | 요청, 응답, timeout, 변환 |

## 작업 순서

Red → Green → Refactor 순서와 완료 증거는 [development-cycle.md](development-cycle.md)를 따른다.
테스트 이름, 단일 동작과 Given-When-Then 규칙은 [CODE_CONVENTION.md](../CODE_CONVENTION.md)가 원본이다.

## 테스트 작성

- 구현 세부사항보다 공개 행동과 결과를 검증한다.
- 시간, 난수, ID와 외부 시스템은 제어 가능한 경계로 주입한다.
- 실제 경계가 중요한 곳을 무분별한 mock으로 대체하지 않는다.
- 같은 fixture가 의도를 가리면 테스트별 최소 데이터로 줄인다.
- 서비스의 입력 검증·응답 조합·요청 중복 제거는 실제 도메인 객체와 필요한 협력 객체의 mock으로 검증한다.
  ID만 필요한 테스트 데이터는 DB에 저장하지 않는다.
- 입력 조합과 건수별 경계값은 단위 테스트에서 충분히 다루고, 통합 테스트에는 실제 쿼리·저장·롤백·복구 시나리오를 남긴다.
  단위 테스트에서 저장소 반환값을 지정한 것은 SQL의 정렬·필터 검증을 대신하지 않는다.

## Spring 컨텍스트와 DB 격리

- SQL 마이그레이션만 검증하면 `@JdbcTest`와 `@AutoConfigureTestDatabase(replace = NONE)`을 사용한다.
  기존 마이그레이션 테스트는 직접 JDBC 연결과 임시 스키마를 관리하므로 `@Transactional(propagation = NOT_SUPPORTED)`로
  테스트 자동 트랜잭션을 끄고, 각 테스트의 `finally`에서 스키마를 정리한다.
- JPA 매핑·쿼리는 `@DataJpaTest`, 여러 계층의 연결·트랜잭션·보안은 필요한 범위의 통합 테스트로 검증한다.
- 같은 설정의 컨텍스트는 재사용한다. `@MockitoBean`, 프로필, 테스트 속성, `@Import`가 달라지면 별도 컨텍스트가 된다.
  테스트 클래스 수만을 기준으로 JVM을 재시작하지 않는다.
- 전체 검사는 하나의 테스트 JVM에서 순차 실행하고, Spring의 기본 컨텍스트 캐시 한도 안에서 재사용한다.
- `PostgresTestDatabases`가 JVM당 PostgreSQL 17 컨테이너 두 개(기본 DB·공유 DB)를 한 번만 기동한다.
  Spring 컨텍스트가 닫혀도 컨테이너는 유지하며, JVM 종료 후 Testcontainers의 Ryuk이 정리한다.
- 테스트 리소스의 `META-INF/spring.factories`에 등록한 `PostgresTestDatabaseInitializer`가 `test` 프로필에
  실제 컨테이너의 호스트·임의 포트·계정을 주입한다. 스키마별 초기화보다 먼저 실행되며 YAML 파일을 수정하지 않는다.
  직접 JDBC/Flyway를 쓰는 테스트도 `PostgresTestDatabases`의 URL을 사용한다.
- 테스트 JPA는 `ddl-auto=create`로 기동 시 테이블을 생성한다. 컨텍스트 종료 시에는 공유 테이블을 삭제하지 않는다.
- 테스트 간 데이터 격리는 기존 롤백·명시적 정리를 유지한다. 검증할 실제 트랜잭션 경계를 mock이나 자동 롤백으로 대체하지 않는다.

## 오류와 회귀

- 버그는 실제 증상을 재현하는 테스트로 시작한다.
- 입력 경계, 부재, 중복, 권한, timeout과 외부 실패를 검증한다.
- 예외 타입만 보지 않고 상태 변화와 외부 효과를 함께 확인한다.
- flaky test는 재실행 성공으로 통과시키지 않고 원인을 격리한다.

## 실행 순서

```text
Docker 실행 → 가까운 테스트 → 기능 테스트 묶음 → ./gradlew --rerun-tasks check
```

IDE와 Gradle 모두 Testcontainers가 DB 기동·준비 대기·정리를 담당한다. 단위 테스트만 실행하면 컨테이너를 띄우지 않는다.
처음 실행할 때는 의존성과 Docker 이미지 다운로드 시간이 추가된다. CI도 같은 실행 경로를 사용한다.
`compose.test.yaml`은 수동 DB 검증용으로 남기며, 자동 테스트는 해당 DB나 고정 포트에 연결하지 않는다.

환경 때문에 실행하지 못한 검사는 실패와 구분해 미검증으로 보고한다.
