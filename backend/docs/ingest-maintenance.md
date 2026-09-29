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
각 작업은 별도 실행이며 수집 완료를 확인한 뒤 해당 정제를 직접 시작한다.

단지 정제는 `MyHomeComplexMappingService.mapAll()` → `MyHomeComplexMappingProcessor.mapAll()`에서
원천을 묶는다. 일반 단지는 위치정보 DB의 좌표를 읽어 저장하고, 확인된 통합 단지는 기존 주소를 검증해
금액·주택형만 갱신한다. 저장은 단지별 트랜잭션이다.
신규 주소의 좌표가 필요하면 수집 후 위치정보요약DB ZIP을 관리자 API로 업로드한다. 업로드는 정제 파이프라인에 포함되지 않는다.
실패는 작업이 끝날 때 현재 원천과 대조해 갱신하며 이전 실패 이력은 보존한다.
단계별 후보 준비·배치 실행 API는 제공하지 않는다.

## 공고 수집·정제를 따라가는 순서

MyHome은 `MyHomeAnnouncementCollectionService.collect()` → `collectSupplyType()` → `fetchCompleteSupplyType()`을 읽는다.
7개 공급유형별 모든 페이지가 유효할 때 `MyHomeSourceStore` 트랜잭션으로 저장한다. 호출 제한은 남은 유형을 중단한다.

LH 공급·상세는 [LhAnnouncementExternalCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementExternalCollectionService.java)의 `collect()`에서 시작한다.
`collectAnnouncements()` → `collectBatch()`에서 대상을 고르고 `collectRequests()`에서 병렬 실행과 결과를 회수한다.
요청 하나는 `LhAnnouncementCandidateCollector.collect()` → `collectAndStore()`에서 조회·파싱 재시도와 저장을 처리한다.
대상·공급코드는 `LhAnnouncementCollectionCandidateResolver`, 갱신 주기는 `LhAnnouncementRefreshPolicy`가 정한다.

공고 정제는 `MyHomeAnnouncementMappingService.mapAll()`에서 공고별로 다음 경로를 따른다.

```text
SourceMapper → SupplyRowResolver → MappingWriter → EnrichmentWriter.writeAfterMapping
공통값 검증 → LH 연결·공급 원천 준비 → 공고·공급행 저장 → 변경된 기존 LH 공고 보강
```

준비한 LH 요청·공급 원천·과거 원천 구분은 재사용한다. 주택형 변경은 공급행을 수정할 때 기록한다.
현재 원천 중 기관이 빈 행도 매핑에는 포함하지만 LH 보강에서는 제외해 기존 금액을 보존한다.
`MappingWriter`가 공고 한 건의 트랜잭션을 소유한다. 보강 실패 시 주택형과 금액을 함께 롤백한다.
최초 매핑과 변경 없는 재매핑은 상세 보강을 요구하지 않는다.
독립 보강은 `LhAnnouncementEnrichmentService.enrichAll()`에서 시작하며 별도 실패 이력을 관리한다.

## 운영 문제를 볼 때

| 질문 | 시작점 |
|---|---|
| 실행·중단·복구 | [DataPipelineExecutionService](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java) → `StateService`·실행 잠금 |
| 동기 작업의 충돌 범위 | [IngestOperationLock](../src/main/java/com/toadzip/backend/ingest/pipeline/repository/IngestOperationLock.java)의 `Operation`과 호출 서비스 |
| 실패의 해결·재발 | [IngestFailure](../src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailure.java)의 상태 전이, [IngestFailureReconciler](../src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailureReconciler.java)의 일괄 조정 |
| 위치 파일 적재 | [LocationSummaryImportService](../src/main/java/com/toadzip/backend/ingest/location/service/LocationSummaryImportService.java) → Parser·Store |

## 클래스 분리 기준

한 흐름에서만 사용하는 보조 절차는 담당 서비스의 private 메서드를 우선한다.
독립된 규칙·재사용처·트랜잭션 경계가 있을 때 클래스로 분리한다. 줄 수만으로 분리하지 않는다.
원천별 파싱 규칙, 병렬 실행의 공고별 직렬화, 실행 소유권·중단·복구는 보존한다.
동기 잠금은 한 구현을 공유한다. 공고 매핑과 LH 보강은 같은 DB 키를 쓰고 나머지 작업별 키는 구분한다.
실행 서비스와 상태 저장, 단지 Writer의 `REQUIRES_NEW` 경계를 유지하고 공고 매핑·보강은 공고 단위로 원자적으로 저장한다.
실패 Entity는 테이블·사유·원천 식별자를 소유하고 공통 상태·시각·횟수는 `IngestFailure`에서 상속한다.
실패 조정은 조회된 행의 마지막 값, 보충 이력의 첫 값, 관찰된 행의 마지막 값을 선택한다.

검증은 [품질 게이트](quality-gates.md)를 따른다. Gradle `check`에는 Java 스타일·계층 전용 검사기가 없으므로
테스트 통과를 모든 컨벤션의 자동 검증으로 해석하지 않는다. 상세 정책은 [문서 지도](README.md)에서 찾는다.
