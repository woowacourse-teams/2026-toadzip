# Backend Code Conventions

백엔드 코드 규칙의 원본은 [`backend/CODE_CONVENTION.md`](../CODE_CONVENTION.md)다.
이 문서는 에이전트가 구현 전에 원본 규칙을 찾도록 연결하고 하네스 관점의 적용 기준만 정의한다.

## 적용 순서

1. Java 변경 전 `backend/CODE_CONVENTION.md`를 읽는다.
2. 현재 코드와 가까운 테스트에서 기존 패턴을 확인한다.
3. 새 규칙이 필요하면 이 문서가 아니라 원본 코드 컨벤션에 추가한다.
4. 규칙을 예외 처리하면 이유와 영향 범위를 PR에 기록한다.

## 생성과 경계

- Domain 객체와 JPA Entity는 같은 클래스를 기본값으로 사용한다.
- 외부 생성은 정적 팩토리를 사용하고 생성자에서 불변식을 우회하지 않는다.
- 새 객체 생성은 `create`, 값 조합은 `of`, 변환은 `from`을 우선한다.
- JPA 기본 생성자는 `protected`로 두고 setter로 불완전한 상태를 만들지 않는다.
- 영속성 제약이 모델을 왜곡할 때만 `persistence.md` 기준으로 분리한다.

## 완료 확인

- 객체가 데이터 노출보다 행위와 불변식을 소유하는지 확인한다.
- Controller, Service, Repository 책임과 Entity 응답 금지 규칙을 확인한다.
- 포맷과 정적 검사는 현재 Gradle 프로젝트에 실제로 존재하는 task만 실행한다.
- 코드와 문서가 다르면 현재 동작을 확인하고 같은 변경에서 원본 문서를 갱신한다.
