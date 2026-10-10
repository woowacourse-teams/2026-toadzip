# 백엔드 아키텍처

기능별 패키지 안에 `controller`, `service`, `repository`, `domain`, `dto`를 둔다.
계층별 책임은 [코드 컨벤션](../CODE_CONVENTION.md)을 따른다. 빈 계층을 미리 만들지 않는다.

## 의존성

| 출발점 | 허용 대상 |
|---|---|
| Controller | Service, DTO |
| Service | Repository, Domain, DTO |
| Repository | Domain, DB·외부 API |
| Domain | JPA 매핑. 상위 계층·DTO·Web·JSON에는 의존하지 않음 |
| DTO | 전달 데이터. 비즈니스 규칙·Repository에는 의존하지 않음 |
| Global | 공통 기술 코드. 기능별 Domain·규칙에는 의존하지 않음 |

Service는 Controller, Repository는 Service·Controller에 의존하지 않는다.
범용 `util` 패키지를 만들지 않는다.

### 기능 간 협력

Controller는 다른 기능의 Service, Service는 다른 기능의 Repository를 호출할 수 있다.
예를 들어 `HousingController → AnnouncementService`, `HousingService → AnnouncementRepository`를 허용한다.
반대 방향의 기능 협력도 가능하지만 객체 간 순환 의존은 허용하지 않는다.
공유 트랜잭션의 범위와 실패 처리는 호출 Service가 책임진다.

### 공통 코드

둘 이상의 실제 사용처가 있는 기술 코드만 `global`에 둔다.
기능 이름이나 정책을 알아야 하는 코드, 시간·ID·외부 클라이언트는 소유 기능에 우선 둔다.
대체 구현·테스트 경계가 필요할 때만 인터페이스를 만들고, 동기 결합을 끊어야 할 때만 이벤트를 도입한다.

## 데이터 저장

Domain과 JPA Entity는 같은 클래스를 기본으로 사용한다. 기본 생성자는 `protected`, 정상 생성은 정적 팩토리를 사용한다.
스키마·JPA 제약이 도메인 행위를 왜곡하거나 저장 모델의 구조·수명이 달라지는 실제 문제가 있을 때 분리를 검토한다.
Entity 분리는 PR에 문제·변환 책임·테스트 범위를 남긴다.

쓰기 트랜잭션은 Service가 소유한다. 외부 API를 기다리는 동안 DB 트랜잭션을 오래 유지하지 않는다.
재시도에는 멱등성·중복 처리 기준을 두고, 동시 갱신은 실제 충돌에 맞춰 잠금을 사용한다.
조회 결과는 정렬과 동률 순서를 고정하고 인덱스는 실제 쿼리·실행 계획을 근거로 추가한다.

스키마 변경은 기존 데이터·배포 순서·이전 앱 호환·복구 경로를 확인한다.
적용 절차는 [Flyway](flyway-adoption.md), 수집 원천 이관은 [수집 DB 전환](ingest-branch-db-upgrade.md)을 따른다.
