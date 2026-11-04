# 수집·정제 흐름

`ingest`는 원천 수집 → 제품 데이터 정제·보강과 그 작업의 실행 관리를 담당한다.
전체 클래스 목록을 읽기보다 아래 두 파일에서 시작해 필요한 단계로 내려간다.

## 시작할 파일 두 개

1. [DataPipelineType](../../../backend/src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineType.java)의 `steps()`: 독립·통합 작업의 단계와 순서.
2. [DataPipelineStepExecutor](../../../backend/src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineStepExecutor.java)의 `execute()`: 단계별로 실제 호출하는 서비스.

Spring은 생성자 주입으로 객체를 연결한다. `DataPipelineRunner.run()`에서 단계 실행 → `DataPipelineExecutionStateService` 결과 저장을 따라간다. 단계별 부분 실패·호출 제한·중단 확인은 `DataPipelineRunner`, 실행 전체의 시작·완료·실패·잠금은 `DataPipelineExecutionService`, 끝나지 못한 실행 복구는 `DataPipelineExecutionRecovery`, 이력·상태 조회는 `DataPipelineExecutionQueryService`가 맡는다. 실행 ID·잠금 lease·진행 모니터는 `IngestExecutionScope`가 스레드 범위로 보관한다. 공공데이터포털 수집 키는 서버 설정만 쓰며 실행마다 넘기지 않는다. `verifyCanContinue()`가 실행 소유권과 중지 요청을 확인하고, `propagate()`가 작업 스레드에 이 맥락과 로그 MDC를 넘긴다. 중단된 파이프라인 복구는 같은 실행의 진행 중 수집 기록도 실패로 마감하며 성공 원천을 보존한다.

## 작업을 고른 뒤 내려갈 곳

| 작업 | 처리 순서와 첫 서비스 |
|---|---|
| 단지 수집 | [MyHomeComplexCollectionService](../../../backend/src/main/java/com/toadzip/backend/ingest/collection/myhome/complex/service/MyHomeComplexCollectionService.java) → LH 임대목록 수집 |
| 단지 정제 | [MyHomeComplexMappingService](../../../backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingService.java) → LH 세대수 보강 |
| 공고 수집 | [MyHomeAnnouncementCollectionService](../../../backend/src/main/java/com/toadzip/backend/ingest/collection/myhome/announcement/service/MyHomeAnnouncementCollectionService.java) → LH 목록 → 공급 → 상세 수집 |
| 공고 정제 | [LhAnnouncementRefinementService](../../../backend/src/main/java/com/toadzip/backend/ingest/lh/refinement/service/LhAnnouncementRefinementService.java) → LH 공고 정제 |

수집은 응답·식별·조회 조건·실제 수집 시각을 원천에 보관하고, 요청의 실행 시각·결과는 `collection/history`에 분리한다. 정제·보강은 원천을 읽어 단지·주택형·공고에 반영한다.
원천 조회·매핑·보강은 `collection` 저장 구조를 사용한다. 정제·보강은 저장 행을 읽기 전용 원천 View(`MyHomeAnnouncementSourceView`, `LhAnnouncementSupplySourceView` 등)로 읽는다. View는 저장 행과 그 행을 조회한 원천 요청으로 `of`/`read`에서만 만든다.

기존 원천의 이관·보존과 배포는 [수집 저장 구조 전환 절차](../../operations/database/ingest-upgrade.md)를 따른다.

통합 실행은 수집 뒤 정제까지 이어간다. 수집 부분 실패·호출 제한이면 자동 정제를 막고 독립 정제 실행으로 복구한다. 기존 네 단독 실행은 유지한다.
단계마다 무엇을 실패·호출 제한 실패·경고로 셀지는 각 단계 보고서가 `IngestStepReport`로 정하고, 파이프라인은 그 수만 읽는다.

단지 정제는 `MyHomeComplexMappingService.mapAll()` → `MyHomeComplexMappingProcessor.mapAll()`에서 원천을 묶는다. 일반 단지는 위치정보 DB의 좌표를 읽어 저장하고, 확인된 통합 단지는 기존 주소를 검증해 금액·주택형만 갱신한다. 저장은 단지별 트랜잭션이다.
신규 주소의 좌표가 필요하면 수집 후 위치정보요약DB ZIP을 관리자 API로 업로드한다. 업로드는 정제 파이프라인에 포함되지 않는다.
실패는 작업이 끝날 때 현재 원천과 대조해 갱신하며 이전 실패 이력은 보존한다. 정제·보강 실패의 전체 실행 후 대조·저장·미해결·이력 조회는 `IngestFailureStore`가 공통으로 맡는다. 원천별 Store는 대상 실패를 고르는 일만 더한다. 지원을 종료한 마이홈 공고 정제·LH 보강 Store는 지난 실패의 조회와 관리자 해결(`resolveByAdmin`)만, 단지 정제 Store는 정제가 중간에 멈췄을 때 그때까지 관찰한 실패 기록(`recordObserved`)을 더한다. 외부 수집 실패(`ExternalDataCollectionFailure`)는 출처와 조회 조건으로 같은 실패를 찾고 오류 유형이 다시 관찰될 때 바뀌므로 `IngestFailure`를 상속하지 않고, 요청 단위로 `ExternalDataFailureStore`·`ExternalDataFailureRecorder`가 관리한다.
단계별 후보 준비·배치 실행 API는 제공하지 않는다.

## 공고 수집·정제를 따라가는 순서

MyHome은 `MyHomeAnnouncementCollectionService.collect()` → `PagedSourceCollector.collectAndReport()` → `MyHomeAnnouncementCollector.store()` → `MyHomeAnnouncementStorageService.complete()`을 읽는다.
페이지로 받는 원천(마이홈 단지·공고, LH 임대·공고 목록)의 `*Collector`는 `PagedSource`로 페이지 조회·검증·저장 방법만 정한다. 재시도, 수집 이력, 실행 확인과 실패 기록은 `PagedSourceCollector`가 맡는다. 수집 이력 하나의 생명주기(실행 확인 → 요청 생성 → 이력 시작 → 수집 → 저장 직전 소유권 재확인, 실패 시 이력 실패 마감)는 `SourceCollectionAttempt`가 맡고, `PagedSourceCollector`와 LH 공급·상세의 `LhAnnouncementQueryCollector`가 함께 쓴다. 페이지 응답 검증 실패와 저장된 원천과의 충돌(`CollectedSourceConflictException`, 마이홈 단지의 지역 충돌)은 마지막 페이지의 외부 호출 실패로 기록하고 다른 요청을 계속한다. 응답 검증을 마친 뒤 저장하다 난 그 밖의 오류는 내부 오류로 실행을 실패시키며 외부 수집 실패로 기록하지 않는다.
7개 공급유형별 모든 페이지가 유효할 때 원천과 성공 기록을 함께 저장한다. 전체 유형 성공 후 `MyHomeAnnouncementLifecycleService.completeRun()`으로 미조회를 판정한다. 호출 제한은 남은 유형을 중단한다.

LH 공급·상세는 [LhAnnouncementExternalCollectionService](../../../backend/src/main/java/com/toadzip/backend/ingest/lh/collection/service/LhAnnouncementExternalCollectionService.java)의 `collect()`에서 시작한다.
`collectAnnouncements()` → `collectBatch()`에서 대상을 고르고 `LhAnnouncementSharedRequestCollector.collect()`에서 병렬 실행과 결과를 회수한다.
요청 하나는 `LhAnnouncementCandidateCollector.collect()` → `LhAnnouncementQueryCollector.collect()` → `LhAnnouncementStorageService.completeSupply()/completeDetail()`에서 조회·검증·저장을 처리한다.
장애 차단기가 요청 전에 거절하면 `ExternalDataRequestRejectedException`(LH는 `LhAnnouncementUnavailableException`)으로 끝나고, 재시도와 호출 수 집계에서 빠진다. 호출 한도로 닫힌 차단기의 거절은 그 요청의 실패로 보고하고, 장애로 닫힌 차단기의 거절은 실행을 `FAILED`로 끝낸다.
조회 조건·공급코드는 `LhAnnouncementCollectionCandidateResolver`, 종료일에 따른 대상 포함 여부는 `LhAnnouncementCollectionPolicy`가 정한다. 해석한 후보 중 요청 충돌·배치 간 중복·정책 제외를 걸러 이번 실행의 후보를 고르는 일은 `LhAnnouncementCandidateSelector`가 맡는다. 일반 수집도 대상 요청을 매 실행 실제 조회한다. 같은 실행에서 공유하는 요청만 합치며 이전 실행의 성공 원천으로 호출을 생략하지 않는다.

마이홈 단지 전국 수집(`MyHomeComplexCollectionService`)도 지역을 병렬로 수집하지만, 두 병렬 흐름은 실패와 호출 제한을 다루는 방식이 달라 공통화하지 않는다.

| 상황 | 마이홈 단지 지역 수집 | LH 공급·상세 요청 |
|---|---|---|
| 작업 실패 | 실행 중인 지역 작업을 중단(`shutdownNow`)한다. 중지 요청 예외면 끝날 때까지 기다린다 | 새 요청만 시작하지 않고 실행 중인 요청은 끝까지 회수한다 |
| 호출 제한 | 공유 신호로 실행 중인 지역의 다음 페이지 호출까지 막는다 | 새 요청만 시작하지 않는다 |
| 실행 순서 | 남은 지역을 순서대로 넣는다 | 같은 LH 공고의 요청은 동시에 실행하지 않는다 |
| 완료 기록 | 지역마다 작업 스레드에서 진행을 기록한다 | 요청별 성공 여부를 다음 배치에 넘긴다 |

합치면 이 차이를 정책 인자와 콜백으로 넘기는 얕은 도우미가 된다. 작업 스레드로 실행 맥락을 넘기는 일은 두 곳 모두 `IngestExecutionScope.propagate()`를 쓴다.

공고 정제는 [LH 공고 정제](lh-linking.md#lh-공고-정제-announcement-refinement)를 따른다. 마이홈 공고 정제·LH 보강·단건 등록과 그 보정은 지원을 종료해 코드를 걷어냈다. 지난 실행 이력과 실패 조회는 남긴다.

PDF 보정은 마이홈 원천행이 기록된 기존 공급행을 다루려고 마이홈 원천 해석(`MyHomeAnnouncementSourceMapper` → `MyHomeAnnouncementSupplyRowResolver`)을 그대로 읽는다.
관리자가 원천 공급행마다 저장한 매칭의 지문 기반 유효성 판정·확정·해제·단지 통합 때의 이동은 `SavedSupplyMatches`만 안다.
LH 공급 연결의 판정·지문 규칙(`LhSupplyRowLinks`)과 저장소는 enrichment에 둔다. 마이홈 원천 해석과 PDF 보정이 이 규칙을 쓰고, `LhSupplyLinkService`는 연결 상태와 매칭 진단(`LhSupplyMatchDiagnostics`)을 조회만 한다. LH 원천 식별자 형식은 `LhSourceIdentifier`만 알고, LH 공고 정제가 고치거나 지울 수 있는 일정·첨부(관리자가 고치지 않은 LH 값)는 `LhOwnedValues`가 판정한다.

기관별 SH·GH 버튼은 원천 수집 성공 뒤 미확정 공고 정제까지 같은 실행으로 수행한다. 수집 실패·호출 제한이면 자동 정제를 막고 해당 기관 수집을 다시 실행한다. 정제 결과는 단계별 실행 보고서에서 확인하며 공개는 PDF 보정에서 별도로 요청한다.

## LH 공고 목록 단독 수집

`POST /api/admin/ingest/lh/announcements/catalog`는 목록 원천만 갱신한다. 공급·상세 조회와 제품 공고 생성·정제는 실행하지 않는다. 전체 페이지 검증 후 건설임대 행만 한 번에 저장하며, 조회 범위 밖의 기존 행과 조회·검증 실패 시의 기존 원천을 보존한다.

목록의 `sourceKey`로 신규·기존을 구분한다. 기존 행은 현재 목록에서 빠졌다 다시 나타나거나 공고 ID·조회 코드·공고명·상태·공고일·게시일·마감일·URL이 달라지면 `changedAt`을 갱신한다. `RNUM`·`ALL_CNT` 같은 페이지 메타데이터 변경은 제외한다. 완료 응답은 저장한 건설임대 행 수이며 신규·변경·동일 건수는 로그와 `ingest.announcement.catalog.rows`에서 확인한다.

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

LH 공급·상세는 TTL로 호출을 생략하지 않으며 TTL 환경 설정을 사용하지 않는다.

기관마다 상세를 다시 받는 규칙은 하나다. 목록 값이 바뀌었거나, 일부만 읽었거나 실패가 열려 있거나, 마지막 수집 뒤 기관별 기한이 지났으면 다시 받는다. SH·GH 게시판은 한 수집기(`BoardSourceCollector`)가 이 판단을 맡는다.

| 기관 | 기한 | 다시 받지 않는 글 |
|---|---|---|
| LH | 0일 | 없음. 수집 대상의 공급·상세를 실행마다 받는다. |
| SH·GH | 7일(`ingest.board-announcement.refetch-after`) | 목록 값이 저장값과 같고 다 읽었으며 열린 실패가 없고 마지막 수집이 7일 안인 글. 목록 값은 SH 제목·등록일, GH 제목·게시일이다. |

기관이 목록에 드러내지 않고 상세나 첨부를 고쳐도 SH·GH는 7일 안에 따라잡는다. 기관별 세부 조건은 [SH 공고 수집·정제](executions.md#sh-공고-수집정제)와 [GH 공고 수집](executions.md#gh-공고-수집)을 따른다.

동시성·타임아웃과 운영 메트릭은 [공고 수집 동시성과 계측](../../operations/ingest/collection-performance.md)을 따른다.
품질 응답의 `collectedRequests`는 현재 조회 조건의 성공 원천 확보 수이며 과거 성공도 포함한다. `latestCollectedAt`은 그중 최댓값이므로 전체 대상의 최신성이나 이번 실행의 성공률을 뜻하지 않는다.

## 운영 문제를 볼 때

관리자 화면에서 처음 상태 조회가 실패하면 재조회한다. 승인할 공급 요청을 바꾸면 이전 요청의 공고 ID·근거·사유·확인 체크를 초기화한다.
공고별 LH 강제 재조회는 **LH 공고 ID(`panId`)**를 받는다. 공급 또는 상세를 선택해 오래된 종료 공고 제외를 우회한다. 응답 검증·빈 응답 보호·공급행 누락 승인 규칙은 유지한다. 원천 반영 후에는 공고 정제를 실행한다. 조회 대상 없음과 부분 실패는 결과에서 구분한다.
이력·상태 조회는 실행 잠금이 없는 만료 파이프라인을 복구한다. 빠른 재시작으로 최신 실행에 가려진 이전 실행도 대상이다. 종료된 실행에 남은 `RUNNING` 수집 기록은 별도 트랜잭션으로 실패 마감하고, 기존 실행 결과·성공 기록·성공 원천은 보존한다. DB 오류로 마감하지 못하면 다음 조회·실행 시작에서 다시 복구한다.
개별 API의 파이프라인 미연결 수집 이력도 이력·상태 조회와 새 파이프라인 시작 시 복구한다. 개별 수집도 전역 실행 잠금 안에서만 실행되므로, 복구는 이 잠금을 확보한 동안에만 수행하고 파이프라인이나 개별 수집이 실행 중이면 다음 조회로 미룬다. 실행 ID가 없거나 대응하는 파이프라인 실행이 없는 `RUNNING` 이력을 실패 마감하며, 기존 성공 이력·성공 원천은 보존한다. DB 오류·실행 잠금 소유권 소실 시 복구 트랜잭션을 롤백하고 다음 조회·실행 시작에서 다시 복구한다.
실제 HTTP 시도는 실패·재시도도 호출수에 포함한다. 서킷이 HTTP 호출 전에 거절한 요청은 보고서와 실행 호출수에서 모두 제외한다.

| 질문 | 시작점 |
|---|---|
| 실행·중단·복구 | [DataPipelineExecutionService](../../../backend/src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java) → `DataPipelineExecutionStateService`·실행 잠금, 복구는 `DataPipelineExecutionRecovery`, 조회는 `DataPipelineExecutionQueryService` |
| 수집·정제의 동시 실행 차단 | [IngestExecutionLockInterceptor](../../../backend/src/main/java/com/toadzip/backend/ingest/pipeline/controller/IngestExecutionLockInterceptor.java)(개별 API)와 `DataPipelineExecutionService`(파이프라인)가 같은 [DataPipelineExecutionLock](../../../backend/src/main/java/com/toadzip/backend/ingest/pipeline/repository/DataPipelineExecutionLock.java)을 잡는다 |
| 실패의 해결·재발 | [IngestFailure](../../../backend/src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailure.java)의 상태 전이, [IngestFailureReconciler](../../../backend/src/main/java/com/toadzip/backend/ingest/failure/domain/IngestFailureReconciler.java)의 일괄 조정 |
| 위치 파일 적재 | [LocationSummaryImportService](../../../backend/src/main/java/com/toadzip/backend/ingest/location/service/LocationSummaryImportService.java) → `LocationSummaryFileParser`·`LocationSummaryStore` |
