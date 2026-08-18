# Backend Architecture

## 기본 형태

기능 중심 모듈러 모놀리스를 사용한다. 각 기능은 독립적인 변경 단위이고,
외부 기술 경계에만 Hexagonal Port와 Adapter를 적용한다.

```text
com.toadzip
├── housing
│   ├── domain
│   │   └── JPA로 매핑된 도메인 Entity
│   ├── application
│   │   ├── port/in
│   │   └── port/out
│   ├── web
│   └── persistence
├── notice
├── eligibility
├── interest
└── shared
```

## 책임 지도

| 영역 | 책임 | 의존 가능 대상 |
|---|---|---|
| Domain | 상태, 행위, 불변식, JPA 매핑 | JDK, JPA, 같은 기능 Domain |
| Application | 유스케이스, Port, 트랜잭션 | Domain |
| Web | HTTP 검증, 변환, 응답 | Inbound Port |
| Persistence | Repository 구현과 복잡한 쿼리 | Outbound Port, Domain Entity, 기술 라이브러리 |

Domain 객체와 JPA Entity는 같은 클래스를 기본값으로 삼는다. Persistence는
Repository 구현과 복잡한 조회를 맡으며, API DTO는 Entity와 항상 분리한다.

## 데이터 흐름

```text
HTTP Request
  → Web DTO 검증
  → Inbound Port
  → Application Use Case
  → Domain Entity
  → Outbound Port
  → Persistence / External Adapter
```

## 구조 선택 기준

- 객체가 소유한 상태와 불변식은 Domain 행위로 표현한다.
- 생성은 정적 팩토리 메서드를 통해 유효한 상태만 허용한다.
- 영속성 제약이 도메인 모델을 심하게 왜곡할 때만 모델 분리를 검토한다.
- 단순 조회는 Domain 규칙을 우회하지 않는 범위에서 얇게 유지한다.
- 인터페이스는 실제 외부 경계나 검증된 교체점에만 만든다.
- 공통 코드는 둘 이상의 기능에서 안정적으로 공유될 때만 추출한다.
- 비동기 이벤트는 동기 결합을 끊을 필요가 증명된 뒤 도입한다.

## 변경 게이트

- 새 클래스의 책임을 한 문장으로 설명할 수 있다.
- 공개 API와 트랜잭션 경계가 명확하다.
- 영향을 받는 기능과 마이그레이션 경로를 설명한다.
- 금지 의존성은 `module-boundaries.md`에 따라 ArchUnit으로 검사한다.
