# Backend Architecture

## 기본 형태

도메인별 패키지 안에 계층을 두는 레이어드 아키텍처를 사용한다.
패키지는 실제 코드가 필요할 때만 만들고 빈 계층을 미리 생성하지 않는다.

```text
com.toadzip.backend
├── housing
│   ├── controller
│   ├── service
│   ├── repository
│   ├── domain
│   └── dto
├── notice
├── eligibility
├── interest
└── global
```

## 의존 방향

```text
Controller → Service → Repository → Database / External API
                 └──→ Domain ←─────┘
Controller ↔ DTO
```

세부 허용 의존성은 [layer-boundaries.md](layer-boundaries.md)에서 확인한다.

## 패키지 기준

- `controller`, `service`, `repository`의 책임은 [CODE_CONVENTION.md](../CODE_CONVENTION.md)를 따른다.
- `repository`의 데이터 접근 대상에는 DB와 외부 API가 포함된다.
- `domain`은 상태, 행위와 불변식을 소유한다.
- `dto`: 외부 계약과 계층 간 전달 데이터
- `global`: 설정, 공통 오류 처리 등 기능에 속하지 않는 기술 코드

## 구조 선택 기준

- 비즈니스 규칙은 Controller나 Repository가 아니라 Domain에 둔다.
- 기능 간 협력은 Service에서 수행하고 다른 기능의 Repository를 직접 호출하지 않는다.
- 인터페이스는 대체 구현이나 테스트 경계가 실제로 필요할 때만 만든다.
- 공통 코드는 둘 이상의 기능에서 안정적으로 공유될 때만 `global`로 옮긴다.
- 비동기 이벤트는 동기 결합을 끊을 필요가 증명된 뒤 도입한다.

## 변경 게이트

- 새 클래스의 책임과 소속 기능·계층을 한 문장으로 설명할 수 있다.
- 의존 방향과 트랜잭션 경계가 명확하다.
- 영향을 받는 기능과 데이터·API 호환성을 설명한다.
- 계층 검사가 없으면 구조 변경을 수동 검증으로 보고한다.
