# 백엔드 에이전트 지도

## 목적

- Java 25와 Spring Boot 4 백엔드만 설계, 구현, 검증한다.
- 사람과 에이전트는 같은 저장소 문서와 자동 검사를 기준으로 작업한다.
- 이 파일은 지도이며 세부 기준의 원본은 `docs/`에 둔다.

## 읽기 순서

1. 모든 작업은 `docs/README.md`에서 필요한 문서를 찾는다.
2. 제품 맥락은 `docs/backend-context.md`를 먼저 확인한다.
3. 구현은 `docs/development-cycle.md`의 작은 검증 루프를 따른다.
4. 구조 변경은 `docs/architecture.md`와 `docs/module-boundaries.md`를 읽는다.
5. Git 작업은 `docs/git-conventions.md`를 따른다.
6. 완료 전 `docs/quality-gates.md`의 증거를 새로 확인한다.

## 절대 경계

- Domain 객체와 JPA Entity는 기본적으로 하나의 클래스로 유지한다.
- Domain에는 JPA 매핑만 허용하며 HTTP, JSON과 Web 기술을 넣지 않는다.
- Web은 Application 유스케이스를 통해 진입하며 Repository를 직접 호출하지 않는다.
- 외부 기술은 Application이 소유한 Port를 구현하는 Adapter로 격리한다.
- 기능 간 협력은 공개 Application API만 사용한다.
- API DTO는 Entity와 분리하고 Entity를 응답으로 직접 반환하지 않는다.

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

- 메인 에이전트가 결정, 파일 소유권, 통합, 최종 검증을 책임진다.
- 조사와 리뷰는 읽기 전용 역할에 맡기고 구현 파일은 소유자를 한 명만 둔다.
- 파괴적 작업, 외부 쓰기, 비밀 접근은 권한과 대상을 먼저 확인한다.
- 테스트, 정적 검사, 보안 게이트를 약화해 통과시키지 않는다.
