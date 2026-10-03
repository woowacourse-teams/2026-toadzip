# Ingest 전체 흐름 재검토 (2026-09-30)

## 결론

원천 수집과 제품 정제의 저장 경계를 유지하면서 **단지 수집·정제 / 공고 수집·정제** 통합 실행을 추가했다. 수집이 부분 실패하거나 호출 제한을 만나면 자동 정제는 시작하지 않는다. 관리자는 결과를 확인한 뒤 **저장된 원천으로 정제 실행**을 선택할 수 있다. 기존 네 독립 실행과 이력을 보존한다. 예상된 부분 실패는 결과로 처리하고, 중복된 로컬 잠금은 제거했다.

이 문서는 기준 커밋 `2b957df`와 이후 리팩터링 판단이다. 현재 실행·배포 계약은 [파이프라인 실행](data-pipeline-execution.md), 공급 정정과 금액 보호 정책은 [LH 품질 운영](lh-announcement-quality-operations.md)을 따른다. 사용자 승인으로 [실제 API·격리 DB 검증](ingest-live-verification.md)을 수행했고 운영 DB는 조회하지 않았다. 2026-09-25 실응답·성능 수치와 2026-09-26 오류 목록은 당시 기록이며 현재 운영 품질을 증명하지 않는다. [성능 실측](announcement-collection-performance.md)과 [LH 정확성 검토](lh-announcement-correctness-audit.md)를 함께 판단 근거로 사용한다.

## 현재 데이터 경로

| 실행 단위 | 실제 단계 | 저장 결과 |
|---|---|---|
| 단지 수집 | 마이홈 지역별 원천 → LH 임대 카탈로그 | 단지·주택형의 원천 |
| 단지 정제 | 마이홈 단지 매핑 → LH 세대수 보강 | 제품 단지·주택형 |
| 공고 수집 | 마이홈 공급유형별 원천 → LH 목록 → LH 공급 → LH 상세 | 공고 원천과 LH 조회별 원천·연결 |
| 공고 정제 | 마이홈 공고 매핑 → LH 공고 보강 | 제품 공고·공급행·일정·첨부 |

순서는 [DataPipelineType](../src/main/java/com/toadzip/backend/ingest/pipeline/domain/DataPipelineType.java)과 [DataPipelineRunner](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineRunner.java)에 있다. 네 실행은 독립적으로 시작할 수 있고, 두 통합 유형은 각 수집 뒤 정제를 같은 실행 ID와 잠금으로 연결한다. 마이홈 공고 원천은 성공한 공급유형마다 저장하고, **전체 공급유형이 성공했을 때만** 미조회 원천을 판정한다([MyHomeAnnouncementCollectionService](../src/main/java/com/toadzip/backend/ingest/collection/service/MyHomeAnnouncementCollectionService.java), [MyHomeSourceStore](../src/main/java/com/toadzip/backend/ingest/collection/repository/MyHomeSourceStore.java)). 위치정보 ZIP 업로드는 단지 정제 전의 별도 관리자 작업이다.

## 남은 문제와 판단

### 1. `COMPLETED`는 원천의 최신성을 보증하지 않는다

LH 공급·상세의 일반 수집은 마이홈 원천 중 선택된 후보에 마감일 정책과 성공 체크포인트의 TTL을 적용해 실제 호출을 생략한다([후보 선택](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementExternalCollectionService.java), [갱신 정책](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementRefreshPolicy.java)). 종료 후 30일을 넘긴 공고는 일반 수집에서 제외된다. 현재 파이프라인 시작은 수동이며, 개별 공고 강제 갱신 API가 별도로 있다.

단계 결과에는 `skippedRequestCount`가 있지만 [DataPipelineStepResultAdapter](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineStepResultAdapter.java)는 실패와 행별 누락만 상태로 환산한다. 따라서 `COMPLETED`는 **이 실행에서 선택된 작업을 끝냈다**는 뜻이며, 모든 공고를 새로 조회했거나 제품 데이터가 충분하다는 뜻이 아니다. 화면은 저장·실제 호출·건너뜀·실패를 함께 보여주고, 이 의미를 명시해야 한다. TTL에 따른 정상 생략을 실패 상태로 바꾸지는 않는다.

### 2. 부분 실패 뒤의 계속 실행은 의도된 동작이지만 입력 시점이 섞일 수 있다

마이홈 수집에 일부 실패가 있어도 Runner는 LH 단계까지 실행한다. [회귀 테스트](../src/test/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineRunnerTest.java)는 이 동작을 고정한다. 후속 LH 수집은 저장된 마이홈 원천을 읽으므로, 같은 실행에서 새로 저장된 행과 이전 실행의 행을 함께 대상으로 삼을 수 있다. 파이프라인은 최종 `FAILED`를 남기지만, 이미 성공한 원천 저장은 되돌리지 않는다.

성공한 공급유형의 새 원천도 저장되므로 LH를 일괄 중단하면 해당 범위의 갱신까지 놓친다. 수집 구간의 계속 실행과 실패 보고를 유지한다. 통합 실행은 수집 부분 실패·호출 제한 뒤 자동 정제를 차단한다. TTL에 따른 정상 생략은 정제로 이어진다. 이전 원천까지 사용할지는 관리자가 독립 정제 버튼으로 선택한다. 정제는 현재 저장 원천 전체를 대상으로 하며 실패 행만 재시도하는 기능은 아니다.

### 3. LH 목록은 유지한다

LH 목록은 유일하게 연결된 후보의 조회 코드를 보완하고 목록 변경 시 TTL 전에 재조회하며, 목록과 연결된 변경 없는 공고에는 24시간 TTL을 적용한다([CandidateResolver](../src/main/java/com/toadzip/backend/ingest/collection/service/LhAnnouncementCollectionCandidateResolver.java)). 목록에만 있는 공고를 제품에 등록하는 기능은 없다.

[2026-09-25 격리 DB·실제 API 측정](announcement-collection-performance.md)에서는 83개 요청 중 72개가 목록과 연결됐다. 시계만 6시간 1초 진행한 재수집에서 LH 공급·상세 호출은 166회에서 22회로 줄었다. 최초 수집과 변경 없는 재수집의 비교이며 현재 운영 성능이나 조회 코드 보정의 성공률을 증명하지 않는다. 초기 보고서가 이 근거를 빠뜨린 점을 수정한다.

목록 제거는 조회 보정·재조회 정책을 바꾸므로 적용하지 않는다. 누락 `AIS_TP_CD` 보완, 보정 요청의 외부 호출·체크포인트·연결 일치, 6시간/24시간 경계·목록 변경 즉시 재조회·실패 시 원천 보존을 회귀 검증한다. 현재 snapshot은 연결과 코드 차이를 보여주지만, 덮어쓴 체크포인트만으로 과거 보정 효과나 조기 재조회 횟수를 복원할 수 없다.

### 4. 정상 HTTP 진입점에서 실행 잠금이 겹친다

관리자의 직접 ingest POST는 [IngestExecutionLockInterceptor](../src/main/java/com/toadzip/backend/ingest/pipeline/controller/IngestExecutionLockInterceptor.java)가 전역 잠금을 획득한다. 파이프라인 시작은 interceptor를 우회하고 [DataPipelineExecutionService](../src/main/java/com/toadzip/backend/ingest/pipeline/service/DataPipelineExecutionService.java)가 같은 잠금을 사용한다. 그 안에서 여러 서비스가 [IngestOperationLock](../src/main/java/com/toadzip/backend/ingest/pipeline/repository/IngestOperationLock.java)의 작업별 DB 잠금을 다시 획득한다. 중복된 프로세스 로컬 잠금은 제거했다.

현재 확인된 HTTP 경로에서는 보호 범위가 겹친다. 하지만 작업별 DB 키는 서비스 직접 호출과 이전 버전과의 충돌도 보호한다. 전체 삭제는 모든 진입점과 혼합 버전 배포를 확인한 뒤 별도로 판단한다. **전역 잠금, 세대 검증, 쓰기 트랜잭션의 소유권 검증은 유지**한다. 로컬 잠금 삭제만으로 처리량 개선을 약속하지 않는다.

## 이전 보고서의 미해결 목록 재분류

| 당시 ID | 현재 코드에서 확인한 조치 | 남은 확인 |
|---|---|---|
| C-01/02 | 빈 LH 상세·임대 카탈로그가 기존 원천을 지우지 않도록 저장 경계에서 거절 | 운영 API의 다른 부분 응답 형태 |
| C-03 | 성공한 수집 범위의 과거 페이지 실패 해소 경로 추가 | 운영 실패 이력 대조 |
| M-01 | 최신 주소·단지명으로 연결 검증 변경 | 운영 연결 충족률 |
| M-02 | 공고 공통값과 공급행이 현재 마이홈 원천을 선택하도록 변경 | 활성/비활성 혼합 운영 표본 |
| M-03 | 새 LH 입주월은 동일 단지명으로 유일하게 연결될 때만 사용 | 실제 단지명 불일치·누락, 과거 추정값 잔존 여부 |
| M-04/05 | 기간 날짜 파싱과 `별도 안내` 정규화 변경, 회귀 테스트 추가 | 미관측 응답 표현 |
| O-01 | 실행 소유권 세대와 쓰기 트랜잭션 검증 추가 | 다중 인스턴스 운영 관측 |
| O-02 | 조회뿐 아니라 새 실행 시작 시에도 오래된 `RUNNING` 복구 | 아무 조회·새 실행도 없을 때는 상태가 그대로 남음 |

이 표는 운영 DB의 전체 오류를 다시 감사한 결과가 아니다. 실제 API·격리 DB에서는 빈 부가정보·접수처와 제주 반복 행 처리, 수집 실패 해소를 추가 확인했다. 이전 보고서의 두 공식 공고문 대조는 여전히 유효한 문제 제기다. [LH 일정 매퍼](../src/main/java/com/toadzip/backend/ingest/enrichment/service/LhAnnouncementEnrichmentMapper.java)는 `ACP_DTTM`을 우선한다. 확인된 일정의 수동 등록 경로가 생겼지만 다른 공고의 조건부 접수·정정 관계를 자동으로 해결하지는 않는다.

## 보존할 경계와 다음 판단 자료

- 불완전한 외부 응답으로 기존 원천을 교체하지 않는 검증, 조회 조건별 LH 원천·체크포인트·연결을 유지한다.
- 단지별·공고별 쓰기 트랜잭션과 관리자 수정값 보호, 잠금 소실 뒤 이전 실행의 쓰기 차단을 유지한다.
- 수집과 정제의 독립 재실행을 유지한다. 정제 차단 결과에는 원천 재사용 안내와 정제 실행 버튼을 제공한다. 단지 좌표가 없으면 수집 → 위치정보 ZIP 업로드 → 정제 순서로 복구한다.
- 운영 품질·조회 코드 보정 기여도는 아직 미측정이다. 추가 감사는 원천·목록·체크포인트·연결을 격리 복제하고 현재 resolver/policy로 계산한다. 기존 실측을 현재 운영 성과로 해석하지 않는다.

현재 실행 유형·잠금·배포 조건은 [파이프라인 실행](data-pipeline-execution.md), 중지·실패 조회·수동 정제 복구는 [운영자 제어](pipeline-operator-controls.md)를 따른다.
