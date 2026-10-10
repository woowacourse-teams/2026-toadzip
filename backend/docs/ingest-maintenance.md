# 수집·정제 코드 안내

`ingest`는 외부 API 응답을 원천에 저장하고, 이를 제품의 단지·주택형·공고로 정제한다.
실행 관리 코드를 먼저 읽은 뒤, 필요한 작업의 수집·정제 서비스로 내려간다.

## 실행 관리

1. [DataPipelineType](../src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineType.java)의 `steps()`에서 작업별 단계와 순서를 확인한다.
2. [DataPipelineRunner](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineRunner.java)의 `execute()`에서 각 단계가 호출하는 서비스를 찾는다.

`Runner`는 단계를 실행하고 부분 실패·호출 제한·중단을 확인한다.
`ExecutionService`는 실행 전체의 시작·완료·실패와 잠금을 관리하며, 결과는 `StateService`가 저장한다.

통합 실행은 수집 뒤 정제까지 이어진다.
수집 부분 실패나 호출 제한이 있으면 자동 정제를 막고, 관리자가 결과를 확인한 뒤 독립 정제를 실행할 수 있다.

## 단지

### 수집

[MyHomeComplexCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/myhome/complex/service/MyHomeComplexCollectionService.java)에서 시작한다.
마이홈 단지 원천을 수집한 뒤 LH 임대목록을 수집한다.

### 정제

[MyHomeComplexMappingService](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingService.java)의 `mapAll()` → `MyHomeComplexMappingProcessor.mapAll()` 순서로 읽는다.

- 일반 단지는 위치정보 DB의 좌표를 읽어 저장한다.
- 확인된 통합 단지는 기존 주소를 검증하고 금액·주택형만 갱신한다.

저장은 단지별 트랜잭션으로 처리한다.
신규 주소의 좌표가 필요하면 수집 후 위치정보요약DB ZIP을 별도로 업로드하고 정제를 실행한다.

## 공고

### 수집

#### 마이홈

[MyHomeAnnouncementCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/myhome/announcement/service/MyHomeAnnouncementCollectionService.java)의 `collectSupplyType()`에서 공급유형별 수집을 따라간다.

`MyHomeAnnouncementCollector.collectWithinBatch()`에서 응답을 모으고, `MyHomeAnnouncementStorageService.complete()`에서 저장한다.
공급유형별 모든 페이지가 유효할 때 해당 원천과 성공 기록을 함께 저장한다.

전체 공급유형이 성공하면 `MyHomeAnnouncementLifecycleService.completeRun()`에서 미조회 원천을 판정한다.
호출 제한을 만나면 남은 유형의 수집을 중단한다.

#### LH

[LhAnnouncementExternalCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/lh/service/LhAnnouncementExternalCollectionService.java)의 `collect()`에서 시작한다.
`collectAnnouncements()` → `collectBatch()`에서 대상을 고르고, `collectRequests()`에서 병렬 실행 결과를 모은다.

요청 하나의 조회·검증·저장은 다음 순서로 읽는다.

```text
LhAnnouncementCandidateCollector.collectAndStore()
  → LhAnnouncementQueryCollector.collectWithinBatch()
  → LhAnnouncementStorageService.completeSupply()/completeDetail()
```

조회 조건·공급코드는 `LhAnnouncementCollectionCandidateResolver`, 종료일에 따른 대상 포함 여부는 `LhAnnouncementCollectionPolicy`가 정한다.

### 정제

[MyHomeAnnouncementMappingService](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementMappingService.java)의 `mapAll()`에서 시작한다.

```text
SourceMapper → SupplyRowResolver → MappingWriter → EnrichmentWriter.writeAfterMapping
공통값 검증 → LH 연결·공급 원천 준비 → 공고·공급행 저장 → 변경된 기존 LH 공고 보강
```

`MappingWriter`가 공고 한 건의 트랜잭션을 관리한다.
기존 LH 공고의 보강이 실패하면 주택형과 금액을 함께 롤백한다. 최초 매핑과 변경 없는 재매핑은 상세 보강을 요구하지 않는다.

독립 보강은 `LhAnnouncementEnrichmentService.enrichAll()`에서 시작한다.

### 재조회

수집을 실행하면 대상 LH 공급·상세를 매번 실제 조회한다.
정제만 실행하면 저장된 원천을 읽으며 외부 API를 호출하지 않는다.

수집 대상, 공유 요청 처리, 실패 시 원천 보존은 [공고 원천 관리](announcement-source-lifecycle.md#lh)를 따른다.

## 운영 문서

- [관리자 실행 제어](pipeline-operator-controls.md): 실행·중지·실패 확인과 정제 복구
- [수집 저장 구조 전환](ingest-branch-db-upgrade.md): DB 이관·배포·복구
- [LH 품질 지표](lh-announcement-quality-operations.md): 원천 확보 수와 최근 수집 시각의 의미
