# Ingest 처음 읽기

`ingest`는 원천 수집 → 제품 데이터 정제·보강과 그 작업의 실행 관리를 담당한다.
전체 클래스 목록을 읽기보다 아래 두 파일에서 시작해 필요한 단계로 내려간다.

## 시작할 파일 두 개

1. [DataPipelineType](../src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineType.java)의 `steps()`: 독립·통합 작업의 단계와 순서.
2. [DataPipelineRunner](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineRunner.java)의 `execute()`: 단계별로 실제 호출하는 서비스.

Spring은 생성자 주입으로 객체를 연결한다. `Runner.run()`에서 단계 실행 → `StateService` 결과 저장을 따라간다. 단계별 부분 실패·호출 제한·중단 확인은 `Runner`, 실행 전체의 시작·완료·실패·잠금은 `ExecutionService`가 맡는다. 중단된 파이프라인 복구는 같은 실행의 진행 중 수집 기록도 실패로 마감하며 성공 원천을 보존한다.

## 작업을 고른 뒤 내려갈 곳

| 작업 | 처리 순서와 첫 서비스 |
|---|---|
| 단지 수집 | [MyHomeComplexCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/myhome/complex/service/MyHomeComplexCollectionService.java) → LH 임대목록 수집 |
| 단지 정제 | [MyHomeComplexMappingService](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingService.java) → LH 세대수 보강 |
| 공고 수집 | [MyHomeAnnouncementCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/myhome/announcement/service/MyHomeAnnouncementCollectionService.java) → LH 목록 → 공급 → 상세 수집 |
| 공고 정제 | [MyHomeAnnouncementMappingService](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementMappingService.java) → LH 공고 보강 |

수집은 응답·식별·조회 조건·실제 수집 시각을 원천에 보관하고, 요청의 실행 시각·결과는 `collection/history`에 분리한다. 정제·보강은 원천을 읽어 단지·주택형·공고에 반영한다.
원천 조회·매핑·보강은 현행 `collection` 저장 구조만 사용한다. 이전 원천 테이블의 읽기 우회와 체크포인트, JPA 매핑은 제거했다. 정제에서 쓰는 평면 행 모델은 현행 원천의 조회 결과를 담는다.
테스트의 `collection/fixture`는 현행 원천 행을 준비하거나 실제 Storage를 호출하는 어댑터다. 구 테이블이나 별도 ORM 매핑을 사용하지 않는다. 파싱 회귀 테스트는 현행 파서를 검증한다.

`V20261005_01__discard_collected_sources.sql`은 재수집을 전제로 구·현행 수집 원천을 모두 비우고 구 원천 테이블 6개·체크포인트·이관 보고 테이블을 제거한다. 제품·관리자 확인값·수집 및 실패 감사 이력·실행 소유권은 보존한다. 미사용 공급 교체 승인은 폐기하고, 폐기된 원천의 빈 응답·행 누락 보류만 `SKIPPED`로 남긴다. 배포는 [수집 저장 구조 전환 절차](ingest-branch-db-upgrade.md)를 따른다. 미적용 이관이 남은 DB는 백업·ID 하한 보존과 구 원천 사전 정리 후 migration을 적용하고 재수집한다. 과거 Flyway 파일은 적용 이력을 위해 유지한다.

통합 실행은 수집 뒤 정제까지 이어간다. 수집 부분 실패·호출 제한이면 자동 정제를 막고 독립 정제 실행으로 복구한다. 기존 네 단독 실행은 유지한다.

단지 정제는 `MyHomeComplexMappingService.mapAll()` → `MyHomeComplexMappingProcessor.mapAll()`에서 원천을 묶는다. 일반 단지는 위치정보 DB의 좌표를 읽어 저장하고, 확인된 통합 단지는 기존 주소를 검증해 금액·주택형만 갱신한다. 저장은 단지별 트랜잭션이다.
신규 주소의 좌표가 필요하면 수집 후 위치정보요약DB ZIP을 관리자 API로 업로드한다. 업로드는 정제 파이프라인에 포함되지 않는다.
실패는 작업이 끝날 때 현재 원천과 대조해 갱신하며 이전 실패 이력은 보존한다.
단계별 후보 준비·배치 실행 API는 제공하지 않는다.

## 공고 수집·정제를 따라가는 순서

MyHome은 `MyHomeAnnouncementCollectionService.collectSupplyType()` → `MyHomeAnnouncementCollector.collectWithinBatch()` → `MyHomeAnnouncementStorageService.complete()`을 읽는다.
7개 공급유형별 모든 페이지가 유효할 때 원천과 성공 기록을 함께 저장한다. 전체 유형 성공 후 `MyHomeAnnouncementLifecycleService.completeRun()`으로 미조회를 판정한다. 호출 제한은 남은 유형을 중단한다.

LH 공급·상세는 [LhAnnouncementExternalCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/lh/service/LhAnnouncementExternalCollectionService.java)의 `collect()`에서 시작한다.
`collectAnnouncements()` → `collectBatch()`에서 대상을 고르고 `collectRequests()`에서 병렬 실행과 결과를 회수한다.
요청 하나는 `LhAnnouncementCandidateCollector.collectAndStore()` → `LhAnnouncementQueryCollector.collectWithinBatch()` → `LhAnnouncementStorageService.completeSupply()/completeDetail()`에서 조회·검증·저장을 처리한다.
조회 조건·공급코드는 `LhAnnouncementCollectionCandidateResolver`, 종료일에 따른 대상 포함 여부는 `LhAnnouncementCollectionPolicy`가 정한다. 일반 수집도 대상 요청을 매 실행 실제 조회한다. 같은 실행에서 공유하는 요청만 합치며 이전 실행의 성공 원천으로 호출을 생략하지 않는다.

공고 정제는 `MyHomeAnnouncementMappingService.mapAll()`에서 공고별로 다음 경로를 따른다.

```text
SourceMapper → SupplyRowResolver → MappingWriter → EnrichmentWriter.writeAfterMapping
공통값 검증 → LH 연결·공급 원천 준비 → 공고·공급행 저장 → 변경된 기존 LH 공고 보강
```

준비한 LH 요청·공급 원천·과거 원천 구분은 재사용한다. 주택형 변경은 공급행을 수정할 때 기록한다.
현재 원천 중 기관이 빈 행도 매핑에는 포함하지만 LH 보강에서는 제외해 기존 금액을 보존한다. `MappingWriter`가 공고 한 건의 트랜잭션을 소유한다. 보강 실패 시 주택형과 금액을 함께 롤백한다. 최초 매핑과 변경 없는 재매핑은 상세 보강을 요구하지 않는다.
독립 보강은 `LhAnnouncementEnrichmentService.enrichAll()`에서 시작하며 별도 실패 이력을 관리한다.

## 실제 재조회와 저장 원천 재사용

관리자가 수집을 실행하면 대상 LH 공급·상세를 실제 조회한다. 목록 내용이 같아도 공급·상세가 같다는 보장이 없으므로 TTL로 호출을 생략하지 않는다. 정제 단독 실행은 저장된 원천만 읽으며 외부 호출을 하지 않는다.

| 조건 | 처리 |
|---|---|
| 진행 중·종료일 없음·해석 불가·종료 후 30일 이내 | 실행할 때마다 실제 조회 |
| 종료 후 30일 초과 | 일반 수집 제외. 필요한 공고는 공고별 재조회 |
| 같은 실행에서 동일한 조회 조건 공유 | 500행 배치 경계를 넘어도 한 번 조회. 성공 후 공고별 연결 저장 |
| 공유 요청 실패 | 같은 실행에서 반복하지 않음. 다음 실행에서 재조회 |
| 기존과 같은 응답 | 검증 후 원천의 실제 수집 시각 갱신, 새 성공 수집 기록 저장 |
| 새 응답 실패·불완전 | 기존 성공 원천·성공 시각·연결 보존, 새 실패 수집 기록 저장 |

종료 후 30일 기준은 수집 대상의 범위를 정하는 운영 정책이며 공식 갱신 주기가 아니다. 시간 경과만으로 자동 실행되지 않는다. 응답 검증·빈 응답 보호·공급행 누락 승인 규칙은 모든 재조회에 적용한다.

2026-10-05에 기존 6/24시간 TTL 생략과 세 TTL 환경 설정을 제거했다. [이전 성능 측정](announcement-collection-performance.md)의 166회 → 22회는 당시 호출을 생략한 결과이며 현재 정책의 성능 근거가 아니다. 동시성 2 → 8의 동일 166회 실측은 별도 기록이다.
품질 응답의 `freshRequests`는 `collectedRequests`로 바뀌었다. 백엔드·관리자 프론트를 함께 배포한다. 현재 조회 조건의 성공 원천 확보 수이며 과거 성공도 포함한다. `latestCollectedAt`은 그중 최댓값이므로 전체 대상의 최신성이나 이번 실행의 성공률을 뜻하지 않는다.

## 운영 문제를 볼 때

관리자 화면에서 처음 상태 조회가 실패하면 재조회한다. 승인할 공급 요청을 바꾸면 이전 요청의 공고 ID·근거·사유·확인 체크를 초기화한다.
실패 요청 화면의 공고별 LH 강제 재조회는 **마이홈 공고 ID**를 받는다. 공급 또는 상세를 선택해 오래된 종료 공고 제외를 우회한다. 응답 검증·빈 응답 보호·공급행 누락 승인 규칙은 유지한다. 원천 반영 후에는 공고 정제를 실행한다. 조회 대상 없음과 부분 실패는 결과에서 구분한다.
이력·상태 조회는 실행 잠금이 없는 만료 파이프라인을 복구한다. 빠른 재시작으로 최신 실행에 가려진 이전 실행도 대상이다. 종료된 실행에 남은 `RUNNING` 수집 기록은 별도 트랜잭션으로 실패 마감하고, 기존 실행 결과·성공 기록·성공 원천은 보존한다. DB 오류로 마감하지 못하면 다음 조회·실행 시작에서 다시 복구한다.
실제 HTTP 시도는 실패·재시도도 호출수에 포함한다. 서킷이 HTTP 호출 전에 거절한 요청은 보고서와 실행 호출수에서 모두 제외한다.

| 질문 | 시작점 |
|---|---|
| 실행·중단·복구 | [DataPipelineExecutionService](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java) → `StateService`·실행 잠금 |
| 동기 작업의 충돌 범위 | [IngestOperationLock](../src/main/java/com/toadzip/backend/ingest/pipeline/repository/IngestOperationLock.java)의 `Operation`과 호출 서비스 |
| 실패의 해결·재발 | [IngestFailure](../src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailure.java)의 상태 전이, [IngestFailureReconciler](../src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailureReconciler.java)의 일괄 조정 |
| 위치 파일 적재 | [LocationSummaryImportService](../src/main/java/com/toadzip/backend/ingest/location/service/LocationSummaryImportService.java) → Parser·Store |

## 클래스 분리 기준

한 흐름에서만 사용하는 보조 절차는 담당 서비스의 private 메서드를 우선한다.
독립된 규칙·재사용처·트랜잭션 경계가 있을 때 클래스로 분리한다. 줄 수만으로 분리하지 않는다. 원천별 파싱 규칙, 병렬 실행의 공고별 직렬화, 실행 소유권·중단·복구는 보존한다.
동기 잠금은 한 구현을 공유한다. 공고 매핑과 LH 보강은 같은 DB 키를 쓰고 나머지 작업별 키는 구분한다.
실행 서비스와 상태 저장, 단지 Writer의 `REQUIRES_NEW` 경계를 유지하고 공고 매핑·보강은 공고 단위로 원자적으로 저장한다.
실패 Entity는 테이블·사유·원천 식별자를 소유하고 공통 상태·시각·횟수는 `IngestFailure`에서 상속한다.
실패 조정은 조회된 행의 마지막 값, 보충 이력의 첫 값, 관찰된 행의 마지막 값을 선택한다.

검증은 [품질 게이트](quality-gates.md)를 따른다. Gradle `check`에는 Java 스타일·계층 전용 검사기가 없으므로 테스트 통과를 모든 컨벤션의 자동 검증으로 해석하지 않는다. 상세 정책은 [문서 지도](README.md)에서 찾는다.
