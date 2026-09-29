# Ingest 수집·정제 리뷰 — 2026-09-28 갱신

코드 리뷰 기준: `c9b3dee1777094070a032f05fa981d3beef6cc84`. 파일명은 기존 링크를 유지하기 위해 그대로 둔다.
9월 26일 리뷰는 문서 하단의 접힌 과거 기록에 보존했다. 현재 작업 우선순위는 이 최신 검토를 따른다.

- **Goal:** 데이터 정확성·속도·실패 복구·객체 책임·죽은 코드를 재검토하고 다음 개선 순서를 정한다.
- **Constraints:** 제품 코드·스키마·운영 데이터를 변경하지 않고 기존 사용자 변경을 보존한다. 외부 API·비밀에 접근하거나 Git stage·commit·push를 하지 않는다.
- **Done:** 현재 결함과 해결된 항목을 구분하고, 파일 근거·검증 결과·미검증 범위를 기록한다.

## 현재 판단과 이전 지적의 상태

현재의 수집 → 매핑·보강 → 제품 반영 구조를 유지한다. 우선순위는 원천 선택 일관성, 데이터 품질 확인,
반복 조회 개선 순서다. 새로 확인한 결함은 **M-06(P2) 1건이며 리뷰 기준 커밋에서는 미해결**이다.
9월 26일의 10개 지적은 현행 코드와 관련 회귀 테스트에서 보완을 확인했다.

**후속 작업 상태:** 문서 갱신 중 M-06의 현재 원천 선택 적용과 관련 테스트를 추가하는 별도 로컬 변경을 확인했다.
해당 변경은 이 문서 작업에서 수정하거나 검증하지 않았다. 아래 783개 테스트와 probe는 수정 전 리뷰의 증거이며,
후속 수정의 완료 판정은 별도의 검증 결과를 따른다.

| 이전 ID | 9월 28일 확인한 상태와 근거 |
|---|---|
| C-01 | [LH 상세 파서](../src/main/java/com/toadzip/backend/ingest/collection/repository/external/LhAnnouncementDetailResponseParser.java)가 실질 내용 없는 행을 거절한다. |
| C-02 | [임대 카탈로그 파서](../src/main/java/com/toadzip/backend/ingest/collection/repository/external/LhLeaseCatalogResponseParser.java)가 지역·공급유형·단지명 누락을 거절한다. |
| C-03 | 유형·지역·카탈로그 전체 수집 성공 시 해당 범위의 과거 페이지 실패를 해결한다. 페이지 감소·크기 변경 회귀 테스트가 있다. |
| M-01 | [단지 BatchProcessor](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingBatchProcessor.java)의 `candidate.prepare()`가 최신 주소로 좌표 캐시를 다시 검증한다. |
| M-02 | 공고 공통값에 [현재 원천 선택](../src/main/java/com/toadzip/backend/ingest/collection/domain/MyHomeAnnouncementCurrentSources.java)을 적용하고 과거 공급행은 보존한다. 공급행의 LH 요청 선택에는 M-06이 남는다. |
| M-03 | [LH 보강 매퍼](../src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentMapper.java)는 단지명이 일치하는 단일 후보에서만 입주예정월을 가져온다. |
| M-04/M-05 | [값 파서](../src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementValueParser.java)가 기간 분리·전체 문자열 검증과 `별도안내` 정규화를 수행한다. |
| O-01 | [쓰기 소유권 Guard](../src/main/java/com/toadzip/backend/ingest/pipeline/service/IngestWriteOwnershipGuard.java)가 실제 lease와 owner/generation을 확인하고 커밋 직전 다시 검증한다. |
| O-02 | [실행 서비스](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java)가 새 실행 시작 전 오래된 RUNNING을 복구하고 마지막 단계를 보존한다. |

빈·손상 응답에서 기존 원천을 보존하는 정책, 같은 PAN_ID의 요청 순서, 제한 후 신규 요청 중단,
공고별 매핑·보강 트랜잭션과 수동 데이터 보호는 유지한다. interceptor의 Repository 직접 참조도
현재는 `IngestExecutionOwnershipService` 호출로 바뀌었다.

## M-06 · P2 · 공급행 매핑과 보강의 LH 원천 선택 불일치

리뷰 기준 커밋의 [MyHomeAnnouncementSupplyRowResolver.java:72](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementSupplyRowResolver.java)는
과거 비활성 행까지 `resolveFirstLinked()`에 전달한다. 반면 공고 공통값과
[LhAnnouncementEnrichmentService.java:149](../src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentService.java)는 현재 원천을 선택한다.

**재현 조건:** 같은 공고의 앞선 비활성 원천에 과거 PAN의 성공 연결이 남아 있고, 뒤의 활성 원천은 최신 PAN으로
정정됐다. 과거 PAN의 대상 단지 공급형이 46A이고 최신 PAN에서는 59A이면 다음 선택 차이가 발생한다.

```text
공급행 매핑이 요청한 PAN: old-pan
매핑 결과의 주택형:       46A
LH 보강이 요청한 PAN:    current-pan
```

실제 컴파일된 resolver와 첫 성공 연결 순회를 호출하고 링크 확정·저장소에는 대역을 사용한 결과다.
위 주택형은 resolver 반환값이며 실제 DB 제품 행을 조회한 결과는 아니다.

**영향:** 이미 LH 보강된 공고는 옛 공급형과 최신 보강 대상의 불일치로 보강이 실패할 수 있다.
[AtomicWriter.java:63](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeLhAnnouncementAtomicWriter.java)가 실패를 예외로 바꾸므로
정정 전체가 롤백되고 같은 입력으로 재시도해도 보류가 반복될 수 있다. 최초 정제는 이 원자 경로를 거치지 않아
옛 공급 구조가 먼저 저장되고 후속 보강이 실패할 여지가 있다. DB 반영·롤백 전체 시나리오는 아직 새로 실행하지 않았다.

**최소 수정:** 과거 공급행 보존은 유지하고, LH 요청을 선택하는 입력에만 기존 `MyHomeAnnouncementCurrentSources`
정책을 동일하게 적용한다. 비활성 원천 전체를 제거하면 과거 공급행까지 삭제될 수 있다.

**완료 조건:** 과거·현재 PAN이 다른 정정, 최신 연결 미완료 시 옛 연결로 돌아가지 않음, 과거 공급행 보존,
실패 시 기존 제품 보존을 통합 테스트로 확인한다. 이번 783개 기존 테스트 통과가 M-06 해결을 뜻하지는 않는다.

## 객체 책임과 유지할 경계

| 책임 | 소유할 객체·기능 | 다음 개선 |
|---|---|---|
| 호출·응답 구조 검증·원천 저장 | collection | 원천 의미 해석과 분리하고 검증 실패 시 기존 원천을 보존한다. |
| 현재 원천 선택·값 해석·제품 연결 | mapping/enrichment | 현재 공통값·LH 요청 선택과 과거 공급행 보존을 서로 다른 입력으로 구분한다. |
| 공고 단위 저장과 기존 값 보호 | Writer | 매핑·보강 원자성과 수동 값 보존을 유지한다. |
| 최종 제품 규칙·확인된 정보 | announcement/housing | 확인된 일정·정정 관계를 자동 원천이 덮지 않도록 한다. |
| 실행 순서·상태·중단·소유권 | pipeline | 전역 잠금을 유지하고 worker 맥락 전달과 쓰기 트랜잭션 경계를 검증한다. |

클래스 길이보다 정책 적용 누락을 먼저 줄인다. 공통 수집 프레임워크나 범용 워크플로 엔진을 추가할 근거는 찾지 못했다.
ingest 전체 계층 자동 검사는 없어 구조는 수동 검토했다. 후속 변경에서는 금지 의존성과 worker의 소유권 전달,
실제 Service/Store 트랜잭션을 통한 쓰기를 검증한다. `upstreamExecutionId`는 실행 연결이며 원천 버전을 고정하지 않는다.

## 데이터 품질과 수집 범위

현재 [LH 상세·공급 수집](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementExternalCollectionService.java)은
마이홈 공고를 출발점으로 삼는다. LH 단독 공고 확보와 원공고·정정 관계 확인은 제품 범위의 후속 과제다.
목록 미연결 건수를 그대로 신규 공고 수로 해석하거나 제목만으로 같은 모집이라고 확정하지 않는다.

[확인된 접수 일정](../src/main/java/com/toadzip/backend/announcement/service/VerifiedApplicationScheduleService.java)과
[정정 관계](../src/main/java/com/toadzip/backend/announcement/service/VerifiedLhRevisionService.java)를 관리하는 기능은 이미 있다.
[보강 통합 테스트](../src/test/java/com/toadzip/backend/ingest/enrichment/service/VerifiedAnnouncementEnrichmentIntegrationTest.java)도
확인된 일정·PAN 보호를 검증한다. 과거 리뷰의 일정·정정 모델 미구현 설명은 현행 상태로 사용하지 않는다.
다만 과거 공고문 표본이나 실제 운영 데이터의 정정 반영까지 이번에 확인한 것은 아니다.

호출 성공률과 별도로 단지·주택형 연결률, 금액 확보율, 일정 검토 여부, 최신성,
기존 정상값 유지 사유와 원천 감소·정정 보류 대상을 확인할 수 있어야 한다.
공급 감소 보호는 유지하되 실제 철회·정정임을 확인한 뒤 처리하는 복구 경로를 구체화한다.
부분 수집 성공 시 최신 성공 실행의 공통값과 과거 공급행이 섞이는 정책은 현재 결함으로 단정하지 않았다.

## 성능: 먼저 줄일 비용과 측정할 비용

아래는 코드에서 확인한 비용이며 운영 병목이나 개선 시간을 새로 실측한 결과는 아니다.

| 위치 | 비용 | 권고와 검증 |
|---|---|---|
| [공급행 Matcher](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementSupplyMatcher.java) | 성공 R행에서 단지·주택형 조회가 대략 2R회 반복 | 공고 단위 조회 결과 재사용. 같은 단지 여러 행의 SQL 수로 검증한다. |
| [단지 매핑 준비](../src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingPreparer.java) | 원천·후보 전량 로딩. `mapNext` 크기만으로 전체 메모리가 제한되지는 않음 | 최대 행 수와 heap을 측정한 뒤 조회 범위를 줄인다. |
| [세대수 보강](../src/main/java/com/toadzip/backend/ingest/enrichment/service/LhHousingTypeHouseholdEnrichmentService.java) | 전체 원천·단지·매칭 결과를 한 트랜잭션에서 처리 | 메모리·시간을 측정한다. 쓰기 분할 전에 중복 대상 검사를 끝내야 한다. |
| [진행 상태 Monitor](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionMonitor.java) | 요청 전후·항목 완료마다 DB 쓰기와 소유권 검증 | SQL 수·커넥션 대기 측정 후 진행 표시 저장 빈도만 조정한다. 소유권 검증은 유지한다. |

일정·첨부 재조회는 현재 Writer가 이미 읽은 목록을 재사용하며, 공급대상도 공고 단위로 묶어 조회한다.
이 두 과거 성능 지적은 다시 작업 목록에 올리지 않는다. 외부 동시성은 현재 상한을 유지하고
외부 요청·재시도, 원천 저장, 정제 시간을 구분해 측정한다. [9월 25일 성능 수치](announcement-collection-performance.md)는 이번 재측정이 아니다.

## 죽은 코드와 작은 정리 후보

| 후보 | 확인 결과·주의 |
|---|---|
| [DataPipelineExecution.isCompleted()](../src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineExecution.java) | 선언 외 main/test 호출이 발견되지 않아 제거 후보로 분류한다. |
| [ProgressManager의 2인자 findBatch()](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementCollectionProgressManager.java) | 운영 경로는 3인자 메서드를 사용한다. 테스트만 사용하는 기본 TTL 오버로드와 전용 필드·주입을 함께 검토한다. |
| [ExternalDataResponse.rawPayload](../src/main/java/com/toadzip/backend/ingest/collection/dto/ExternalDataResponse.java) | 운영 코드에서 읽지 않고 테스트만 확인한다. 실제 저장되는 LH 목록 Entry의 rawPayload와 구분한다. |

큰 미사용 하위 시스템은 확인하지 못했다. Spring 엔티티·설정·스케줄러는 직접 Java 참조가 없어도 사용된다.
특히 `IngestExecutionOwnership`은 스키마 검증에 참여하므로 죽은 클래스로 제거하지 않는다.

## 권장 작업 순서와 완료 조건

1. **M-06 수정:** 현재 원천 선택을 통일하고 서로 다른 PAN의 정정·과거 공급행 보존·실패 롤백을 검증한다.
2. **품질 상태 정리:** 미연결·미검토·보류·오래된 정상값을 구분하고 원천별 복구 대상을 확인할 수 있게 한다.
3. **작은 성능·구조 개선:** 공고 단위 조회 재사용을 SQL 수로 검증하고, 미사용 메서드와 낡은 문서 상태를 정리한다.
4. **근거에 따른 확장:** 전량 처리·트랜잭션 비용을 측정해 범위를 줄이고 LH 단독 수집·정정 반영을 확장한다.

공개 계약·데이터 모델 변경이 필요한 작업은 구체적인 변경안을 검토한 뒤 구현한다. 이 문서 갱신은 위 구현을 포함하지 않는다.

## 9월 28일 검증과 한계

코드 리뷰 단계에서 격리 PostgreSQL로 다음 명령을 실행했다. 기존 테스트 결과와 새 probe의 범위를 구분한다.

```bash
TEST_POSTGRES_PORT=55442 TEST_SHARED_POSTGRES_PORT=55443 \
./gradlew --offline test --tests 'com.toadzip.backend.ingest.*' --rerun-tasks
```

- **783 tests, 102 suites, failures/errors/skipped 0**, `BUILD SUCCESSFUL`과 XML 집계를 확인했다.
- M-06의 실제 클래스 probe는 원천 선택 차이를 확인했다. 새 DB 종단 재현은 미실행이다.
- 임시 DB `toadzip-ingest-review-20260928`의 컨테이너 2개와 전용 네트워크를 정리했다. 기존 DB는 사용하지 않았다.
- 이 리뷰·문서 작업에서는 제품 소스·테스트·스키마를 수정하지 않았다. 진단 소스는 `/tmp/toadzip-review-mapping-20260928/CurrentSelectionProbe.java`에만 두었다.
- 전체 backend `check`, 실제 API·운영 데이터 대조, 처리량·메모리 실측은 미실행이다.
- 아래 9월 26일의 645개 결과와 이번 결과를 합산하지 않는다. 문서만 갱신하는 단계에서는 제품 테스트를 다시 실행하지 않는다.
- 문서 갱신 후 하네스 테스트·검사, 문서·코드 로컬 링크 98개 확인과 `git diff --check`를 통과했다.

<details>
<summary>2026-09-26 과거 리뷰 원문 — 당시 결함·권고·검증 기록</summary>

이하의 현재·미해결·미구현 표현과 코드 줄 번호는 모두 9월 26일 기준이다.
C-01~C-03, M-01~M-05, O-01~O-02의 최신 상태와 후속 작업은 위 9월 28일 검토를 따른다.

## 2026-09-26 검토 기준

기준: `develop`, `bd9d2f5deade1d5776f7aa7ba25a9a8893a94520`. 시작 작업 트리는 깨끗했다.
이번 작업은 코드 리뷰와 문서 갱신이며 제품 코드·스키마·운영 데이터는 변경하지 않았다.

## 검토 계약과 판정

- **Goal:** 최근 LH 수집 개선을 재평가하고 MyHome/LH 수집, 단지·공고 매핑, 보강, 실행 관리의 안정성·성능·복잡성을 검토한다.
- **Constraints:** 기존 데이터 보존 정책과 공개 계약을 유지한다. 외부 API·운영 DB·비밀에 접근하지 않는다. Git stage·commit·push를 하지 않는다.
- **Done:** 수집과 정제의 책임, 해결된 항목, 남은 결함, 성능 비용, 리팩터링 순서와 검증 공백을 코드 근거로 기록한다.
- **근거 구분:** `실행 확인`은 이번 직접 실행, `정적 경로`는 코드와 기존 테스트 검토, `과거 실측`은 이전 문서의 당시 결과다. 실제 운영 발생 빈도는 확인하지 않았다.
- **우선순위:** P1은 해당 운영 조건에서 우선 대응할 문제, P2는 재현 조건이 있는 정상 개선 대상이다. 순수 구조 개선과 미확정 제품 정책에는 결함 우선순위를 붙이지 않는다.

**새 발견은 10건: 조건부 P1 1건, P2 9건이다.** 기존 접수 일정·정정 공고 문제는 별도 잔여 항목으로 유지한다.
기존 ingest 테스트 645개가 모두 통과해도 아래 새로운 경계가 검증됐다는 의미는 아니다.

| ID | 우선순위 | 책임 | 문제 | 이번 근거 |
|---|---|---|---|---|
| O-01 | P1·다중 인스턴스 조건 | 실행 관리 | 잠금 세션 소실 뒤 기존 실행이 소유권을 계속 주장 | 실제 잠금 클래스 + 격리 PostgreSQL 재현 |
| C-01 | P2 | 수집 | 내용 없는 LH 상세 행이 빈 응답 보호를 우회 | 파서 실행 + 교체·성공 기록 경로 |
| C-02 | P2 | 수집 | 내용 없는 LH 임대 카탈로그로 전체 원천 교체 | 파서 실행 + 전체 교체 경로 |
| C-03 | P2 | 수집·실패 관리 | 페이지 수 감소 후 정상 수집에도 과거 페이지 실패 잔존 | 정적 경로 |
| M-01 | P2 | 단지 매핑 | 준비 이후 주소 변경 시 새 PNU와 옛 좌표 결합 | 정적 경로 |
| M-02 | P2 | 공고 원천 선택 | 비활성 원천의 옛 공통값이 현재 정정을 거절 | 정적 경로 |
| M-03 | P2 | LH 보강 매칭 | 이름이 다른 단일 단지에서 입주예정월 추정 | 정적 경로 |
| M-04 | P2 | 날짜 정제 | 날짜만 있는 기간의 뒤 연도를 앞 날짜의 시간으로 파싱 | 실제 파서 실행 |
| M-05 | P2 | 미제공 값 정제 | `별도 안내`를 미제공으로 인식하지 못함 | 실제 파서 실행 |
| O-02 | P2 | 실행 복구 | 이전 RUNNING이 새 실행에 가려져 복구되지 않음 | 정적 경로 |

## 최근 개선으로 해결된 것과 유지할 방어

| 영역 | 현재 코드·테스트에서 확인한 상태 |
|---|---|
| 정상 진입의 배타 실행 | 모든 `/api/admin/ingest/**` POST에 공통 잠금. pipeline 경로는 실행 서비스에서 같은 잠금 획득 |
| LH 병렬 수집 | 최대 8개 슬롯, 완료 후 추가 투입, 같은 PAN_ID 요청 순서, 중복 요청 재사용 |
| 외부 장애 | LH 전용 timeout·circuit breaker, 호출 제한 전파, 실패 후 신규 투입 중단과 진행 중 성공 처리 |
| 목록 교체 | 전체 건수·페이지·식별자 검증, 근거 없는 빈 목록으로 현재 목록을 지우지 않음 |
| 원천 교체 | 기존 상세·공급의 완전 빈 응답 거절, 같은 조회 조건의 구버전 원천과 비교 |
| 공급 감소 | 식별 조합·중복 개수 감소 거절, 순서 변경·허용되는 값 갱신 처리 |
| 정제 보존 | 공고별 MyHome 매핑과 LH 보강의 원자성, 금액 미제공 시 보존, 수동 데이터 보존 |
| 중복 연결 | 서로 다른 LH 공급행이 같은 제품 공급행을 덮는 경우 거절 |
| 060·일정 필드 | 유형별 동의 필드, 구조화된 접수일 보존, 서류대상자 발표와 당첨일 분리 |
| 실행 상태 | 실제 COMPLETED 후 정제 연결, 호출 제한/행 누락/운영 부분 실패의 상태 구분 |

[IngestExecutionLockInterceptor.java:24](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/controller/IngestExecutionLockInterceptor.java:24)–56, [DataPipelineExecutionWebConfiguration.java:23](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/configuration/DataPipelineExecutionWebConfiguration.java:23)–25이 공통 HTTP 잠금 근거다.
개별 단지 수집·세대수 보강 Service에 잠금이 없다는 사실만으로 HTTP 동시 실행 결함을 보고하지 않았다.
슬롯과 upstream 중복은 Flyway의 partial unique index도 방어한다.

## 수집에서 처리할 문제

### C-01 · LH 상세의 내용 없는 객체가 이전 정상 원천을 지울 수 있다

**코드 근거:** [LhAnnouncementDetailResponseParser.java:67](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/repository/external/LhAnnouncementDetailResponseParser.java:67)–97은 `dsEtcInfo:[{}]`, `dsSbd:[{}]`도 원천 객체로 만든다.
[LhSourceStore.java:68](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/repository/LhSourceStore.java:68)–77은 `sources.isEmpty()`일 때만 이전 상세 보존을 검사한다.
[LhAnnouncementCandidateCollector.java:41](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementCandidateCollector.java:41)–58은 저장 후 성공 체크포인트를 기록한다.

이번에 실제 파서에 `[{"dsSbd":[{}]}]`를 넣어 `COMPLEX` 1행, 단지명·주소 null을 확인했다.
요청과 일치하는 `dsSch`와 성공 헤더가 있는 손상 응답이면 외부 응답 검증을 거쳐도 이 경로에 도달할 수 있다.
같은 요청 해시의 상세가 이미 있으면 모든 기존 상세를 지우고 내용 없는 1행으로 교체할 수 있다.

**영향:** 수집이 성공으로 기록돼도 원천의 단지·일정·첨부를 잃는다. 같은 PAN_ID의 제품 일정은
보강의 빈 값 보존 정책으로 남을 수 있으므로 제품 일정도 반드시 삭제된다고 단정하지 않는다.
원천 복원과 새 정제가 불가능해지는 것만으로도 수집 경계의 문제다.

**권고:** dataset별로 최소 식별/구조 검증을 하고 모든 실질 필드가 누락된 행을 정상 원천으로 인정하지 않는다.
원천 숫자의 의미 해석은 정제에 남긴다. 합법적인 빈 배열, 선택 필드 null, 정상적인 일부 dataset 감소까지
모두 실패로 바꾸는 방식은 피한다. 기존 상세 감소 허용 정책과는 별개의 손상 입력 방어다.

**회귀 검증:** 정상 상세를 저장한 뒤 `{}`·필드명 변경 응답을 수집한다. 기존 행·체크포인트·연결 보존과 실패 기록을 함께 검증한다.
파서 수용은 실행 확인했지만 이 입력의 DB 교체 전체 시나리오는 이번에 새로 실행하지 않았다. 실제 LH 장애 표본도 아니다.

### C-02 · LH 임대 카탈로그도 식별 정보 없는 행으로 전체 교체된다

**코드 근거:** [LhLeaseCatalogResponseParser.java:16](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/repository/external/LhLeaseCatalogResponseParser.java:16)–40은 dataset 존재만 확인하고 전체 건수를 `-1`로 반환한다.
[LhLeaseCatalogCollectionService.java:90](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/service/LhLeaseCatalogCollectionService.java:90)–97은 수집을 마친 뒤 [LhSourceStore.java:47](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/repository/LhSourceStore.java:47)–61의 전체 교체를 호출한다.
저장소는 비어 있지 않으면 `deleteAllInBatch()` 후 새 행을 저장한다.

이번 파서 실행에서 `[{"dsList":[{}]}]`는 8개 필드가 모두 null인 snapshot 1개가 됐다.
짧은 페이지로 수집이 끝나면 정상 카탈로그 전체가 이 행 하나로 교체될 수 있다.
뒤의 [LhHousingTypeHouseholdSourceMapper.java:32](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhHousingTypeHouseholdSourceMapper.java:32)는 지역·공급유형·단지명을 필수로 요구하므로 보강은 실패한다.

**권고:** 행을 식별하는 최소 구조를 전체 교체 전에 검증한다. 숫자·지역 의미의 정제와 단지 매칭은 보강 책임으로 유지한다.
성공 테스트 fixture도 현재의 `ARA_NM`만 있는 축약 행에서 실제 필수 구조를 가진 행으로 보완한다.

**회귀 검증:** 정상 기존 카탈로그 + `{}` 응답에서 수집 실패와 기존 행 보존, 정상 fixture의 수집·보강을 확인한다.
실제 API의 전체 건수·서버 pageSize 상한은 재검증하지 않았으므로 조기 종료 문제까지 확정하지 않는다.

### C-03 · 복구 성공 범위와 실패 해결 범위가 다르다

**코드 근거:** [MyHomeAnnouncementSupplyTypeCollector.java:152](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/service/MyHomeAnnouncementSupplyTypeCollector.java:152)–173, [MyHomeComplexRegionCollector.java:192](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/service/MyHomeComplexRegionCollector.java:192),
[LhLeaseCatalogCollectionService.java:97](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/collection/service/LhLeaseCatalogCollectionService.java:97)은 이번에 실제 호출한 페이지의 `requestDescription`만 해결한다.
실패 저장소도 요청 설명의 완전 일치로 찾는다.

**재현:** 첫 실행 PAGE=2 실패 → 원천 건수가 줄어 다음 전체 수집은 PAGE=1에서 종료 → 전체 저장 성공.
PAGE=2의 PENDING 실패가 계속 남는다. 페이지 크기 변경도 동일한 문제를 만든다.

**영향:** 복구된 원천을 운영 화면에서는 계속 실패로 보고 불필요한 재처리를 유도한다.
이번에는 잘못된 제품 행 생성보다 운영 상태의 정확성 문제다.

**권고:** 지역·공급유형·전체 카탈로그라는 수집 범위와 페이지 요청을 구분한다.
범위 전체 수집·저장이 성공한 뒤 그 범위의 이전 페이지 실패를 해결한다.
LH 공고 목록의 `resolveStartingWith()`가 선례이며, 다른 지역·유형의 실패까지 해결하면 안 된다.

**회귀 검증:** 페이지 감소, pageSize 변경, 다른 scope 실패 보존, 전체 수집 실패 시 기존 실패 유지.

## 정제·매핑에서 처리할 문제

### M-01 · 준비한 주소와 실제 매핑할 원천이 달라질 수 있다

**코드 근거:** [MyHomeComplexMappingBatchProcessor.java:111](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingBatchProcessor.java:111)–116은 최신 원천을 다시 변환하지만 후보 주소를 갱신하지 않고
`resolveCoordinates(candidate)`를 호출한다. 같은 파일 `198–207`은 후보에 저장된 이전 주소·좌표를 사용한다.
[MyHomeComplexMappingCandidate.java:74](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/domain/MyHomeComplexMappingCandidate.java:74)–85에는 주소 변경을 감지해 좌표를 초기화하는 `prepare()`가 이미 있다.

**재현:** 주소 A로 prepare → 별도 수집에서 같은 단지의 주소/PNU를 B로 정정 → mapNext.
최신 PNU·행정코드에 A의 도로명주소와 좌표가 결합된다. 순차 호출만으로 가능하므로 공통 실행 잠금은 해결책이 아니다.

**권고:** geocoding 직전에 최신 `sourceRoadAddress`로 후보를 준비하고 주소가 달라졌으면 캐시를 무효화한다.
원천 버전이 꼭 필요한지 판단하기 전에 기존 `prepare()`를 활용하는 작은 수정부터 검토한다.

**회귀 검증:** PENDING·GEOCODED 후보 각각에서 prepare 후 유효한 주소 변경, 변경 없는 주소의 좌표 재사용,
새 주소 지오코딩 실패 시 기존 제품 데이터 보존. 기존 테스트는 변환 불가능한 원천 변경만 검증한다.

### M-02 · 비활성 원천의 옛 값이 현재 공고의 정정을 막는다

**코드 근거:** [MyHomeAnnouncementMappingService.java:220](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementMappingService.java:220), [LhAnnouncementEnrichmentService.java:130](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentService.java:130)은 비활성 행도 같은 공고로 묶는다.
[MyHomeAnnouncementValueParser.java:39](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementValueParser.java:39)–48과 [MyHomeAnnouncementCommonValuesMapper.java:17](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementCommonValuesMapper.java:17)–59는 그룹의 제목·날짜·URL 등
공통값이 다르면 거절한다.

**재현:** 공고의 공급 A/B 수집 → B가 두 번 미조회돼 비활성 → 계속 수집되는 A의 제목·종료일 정정.
B는 옛 값으로 남아 mapping은 `CONFLICTING_SOURCE_VALUE`, enrichment도 공통값 충돌로 보류된다.
B가 다시 나타나지 않으면 정상 재수집을 반복해도 정정이 반영되지 않는다.

**권고:** 현재 공통 메타데이터를 선택하는 규칙과 과거 공급행을 보존하는 규칙을 분리한다.
활성 여부·동일 관찰 실행 등을 기준으로 현재 공통값을 선택하되 비활성 원천과 과거 제품 행은 보존한다.
전체 입력에 단순 `active=true` 필터를 붙이면 Writer의 stale 삭제가 과거 공급행을 지울 수 있다.
모두 비활성인 과거 공고의 처리도 함께 정해야 한다.

**회귀 검증:** 위 혼합 상태에서 새 제목·날짜 반영과 기존 공급행 보존, 전부 비활성인 공고 보존,
같은 실행의 활성 행끼리 충돌하면 계속 거절. 이 항목은 선택 정책과 삭제 정책을 함께 검토해야 한다.

### M-03 · 단일 후보라는 이유로 다른 단지의 입주예정월을 사용한다

**코드 근거:** [LhAnnouncementEnrichmentMapper.java:71](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentMapper.java:71)–83은 이름 매칭이 실패해도 상세 단지가 하나이고
두 이름이 비어 있지 않으면 그 단지의 입주월을 반환한다.

**재현 입력:** 상세 `청운3 / 202703` 한 행, 공급 `동삼2 / 46A` → 동삼2 입주월이 `2027-03`으로 생성된다.
동일 요청에서 받은 상세·공급이라는 이유만으로 서로 다른 단지명이 같은 단지임을 보장할 수 없다.

**권고:** 이름이 불일치하면 미확정으로 남긴다. 명확한 원천 식별자나 검증된 별칭 관계가 있을 때만 연결한다.
마지막 정상값 보존과 이번 원천의 매칭 성공을 구분하고, 성공하지 않은 값을 새로 추정하지 않는다.

**회귀 검증:** 단일 후보이면서 양쪽 이름이 존재하되 불일치, 정상적인 이름 정규화 일치, 다중 후보 모호성.
기존 테스트의 빈 이름·다단지 불일치만으로는 이 fallback을 검증하지 못한다.

### M-04 · 날짜 기간의 뒤 연도가 앞 날짜의 시각으로 소비된다

**코드 근거:** [LhAnnouncementValueParser.java:15](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementValueParser.java:15)–16의 시각 정규식은 임의의 비숫자 구분자 뒤 숫자를 시·분으로 받아들인다.
[LhAnnouncementEnrichmentMapper.java:167](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentMapper.java:167)–172는 기간에서 날짜 두 개를 얻지 못하면 보강을 거절한다.

실제 컴파일된 파서 호출 결과:

```text
dateTimes("2026.09.14 ~ 2026.09.15")
  => [2026-09-14T20:26]
dateTimes("2026.09.14 10:00 ~ 2026.09.15 16:00")
  => [2026-09-14T10:00, 2026-09-15T16:00]
```

**영향:** 날짜만 표현한 기간을 처리하지 못하며, `dateTime()` 같은 단일값 경로에서는 잘못된 시각을 만들 여지가 있다.
현재 기간 매퍼는 두 값이 없어 실패하므로 위 입력이 그대로 정상 제품 일정에 저장됐다고 주장하지 않는다.

**권고:** 기간을 분리한 뒤 명시적인 날짜/날짜시각 형식으로 각각 파싱한다.
날짜 전용 값의 자정 저장과 사용자에게 보여줄 확정 시각은 다른 문제이며 아래 일정 정책과 함께 다룬다.

**회귀 검증:** 날짜만 있는 기간, 시각 포함 기간, 날짜·시각 혼합, 잘못된 날짜, 다중 기간.
미지의 문자열을 부분 매칭해 임의의 날짜를 만드는 동작도 방어해야 한다.

### M-05 · 공백 제거와 미제공 마커가 서로 맞지 않는다

**코드 근거:** [LhAnnouncementValueParser.java:114](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementValueParser.java:114),124는 공백을 제거하지만 `:133`은 공백이 있는 `별도 안내`를 비교한다.

실제 호출에서 `unavailable("별도 안내")`는 false,
`yearMonth("별도 안내", "입주예정월")`는 `LhAnnouncementEnrichmentRejectedException`이었다.
일정·입주월·금액에 이 마커가 있으면 미제공값 보존 대신 공고 전체 보강을 보류할 수 있다.

**권고:** 비교 대상도 동일하게 정규화한다. 큰 파서 재작성과 분리 가능한 작은 결함이다.
`별도 안내`, `별도안내`, 탭·개행이 있는 값과 기존 `공고문 참조`·`미정`을 검증한다.
실제 API에서 해당 마커가 출현하는 빈도는 이번에 측정하지 않았다.

## 실행 관리에서 처리할 문제

### O-01 · DB 잠금 소실을 기존 작업이 인지하지 못한다

**코드 근거:** [PostgresAdvisoryLock.java:28](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/global/persistence/PostgresAdvisoryLock.java:28)–36,128–148은 별도 세션에 잠금을 얻고 종료 때만 다시 사용한다.
[DataPipelineExecutionLock.java:47](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/repository/DataPipelineExecutionLock.java:47)–50은 로컬 boolean이 true이면 DB 확인 없이 true를 반환한다.
[DataPipelineExecutionService.java:312](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java:312)–322의 heartbeat는 다른 repository 연결로 실행 행만 갱신한다.

이번에 격리 PostgreSQL에서 실제 `DataPipelineExecutionLock` 두 인스턴스로 실행했다.
A의 잠금 연결을 probe 자신의 application_name으로 특정해 그 세션 하나만 종료한 뒤 B가 같은 잠금을 얻었다.

```text
own terminated lock sessions=1
B acquired=true, A isHeld=true, B isHeld=true
```

**조건과 영향:** 다중 앱 인스턴스에서 A의 잠금 전용 세션만 소실되고 업무용 DB 연결은 살아 있거나 재연결되면,
B가 시작한 작업과 A의 후속 수집·정제가 겹칠 수 있다. 정상 잠금 경쟁 테스트로는 이 장애를 잡지 못한다.
위 실행은 잠금 계층의 소유권 오판을 확인한 것이며 두 파이프라인이 실제 제품 행을 동시에 덮는 테스트까지 수행한 것은 아니다.

**권고:** lease 소유 연결의 소실을 감지해 후속 단계·배치·외부 요청을 중단하고 실패로 기록한다.
heartbeat 검사만으로는 검사 사이의 동시 쓰기까지 막을 수 없다. 무중복 저장이 필수라면 실제 쓰기 트랜잭션에서도
현재 실행의 소유권을 확인하는 방식을 함께 설계한다. 토큰/버전 컬럼을 선택한다면 별도 스키마 변경 검토가 필요하다.
현재 전역 잠금을 더 잘게 쪼개는 작업보다 이 경계를 먼저 다룬다.

**회귀 검증:** 실제 PostgreSQL에서 A 잠금 세션 종료 → B 재획득 → A의 후속 저장 차단 및 실패 상태,
정상 장기 실행, 중단 중 worker 정리와 잠금 반납을 검증한다.

### O-02 · 과거 RUNNING 복구가 조회 여부에 의존한다

**코드 근거:** [DataPipelineExecutionService.java:143](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java:143)–156의 `findLatest`·`find(id)`만 중단 복구를 호출한다.
새 실행 시작은 기존 RUNNING을 정리하지 않는다. [DataPipelineScheduleOrchestrator.java:100](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineScheduleOrchestrator.java:100) 이후 흐름은 현재 슬롯과
완료된 미연결 수집을 조회하므로 종료된 과거 RUNNING을 전부 복구하지 않는다.

**재현:** 이전 슬롯 수집/정제 도중 프로세스 종료 → 다음 슬롯에서 재기동 → 새 실행 생성 → 이전 ID 직접 조회 없음.
과거 실행은 RUNNING으로 남고 최신 조회에서는 새 실행에 가려진다.
복구가 호출되더라도 [DataPipelineExecutionService.java:329](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java:329)–334가 실패 단계를 null로 넘겨 마지막 단계 정보가 사라진다.

**권고:** 전역 잠금을 획득한 새 실행 시작 시 또는 같은 잠금 아래 주기적으로 오래된 RUNNING을 복구한다.
상태와 heartbeat를 갱신 직전에 다시 검사하고 마지막 단계는 보존한다.

**회귀 검증:** 과거 수동 실행·이전 슬롯·중단된 정제가 새 실행에 가려져도 복구됨,
실제 활성 실행 보존, 마지막 단계 보존. 기존 테스트는 오래된 최신 실행을 직접 조회하는 경우 중심이다.

## 기존 리뷰에서 계속 남는 제품 의미 문제

[LH 정확성 검증 문서](lh-announcement-correctness-audit.md)의 2026-09-25 공고문 대조는 아직 해결되지 않았다.
이번에는 외부 공고문·API를 다시 조회하지 않았으며 그 당시 표본의 증거로만 인용한다.

- `ACP_DTTM` 우선 사용은 남원노암의 조건부 후순위 날짜를 빠뜨린다. 단지·순위별 일정을 모두 `접수`로 저장해 구분도 잃는다.
- 두 표본의 API 종료 16:10과 공고문 16:00이 달랐다. 더 정밀한 필드라는 이유로 우선할 수 없다.
- 원공고/정정 PAN_ID 관계와 첨부 정정이 카드·검색의 기본 날짜와 함께 반영돼야 한다.
- 금액은 당시 402행 중 348행이 `공고문 참조`였다. 호출 실패 0건은 제품 정보 충족률 100%가 아니다.

**책임 분리:** 수집은 원천 필드·출처·조회 조건·시각을 잃지 않고 보존한다.
정제는 날짜의 충돌·단지/순위·조건부 일정을 인식하고 확인할 수 없는 값을 확정하지 않는다.
어떤 날짜를 카드에 표시하고 어떤 정정 공고를 대표로 삼을지는 announcement 제품 정책이다.
이를 `ACP_DTTM` 대신 구조화된 날짜를 항상 우선하는 한 줄 수정으로 끝내면 다른 단지별 일정이 틀릴 수 있다.

## 성능: 확인한 비용과 다음 측정

아래는 호출 구조에서 확인한 비용이다. 운영 병목이나 예상 개선 시간을 새로 실측한 결과가 아니다.

| 위치·근거 | 현재 비용 | 가장 작은 개선·검증 |
|---|---|---|
| [MyHomeAnnouncementSupplyMatcher.java:29](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeAnnouncementSupplyMatcher.java:29),66 | 성공 매칭 R행이면 단지·주택형 조회가 대략 2R회. 같은 단지도 반복 | 공고 단위 lookup 재사용. 동일 단지 다행 fixture에서 SQL 수 상한 검증 |
| [LhAnnouncementEnrichmentWriter.java:97](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentWriter.java:97),132,349,358 | 일정·첨부를 각각 읽고 stale 계산 때 다시 조회 | 이미 읽은 목록 재사용. 공고당 중복 조회 2회 제거와 수동 데이터 보존 검증 |
| [MyHomeComplexMappingPreparer.java:55](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/mapping/service/MyHomeComplexMappingPreparer.java:55)–58, 공고 mapping/enrichment의 `findAll` | 전체 원천·후보가 메모리에 존재. mapNext 크기만으로 전체 메모리 제한 불가 | 최대 원천 행 수·heap부터 측정. 식별자 묶음 단위 순회와 변경 대상 처리 |
| [LhHousingTypeHouseholdEnrichmentService.java:80](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhHousingTypeHouseholdEnrichmentService.java:80)–88 | 전체 카탈로그·단지·결과와 쓰기가 하나의 트랜잭션 | 중복 단지 매칭 검출을 먼저 완료한 뒤 단지별 쓰기 검토. 전체 rollback 계약 변화를 함께 확인 |
| `LhSourceStore.replaceDetails/replaceSupplies` | 내용이 같아도 만료 후 전체 DELETE·INSERT, IDENTITY 엔티티 | 전체 SQL·저장 p95·connection 대기 측정. 동일 내용 저장 생략 시 collectedAt/checkpoint 최신성은 별도 갱신 |
| `LhAnnouncementExternalCollectionService` | 과거 MyHome 원천도 500행씩 읽은 뒤 후보·TTL 제외 판정 | 누적 과거 행 대비 후보 계산/조회 비용 측정. active 필터만으로 최근 종료 대상을 누락시키지 않음 |
| `LhAnnouncementCollectionProgressManager.resolveFailures` | 성공별 버전·페이지별 과거 실패 조회 반복 | 원천 store뿐 아니라 checkpoint·link·failure까지 포함한 SQL 계측 |
| [DataPipelineExecution.java:71](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineExecution.java:71) 부근, scheduler | 보고서 컬렉션 3개가 EAGER. 존재 확인에도 전체 실행 조회 | `exists`와 필요한 필드 조회. 단계 수가 작아 현재 우선 병목으로 단정하지 않음 |
| 수집 버전별 requestHash | 구버전 원천·checkpoint 누적 보존 | 참조 여부·감사/복구 기간을 정한 뒤 정리. 무조건 삭제하지 않음 |

[기존 성능 문서](announcement-collection-performance.md)의 동일 166회 호출 `160.682초 → 41.116초`는
당시 동시성 비교 결과다. 이번 재측정이 아니며, 운영 트래픽과 동시에 발생하는 DB 대기를 포함하지 않는다.
동시성 8은 진행 중 요청 상한이지 초당 호출량 제한이 아니다. 더 높이기 전에 23/429·timeout·DB 대기를 함께 본다.

실행별로 최소한 다음을 분리해 계측하는 것이 좋다: 외부 요청/재시도 시간, 원천 저장과 후처리 SQL,
읽은 원천/변경 원천/복구 대상/성공 매칭/보류 건수, 정제 시간과 최대 메모리.
변경 대상 정제는 원천이 같은 실패 복구 대상도 다시 처리해야 하므로 단순 hash 같음=건너뜀으로 구현하지 않는다.

## 복잡성과 리팩터링

| 사안 | 권고 경계 | 적용 시점·주의 |
|---|---|---|
| 페이지 문자열이 실패 해결 범위까지 소유 | 수집 scope와 페이지 요청을 작은 값으로 구분 | C-03 수정과 함께. 범용 수집 프레임워크는 불필요 |
| mapping/enrichment가 공고 그룹·현재값 선택을 중복 | ingest 소속 원천 선택 정책 공유 | M-02 정책을 확정한 뒤. 과거 행 보존과 최신 공통값 선택을 분리 |
| LH 실행기에 후보·TTL·dedup·same-PAN 순서·worker·집계 집중 | 실행 스케줄링 부분만 추출 검토 | 수집 변경이 필요할 때. MyHome과 취소/저장 의미가 달라 무리하게 통합하지 않음 |
| 지역 정규화가 세대수 SourceMapper/Matcher에 중복 | ingest 소속 작은 정규화 정책 | 실제 두 사용처의 동작 보존 테스트와 함께 |
| HTTP interceptor가 Repository 잠금을 직접 참조 | 잠금 정책을 Service 경계로 이동 | 계층 규칙 위반 개선. 현재 HTTP 잠금 누락 결함은 아님 |
| 실행 ID가 MDC에서 실패 이력으로 전달 | 로그 맥락과 실행 맥락의 계약 명확화 | 병렬 실행 변경 시 ID 전달 테스트. 현재 누락을 확인한 것은 아님 |
| 상세 파서의 긴 Builder | dataset별 유효성·변환 책임 명확화 | 길이만 줄이기보다 C-01 검증을 먼저 고정 |

전역 잠금은 공고·단지 작업을 직렬화하지만 지금 분리하면 공유 원천/정제의 충돌을 다시 증명해야 한다.
현재는 유지하고 O-01의 장애 경계를 먼저 보완하는 편이 적절하다.

## 정책·관측성 후속과 제외한 의심

- **LH 공급 감소 보류:** 실제 철회·이름/면적 정정도 막을 수 있다. 원문 근거를 확인하는 정정 경로가 필요하지만,
  반복 부분 응답을 성공으로 승격하거나 기존 행을 자동 삭제하는 해법은 적절하지 않다.
- **주택형 정정 뒤 기존 LH 세대수:** [HousingType.java:156](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/housing/domain/HousingType.java:156), [LhHousingTypeHouseholdWriter.java:33](/Users/pjh/source/2026-toadzip/backend/src/main/java/com/toadzip/backend/ingest/enrichment/service/LhHousingTypeHouseholdWriter.java:33)은 이전 값을 보존한다.
  동일 주택형 정정인지 새 매칭 대상인지 불명확하므로 확정 오류로 분류하지 않았다. 보강 근거와 재검증 조건이 필요하다.
- **세대수 미매칭 상세:** 현재 주택형 누락은 집계 수만 남아 실패 API만으로 어느 면적을 복구할지 알기 어렵다.
  재처리를 위해 단지·주택형·원천 식별자별 보류 근거를 남기는 개선을 권고한다.
- **수집→정제 연결:** `upstreamExecutionId`는 실행 연관이며 원천 버전 고정이 아니다.
  두 실행 사이의 다른 수동 수집 때문에 정제가 upstream 당시와 정확히 같은 원천을 읽는다는 보장은 없다.
  현재 계약 위반으로 단정하지 않고 감사·재현성 한계로 기록한다.
- **원천 저장과 checkpoint:** 별도 트랜잭션은 기존 문서가 명시한 계약이다. 저장 후 checkpoint 실패는 재실행 복구 대상으로 본다.
  하나의 트랜잭션이 아니라는 이유만으로 새 결함으로 보고하지 않았다.
- **shared DB:** 설정 빈은 있지만 main Java에서 설정 외 사용처가 발견되지 않았다. 현재 ingest/state/failure는 primary JPA 경계다.
  과거 이중 DB 원자성 문제를 현행 결함으로 올리지 않았다.
- **권한·중복 실행:** 관리자 권한·CSRF, 공통 POST 잠금, 슬롯/upstream 고유 제약을 확인했다. 새로운 누락은 발견하지 않았다.

## 권장 작업 순서와 완료 조건

1. **수집 보호와 작은 파서 결함:** C-01/C-02/M-04/M-05를 각각 재현 테스트로 고정하고 수정한다.
   이전 원천 보존과 정상 응답 호환성을 함께 통과해야 한다.
2. **잘못된 연결 방지:** M-01/M-03을 수정한다. 주소 변경의 캐시 무효화와 이름 불일치의 미확정 처리를 검증한다.
3. **현재 원천·실패 상태 선택:** M-02/C-03을 처리한다. 최신값 반영, 과거 공급행 보존, 다른 scope 실패 보존이 완료 조건이다.
4. **실행 소유권·중단 복구:** O-01/O-02를 처리한다. 다중 인스턴스 운영 또는 확장 예정이면 이 작업을 1번보다 먼저 진행한다.
5. **제품 일정·정정 공고:** 기존 공고문 대조 표본으로 표시 의미를 정하고 카드·상세·검색을 함께 검증한다.
   공개 계약·데이터 모델 변경이 필요하면 구현 전에 구체적인 변경안을 검토한다.
6. **성능·구조 개선:** 공고별 조회 재사용부터 SQL 수로 검증하고, 실제 병목 근거에 따라 전량 처리와 트랜잭션을 줄인다.

각 수정은 관련 Red → Green 뒤 ingest 묶음과 격리 PostgreSQL의 전체 `check`를 실행한다.
지금은 리뷰 완료이며 위 수정들은 미구현이다.

## 이번 검증과 한계

| 검증 | 결과 |
|---|---|
| 격리 DB | `toadzip-ingest-review-20260926`, primary 55442/shared 55443. 기존 55432/55433 컨테이너는 사용하지 않음 |
| `TEST_POSTGRES_PORT=55442 TEST_SHARED_POSTGRES_PORT=55443 ./gradlew --offline test --tests 'com.toadzip.backend.ingest.*' --rerun-tasks` | **645 tests, 90 suites, failures/errors/skipped 0**. XML 집계와 BUILD SUCCESSFUL 확인 |
| 실제 컴파일된 값 파서 probe | M-04/M-05 재현. 문자열을 독립 재구현하지 않고 현재 클래스 호출 |
| 실제 LH 응답 파서 probe | C-01/C-02의 내용 없는 원천 1행 수용 재현 |
| 실제 잠금 클래스 + 임시 PostgreSQL probe | O-01의 A 소유권 오판/B 재획득 재현. probe 자신의 잠금 세션 1개만 종료 |
| 계층·트랜잭션 | 수동 검토. interceptor→Repository 직접 의존 확인, 전체 pipeline이 단일 원자 트랜잭션은 아님 |
| 문서/하네스 | `sh tests/harness/validate-harness-test.sh`, `sh scripts/validate-harness.sh`, `git diff --check` 통과. 문서 링크·줄 번호 검사 통과 |

검증에 사용한 임시 DB 컨테이너 2개와 전용 네트워크는 정리했다.
진단 소스는 `/tmp/toadzip-ingest-review-20260926/`에만 두었고 제품 소스·테스트 파일은 수정하지 않았다.
이번 리뷰에서 전체 backend `check`, 실제 API 재수집, 운영 데이터 대조, 처리량/heap 재측정은 하지 않았다.
M-01/M-02/M-03/C-03/O-02는 정적 경로이며 새로운 종단 재현 테스트는 후속 수정의 첫 검증으로 남겼다.
과거 629/1,474 테스트 결과는 이전 시점의 증거이며 이번 실행 결과와 합치지 않는다.

## 관련 문서

- [LH 정확성 검증과 기존 공고문 대조](lh-announcement-correctness-audit.md)
- [수집 성능 실측](announcement-collection-performance.md)
- [원천 수명주기](announcement-source-lifecycle.md)
- [연결·복구와 원천 교체 정책](lh-announcement-link-resolution.md)
- [파이프라인 실행](data-pipeline-execution.md)

</details>
