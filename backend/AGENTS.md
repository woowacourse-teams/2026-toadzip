# 백엔드 에이전트 지도

## 목적

- Java 25와 Spring Boot 4 백엔드만 설계, 구현, 검증한다.
- 사람과 에이전트는 같은 저장소 문서와 자동 검사를 기준으로 작업한다.
- 이 파일은 지도이며 세부 기준은 원본 문서와 `docs/`에 둔다.

## 읽기 순서

1. [docs/README.md](docs/README.md)에서 작업에 필요한 문서를 찾는다.
2. 제품 맥락은 루트 [SERVICE_OVERVIEW.md](../SERVICE_OVERVIEW.md)에서 확인한다.
3. 구현은 [docs/development-cycle.md](docs/development-cycle.md)의 작은 검증 루프를 따른다.
4. 구조 변경은 [docs/architecture.md](docs/architecture.md)와 [docs/layer-boundaries.md](docs/layer-boundaries.md)를 읽는다.
5. Java 규칙은 [CODE_CONVENTION.md](CODE_CONVENTION.md), Git 작업은 루트 [CONTRIBUTING.md](../CONTRIBUTING.md)를 따른다.
6. 완료 전 [docs/quality-gates.md](docs/quality-gates.md)의 증거를 새로 확인한다.

## 절대 경계

- 기능을 먼저 나누고 내부를 `controller`, `service`, `repository`, `domain`, `dto`로 구분한다.
- 의존성은 Controller → Service → Repository·Domain 방향으로만 흐른다.
- 계층별 책임과 Entity 응답 금지는 [CODE_CONVENTION.md](CODE_CONVENTION.md)를 원본으로 따른다.
- Domain은 HTTP, DTO, Controller, Service와 Repository에 의존하지 않는다.
- 기능 간 협력도 기능 소속과 관계없이 Controller → Service → Repository 방향을 유지하고
  객체 간 순환 의존을 만들지 않는다.
- `global`에는 공통 기술 설정만 두고 기능별 비즈니스 규칙을 넣지 않는다.

## 작업 계약

| 단계 | 필수 산출물 |
|---|---|
| 시작 | Goal, Constraints, Done |
| 탐색 | 관련 코드·테스트·문서 근거 |
| 변경 | Red → Green → Refactor 증거 |
| 완료 | 실행 명령, 결과, 미검증 항목, 위험 |

## 구현 원칙

- 한 번에 하나의 관찰 가능한 동작만 변경한다.
- 현재 요구에 필요하지 않은 계층, 옵션, 추상화, 의존성을 추가하지 않는다.
- 생성자 주입과 불변 객체를 우선하고 예외를 성공으로 위장하지 않는다.
- production dependency, 공개 계약, 데이터 변경은 사전 승인을 받는다.
- 관련 없는 사용자 변경과 생성 산출물을 수정하지 않는다.

## 협업과 안전

- 메인 에이전트가 결정, 파일 소유권, 구현, 통합과 최종 검증을 책임진다.
- 조사와 리뷰만 읽기 전용 역할에 맡긴다.
- 파괴적 작업, 외부 쓰기, 비밀 접근은 권한과 대상을 먼저 확인한다.
- 테스트, 정적 검사, 보안 게이트를 약화해 통과시키지 않는다.
