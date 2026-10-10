# Layer Boundaries

## 허용 의존성

```text
controller ──→ service ──→ repository ──→ domain
     │             └────────────────────→ domain
     └────────────→ dto ←─────────────── service
feature layers ─────────────────────────→ global
```

## 금지 규칙

| 출발점 | 금지 대상 |
|---|---|
| Controller | Repository 직접 호출 |
| Service | Controller |
| Repository | Controller, Service |
| Domain | Controller, Service, Repository, DTO, Web·JSON 기술 |
| DTO | 비즈니스 규칙, Repository |
| Global | 기능별 Domain과 비즈니스 규칙 |
| 모든 패키지 | 범용 `util` 패키지 |

Domain에는 JPA 매핑을 허용한다. Entity 응답 금지와 계층별 책임은
[CODE_CONVENTION.md](../CODE_CONVENTION.md)를 원본으로 따른다.

## 기능 간 협력

- Controller는 소속 기능과 관계없이 Service를 호출할 수 있다.
- Service는 소속 기능과 관계없이 Repository를 호출할 수 있다.
- `HousingController → AnnouncementService`, `HousingService → AnnouncementRepository`를 허용한다.
- `AnnouncementController → HousingService`, `AnnouncementService → HousingRepository`도
  함께 구성할 수 있다.
- 기능 간 협력은 양방향일 수 있지만 각 객체의 의존은 Controller → Service → Repository
  방향을 유지한다.
- `HousingController ↔ AnnouncementService`처럼 객체가 서로를 참조하거나 의존 경로가
  순환해서는 안 된다.
- 공유 트랜잭션은 호출 Service가 범위와 실패 처리를 소유한다.
- 결합을 끊어야 할 실제 필요가 생기기 전에는 이벤트를 추가하지 않는다.

## Global

- 공통 설정, 오류 처리와 안정된 기술 지원 코드만 둔다.
- 두 번째 실제 사용처가 생기기 전에는 기능 패키지에 둔다.
- 기능 이름이나 정책을 알아야 하는 코드는 `global`에 두지 않는다.
- 시간, ID와 외부 클라이언트는 소유 기능에 우선 배치한다.

## 검증

- Controller가 Repository를 직접 참조하지 않는지 확인한다.
- Service와 Repository가 Controller에 의존하지 않는지 확인한다.
- Repository가 Service에 의존하지 않는지 확인한다.
- Domain이 상위 계층과 Web·JSON 기술에 의존하지 않는지 확인한다.
- 기능 간 의존도 계층 방향을 유지하고 객체 간 순환 의존이 없는지 확인한다.
- 자동 구조 검사가 없다면 실행하지 못한 검사와 수동 검토 결과를 보고한다.
