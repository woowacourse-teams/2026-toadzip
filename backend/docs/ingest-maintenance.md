# Ingest 처음 읽기

`ingest`는 원천 수집 → 제품 데이터 정제·보강과 그 작업의 실행 관리를 담당한다.
전체 클래스 목록을 읽기보다 아래 두 파일에서 시작해 필요한 단계로 내려간다.

## 시작할 파일 두 개

1. [DataPipelineType](../src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineType.java)의 `steps()`: 네 작업의 단계와 순서.
2. [DataPipelineRunner](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineRunner.java)의 `execute()`: 단계별로 실제 호출하는 서비스.

Spring은 생성자 주입으로 객체를 연결한다. `Runner.run()`에서 단계 실행 → `StateService` 결과 저장을 따라간다.
단계별 부분 실패·호출 제한·중단 확인은 `Runner`, 실행 전체의 시작·완료·실패·잠금은 `ExecutionService`가 맡는다.

## 작업을 고른 뒤 내려갈 곳

| 작업 | 처리 순서와 첫 서비스 |
|---|---|
| 단지 수집 | [MyHomeComplexCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/service/MyHomeComplexCollectionService.java) → LH 임대목록 수집 |
| 단지 정제 | [MyHomeComplexMappingService](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingService.java) → LH 세대수 보강 |
| 공고 수집 | [MyHomeAnnouncementCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/service/MyHomeAnnouncementCollectionService.java) → LH 목록 → 공급 → 상세 수집 |
| 공고 정제 | [MyHomeAnnouncementMappingService](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementMappingService.java) → LH 공고 보강 |

수집은 원천을 보관하고 정제·보강은 그 원천을 읽어 단지·주택형·공고에 반영한다.
각 작업은 별도 실행이며 자동 실행의 연결 조건은 [실행 문서](data-pipeline-execution.md)를 따른다.

## 공고 수집을 따라가는 순서

MyHome은 `MyHomeAnnouncementCollectionService` 한 파일에서 다음 순서로 읽는다.

```text
collect → collectUnlocked → collectSupplyType → fetchCompleteSupplyType → parsePage
실행 잠금 → 7개 공급유형 → 유형 단위 처리 → 전체 페이지 조회 → 응답 검증
```

각 유형의 모든 페이지가 유효할 때만 저장하고, 모든 유형이 성공한 경우에만 전체 조회를 완료한다.
호출 제한에 도달하면 남은 공급유형을 중단한다. 저장은 별도 `MyHomeSourceStore` 트랜잭션이다.

LH 공급·상세는 [LhAnnouncementExternalCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementExternalCollectionService.java)의 `collect()`에서 시작한다.

```text
collect → collectAnnouncements → collectBatch → collectCandidates → collectRequests
실행 잠금 → 원천 묶음 조회 → 대상 선정 → 갱신 여부 확인 → 병렬 실행과 결과 회수
```

요청 하나는 [LhAnnouncementCandidateCollector](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementCandidateCollector.java)의 `collect()` → `collectAndStore()`를 읽는다.
이 파일에서 외부 조회·파싱의 재시도, 원천 저장, 완료 기록까지 확인할 수 있다.
대상·공급코드는 `LhAnnouncementCollectionCandidateResolver`, 갱신 주기는 `LhAnnouncementRefreshPolicy`가 정한다.

## 운영 문제를 볼 때

| 질문 | 시작점 |
|---|---|
| 실행·중단·복구 | [DataPipelineExecutionService](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java) → `StateService`·실행 잠금 |
| 동기 작업의 충돌 범위 | [IngestOperationLock](../src/main/java/com/toadzip/backend/ingest/pipeline/repository/IngestOperationLock.java)의 `Operation`과 호출 서비스 |
| 자동 실행 시점·선행 작업 | [DataPipelineScheduleOrchestrator](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineScheduleOrchestrator.java) |
| 실패의 해결·재발 | [IngestFailure](../src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailure.java)의 상태 전이, [IngestFailureReconciler](../src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailureReconciler.java)의 일괄 조정 |
| 위치 파일 적재 | [LocationSummaryImportService](../src/main/java/com/toadzip/backend/ingest/location/service/LocationSummaryImportService.java) → Parser·Store |

## 클래스 분리 기준

한 흐름에서만 사용하는 보조 절차는 담당 서비스의 private 메서드를 우선한다.
독립된 규칙·재사용처·트랜잭션 경계가 있을 때 클래스로 분리한다. 줄 수만으로 분리하지 않는다.
원천별 파싱 규칙, 병렬 실행의 공고별 직렬화, 실행 소유권·중단·복구는 보존한다.
동기 잠금은 한 구현을 공유한다. 공고 매핑과 LH 보강은 같은 DB 키를 쓰고 나머지 작업별 키는 구분한다.
실행 서비스와 상태 저장, 단지 Writer의 `REQUIRES_NEW`, 마이홈 매핑과 LH 보강의 원자적 저장은 합치지 않는다.
실패 Entity는 테이블·사유·원천 식별자를 소유하고 공통 상태·시각·횟수는 `IngestFailure`에서 상속한다.
실패 조정은 조회된 행의 마지막 값, 보충 이력의 첫 값, 관찰된 행의 마지막 값을 선택한다.

검증은 [품질 게이트](quality-gates.md)를 따른다. Gradle `check`에는 Java 스타일·계층 전용 검사기가 없으므로
테스트 통과를 모든 컨벤션의 자동 검증으로 해석하지 않는다. 상세 정책은 [문서 지도](README.md)에서 찾는다.
