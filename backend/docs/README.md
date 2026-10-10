# 백엔드 문서

개발 기준, 기능별 API, 수집 운영과 DB 배포 안내를 필요한 작업별로 찾는다.
서비스 목적은 [서비스 소개](../../SERVICE_OVERVIEW.md), 실행 환경은 [환경 설정](../../docs/SETUP.md)을 먼저 확인한다.

## 개발 기준

| 작업 | 문서 |
|---|---|
| Java 코드 | [코드 컨벤션](../CODE_CONVENTION.md) |
| 패키지·의존성·저장 | [아키텍처](architecture.md) |
| HTTP·인증·오류 | [API 규칙](exception-handling.md) |
| 로그·메트릭 | [로그](logging-convention.md) |
| 테스트·완료 확인 | [검증](quality-gates.md) |
| 에이전트 역할 | [작업 지침](../AGENTS.md) |

Git 작업은 [기여 규칙](../../CONTRIBUTING.md)을 따른다.

## 기능별 안내

| 기능 | 문서 |
|---|---|
| 지역·검색 | [지역 카탈로그](region-search.md), [통합 검색](../../docs/INTEGRATED_SEARCH.md) |
| 공고 | [첨부파일](announcement-attachments.md), [일정 대상](announcement-schedule-targets.md), [조회수](announcement-views.md) |
| 거리뷰 | [거리뷰](street-view.md) |
| 사용자 | [소셜 로그인](user-social-login.md), [회원 알림 설정](notification-settings.md), [의견 접수](user-feedback.md) |
| 관리자 | [데이터 수정·삭제](admin-data-management.md), [공고 JSON 가져오기](../../docs/ADMIN_ANNOUNCEMENT_IMPORT.md) |

## 수집과 정제

### 코드 읽기

[수집·정제 코드 안내](ingest-maintenance.md)에서 실행 관리와 단지·공고 흐름을 확인한다.

### 실행과 복구

| 작업 | 문서 |
|---|---|
| 실행 유형·상태 | [파이프라인 실행](data-pipeline-execution.md) |
| 진행·중지·실패 확인 | [관리자 실행 제어](pipeline-operator-controls.md) |
| 수집 인증키 지정 | [일회성 서비스키](pipeline-service-key.md) |
| 원천 조회·활성 상태 | [관리자 원천 조회](ingest-source-browsing.md), [공고 원천 관리](announcement-source-lifecycle.md) |
| 공고 수집 설정 | [동시성·타임아웃·성능 확인](announcement-collection-performance.md) |
| LH 연결과 데이터 품질 | [연결·복구](lh-announcement-link-resolution.md), [품질 운영](lh-announcement-quality-operations.md) |
| 단지 연결·통합 | [마이홈 단지 통합](myhome-complex-linking.md) |
| 단지 좌표 | [도로명주소 좌표 적재](road-address-reference-data.md) |

## DB 배포

기존 DB에 Flyway를 처음 적용할 때는 [Flyway 도입](flyway-adoption.md)을 따른다.
수집 원천 저장 구조를 전환할 때는 [기존 수집 DB 전환](ingest-branch-db-upgrade.md)을 따른다.
각 기능의 추가 스키마와 롤백 조건은 해당 기능 문서에서 확인한다.
