# 백엔드 개발 흐름과 검증

명령은 `backend/`에서 실행한다. 프론트엔드의 별도 기준은 [프론트엔드 검증](../frontend/testing.md)을 따른다.

## 개발 흐름

### 작업 계약

작업 전에 사용자에게 달라지는 결과(Goal), 관련 코드·테스트·결정(Context), 기술·범위·안전 제약(Constraints), 완료를 증명할 동작과 명령(Done)을 정한다. 별도 양식을 작성할 필요는 없다.

결과를 바꾸는 모호함만 구현 전에 질문하고, 나머지는 최소 가정으로 진행한다.

### 탐색

1. [공용 문서 목차](../../README.md)에서 필요한 문서를 고른다.
2. production code와 가까운 test를 함께 읽는다.
3. Controller부터 Service, Repository와 Domain까지 호출·데이터 흐름을 추적한다.
4. 공개 계약, 트랜잭션, 외부 연동과 실패 경로를 확인한다.
5. 발견 사실과 추론을 구분해 파일 근거를 남긴다.

### 설계 게이트

1. 변경할 객체마다 책임, 입력, 출력과 의존성을 한 문장으로 적는다.
2. 기능 경계와 Controller → Service → Repository·Domain 의존 방향을 확인한다.
3. 변경 파일과 테스트 파일을 정하고 각 파일의 소유자를 한 명으로 둔다.
4. 가장 작은 완성 범위를 선택하고 미래 확장용 코드는 제외한다.
5. 여러 파일 또는 공개 계약을 변경하면 구현 전에 짧은 계획을 공유한다.

### Red → Green → Refactor

| 단계 | 해야 할 일 | 다음 단계 조건 |
|---|---|---|
| Red | 관찰 가능한 행동의 최소 실패 테스트 작성 | 빠진 행동 때문에 실패 |
| Green | 정상·오류 경로를 포함한 최소 구현 | 새 테스트와 관련 테스트 통과 |
| Refactor | 이름, 책임, 중복, 응집도 개선 | 행동 보존 재검증 |

- 컴파일·설정 오류를 Red 증거로 사용하지 않는다.
- 테스트를 맞추는 하드코딩이나 계층 우회를 금지한다.
- 새 행동이 필요하면 별도의 Red 단계로 돌아간다.

## 테스트 피라미드

| 대상 | 기본 형태 | 검증 내용 |
|---|---|---|
| Domain | 빠른 단위 테스트 | 행위, 불변식, 경계값 |
| Service | 유스케이스 테스트 | 흐름, Repository 협력, 실패 전파 |
| Controller | `@WebMvcTest` | 검증, 상태, 오류 매핑 |
| Repository | DB 통합 테스트 | 매핑, 쿼리, 제약조건 |
| 외부 연동 | 계약·통합 테스트 | 요청, 응답, timeout, 변환 |

## 테스트 작성

- 작성 순서는 위 개발 흐름을, 이름·Given-When-Then은 [백엔드 코드 컨벤션](../../../backend/CODE_CONVENTION.md)을 따른다.
- 공개 행동과 결과를 검증한다. 필드 목록·기동·상태 코드만 중복 확인하면 기존 행동 검증에 통합한다.
- 시간, 난수, ID와 외부 시스템은 제어 가능한 경계로 주입한다.
- 실제 경계가 중요한 곳을 무분별한 mock으로 대체하지 않는다.
- 같은 fixture가 의도를 가리면 테스트별 최소 데이터로 줄인다.
- 서비스의 입력 검증·응답 조합·요청 중복 제거는 실제 도메인 객체와 mock으로 검증한다. ID만 필요한 데이터는 DB에 저장하지 않는다.
- 입력 조합과 건수별 경계값은 단위 테스트에서 충분히 다루고, 통합 테스트에는 실제 쿼리·저장·롤백·복구 시나리오를 남긴다.
  단위 테스트에서 저장소 반환값을 지정한 것은 SQL의 정렬·필터 검증을 대신하지 않는다.
- `@SpringBootTest`는 여러 계층의 실제 연결이나 애플리케이션 설정 자체를 검증할 때 선택한다.
  JPA는 `@DataJpaTest`, JDBC는 `@JdbcTest`, HTTP 계약·권한은 실제 보안 설정을 가져온 `@WebMvcTest`를 검토한다.
- 실제 커밋·롤백이나 여러 연결의 경합을 검증하는 slice 테스트는 자동 테스트 트랜잭션을 끈다.
  기존 명시적 정리와 실제 서비스의 트랜잭션 경계를 유지한다.

## Spring 컨텍스트 재사용

- 하나의 테스트 JVM에서 순차 실행한다. 클래스 수를 기준으로 JVM을 재시작하거나 기본 캐시 한도 32를 늘리지 않는다.
- 같은 검증 환경은 프로필·속성·`@Import`·mock 구성을 맞춘다. 기본 JPA 테스트는 `showSql = false`로 SQL 출력을 끈다.
  같은 빈을 대체하는 `@MockitoBean`·`@MockitoSpyBean`은 필드명·qualifier도 맞춘다. 이 차이도 캐시 키에 반영된다.
- 실제 보안 필터, Clock 기준 시각, 기능 플래그, 독립 스키마가 다르면 별도 설정을 유지한다. 공유를 위해 mock을 늘리지 않는다.
- `@DirtiesContext`를 사용할 때 재기동이 필요한 이유와 상태 복원 가능성을 확인한다. DB 데이터 정리는 롤백·명시적 정리를 우선한다.
  실제 CSRF 쿠키 저장소의 상태 변경이나 삭제한 임시 스키마에 연결된 컨텍스트는 재사용 전에 복원이 필요하다.
- 느려졌을 때 아래 명령으로 측정한다. `missCount`는 캐시에서 찾지 못한 누적 횟수이며, 고유 설정 수나 클래스 수가 아니다.
  성공 실행의 기동 횟수와 함께 비교하고, `hitCount`는 테스트 수로 해석하지 않는다. 캐시 한도·`@DirtiesContext`도 확인한다.
  수동 `SpringApplicationBuilder` 재기동은 캐시 통계 밖이다. 기준은 [Spring 컨텍스트 캐시 문서](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)를 따른다.

```sh
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_TEST_CONTEXT_CACHE=DEBUG \
  ./gradlew test --rerun check --build-cache --info --profile
```

## DB 격리

- JPA·JDBC slice는 `@AutoConfigureTestDatabase(replace = NONE)`으로 실제 PostgreSQL을 사용한다.
- SQL 마이그레이션은 `@JdbcTest`와 `@Transactional(propagation = NOT_SUPPORTED)`를 사용하고, 직접 만든 임시 스키마를 `finally`에서 정리한다.
- `PostgresTestDatabases`가 JVM당 PostgreSQL 17 컨테이너 두 개(기본 DB·공유 DB)를 한 번만 기동한다.
  Spring 컨텍스트가 닫혀도 컨테이너는 유지하며, JVM 종료 후 Testcontainers의 Ryuk이 정리한다.
  작은 fixture에서 컴파일 비용이 큰 JIT는 테스트 DB에서 끈다. 운영 쿼리 성능은 운영에 맞는 설정·데이터로 별도 측정한다.
- 테스트 리소스의 `META-INF/spring.factories`에 등록한 `PostgresTestDatabaseInitializer`가 `test` 프로필에 접속 정보를 주입한다.
  스키마별 초기화보다 먼저 실행되며 YAML 파일을 수정하지 않는다. DB가 없는 MVC slice에는 `test` 프로필을 불필요하게 붙이지 않는다.
  직접 JDBC/Flyway를 쓰는 테스트도 `PostgresTestDatabases`의 URL을 사용한다.
- 테스트 JPA는 `ddl-auto=create`로 기동 시 테이블을 생성한다. 컨텍스트 종료 시에는 공유 테이블을 삭제하지 않는다.

## 오류와 회귀

- 버그는 실제 증상을 재현하는 테스트로 시작한다.
- 입력 경계, 부재, 중복, 권한, timeout과 외부 실패를 검증한다.
- 예외 타입만 보지 않고 상태 변화와 외부 효과를 함께 확인한다.
- flaky test는 재실행 성공으로 통과시키지 않고 원인을 격리한다.

## 실행 순서

1. 변경한 테스트 클래스 또는 가장 가까운 Gradle task를 실행한다.
2. 영향 기능의 테스트 묶음을 실행한다.
3. Docker 실행 상태에서 `./gradlew test --rerun check --build-cache`를 실행한다. PostgreSQL은 Testcontainers가 준비한다.
4. 테스트 JVM 종료 후 DB 컨테이너 자동 정리를 확인한다.
5. 계층·포맷·정적 분석·통합 테스트 결과를 확인한다. 계층 자동 검사가 없으면 [구조 검증](architecture.md#검증)을 수동 수행하고 결과를 보고한다.
6. `git diff --check`와 최종 diff에서 범위 이탈·회귀·보안·비밀 노출을 검토한다.

- IDE·Gradle·CI 모두 Testcontainers가 DB를 준비·정리한다. 첫 실행의 다운로드 비용을 구분하며, 단위 테스트는 DB를 띄우지 않는다.
- `compose.test.yaml`은 수동 검증용이다. 자동 테스트는 해당 DB나 고정 포트에 연결하지 않는다.
- `test --rerun`은 테스트만 새로 실행하고 컴파일 캐시는 유지한다. CI의 테스트·프로파일 결과로 같은 조건의 실행 시간을 비교한다.
- 환경 때문에 실행하지 못한 검사는 실패와 구분해 미검증으로 보고한다.

- 존재하는 검사를 삭제하거나 완화해 통과시키지 않는다.
- 실제 명령이 다르면 Gradle task 목록에서 확인한 명령을 사용한다.

완료는 현재 작업 트리에서 새로 얻은 증거로만 판정한다.

## 게이트

| 영역 | 통과 조건 |
|---|---|
| 범위 | Goal·Constraints·Done 충족, 관련 없는 변경 없음 |
| 테스트 | 새 행동의 Red 확인, 관련·전체 검사 통과 |
| 구조 | 금지 의존성 없음, 공개 경계와 트랜잭션 명확 |
| 품질 | 백엔드 코드 컨벤션 준수, 책임·이름 명확, 죽은 코드 없음 |
| 보안 | 입력·권한 검증, 비밀·개인정보 노출 없음 |
| 운영 | 실패가 로그·메트릭·트레이스로 진단 가능 |

## 완료 체크리스트

- [ ] 사용자 관점의 결과가 테스트 또는 실행 증거로 확인된다.
- [ ] Controller·Service·Repository·Domain 의존 방향이 유지된다.
- [ ] API·데이터·설정 변경의 호환성과 복구 경로를 확인했다.
- [ ] 오류·경계값·권한·외부 실패 경로를 검증했다.
- [ ] diff에 비밀, 임시 로그, 생성 산출물과 범위 이탈이 없다.
- [ ] 독립 리뷰의 blocker가 남아 있지 않다.

## 완료 보고

사용자 관점의 결과·핵심 변경 파일, 실행 명령·성공 또는 실패 결과, 확인한 구조·경계, 미실행 검사와 이유, 잔여 위험을 보고한다. 정해진 항목명이나 양식은 요구하지 않는다.
검증하지 못한 항목은 통과로 간주하지 않는다. 같은 실패가 반복되면 문서보다 자동 검사로 규칙을 승격한다.
