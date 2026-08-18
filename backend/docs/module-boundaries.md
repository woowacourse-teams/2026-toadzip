# Module Boundaries

## 허용 의존성

```text
web ───────→ application ───────→ domain
persistence ──implements───────→ application.port.out
external ─────implements───────→ application.port.out
feature A ──public API only────→ feature B.application
```

## 금지 규칙

| 출발점 | 금지 대상 |
|---|---|
| Domain | Spring Web, HTTP, JSON, 메시징 타입, Repository 구현 |
| Application | 구체 Adapter, Controller, Persistence 구현 |
| Web | Repository, 다른 기능 내부 타입 |
| Persistence | Controller, 다른 기능 Repository |
| 모든 기능 | 순환 의존, 범용 `util` 패키지 |

## 기능 공개면

- 다른 기능에는 Application API와 입력·출력 타입만 공개한다.
- Domain Entity, Repository, Adapter 구현을 기능 밖으로 노출하지 않는다.
- 기능 간 호출은 호출자의 트랜잭션 요구를 명시한다.
- 부수 효과가 핵심 결과와 다른 수명을 가지면 이벤트 분리를 검토한다.

## 공유 코드

- `shared`는 기술 편의가 아니라 안정된 공통 개념만 소유한다.
- 두 번째 실제 사용처가 생기기 전에는 기능 내부에 둔다.
- 시간, ID 생성, 외부 클라이언트는 소유 기능의 Port로 노출한다.
- 공통 예외가 HTTP나 DB 구현 세부사항을 담지 않게 한다.

## ArchUnit 최소 검사

- Domain 패키지는 JPA 외 프레임워크와 Web 기술에 의존하지 않는다.
- Web 패키지가 Persistence 패키지에 의존하지 않는다.
- Application 패키지가 Adapter 구현에 의존하지 않는다.
- 기능 내부 패키지를 다른 기능이 직접 참조하지 않는다.
- 순환 의존이 없다.

ArchUnit이 아직 구성되지 않았다면 구조 변경은 미검증으로 보고한다.
