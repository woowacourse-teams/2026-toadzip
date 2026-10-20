# 공용 문서

사람과 AI가 함께 사용하는 기술 문서의 진입점이다. 문서는 구현 위치보다 독자가 하려는 작업을 기준으로 분류한다.

## 분류와 배치 기준

| 분류 | 답할 질문 | 정리할 위치 |
| --- | --- | --- |
| 환경 설정 | 설치·환경변수 준비와 최초 실행을 어떻게 하는가? | `setup/` |
| 개발 기준 | 어떤 구조·규칙으로 구현하고 검증하는가? | `development/` |
| 기능 명세 | 현재 기능이 어떤 정책·제약으로 동작하는가? | `features/` |
| 운영 | 어떻게 배포·관측·유지하고 장애에서 복구하는가? | `operations/` |

현재 문서는 아래 기존 위치에서 읽는다. 네 분류로 단계적으로 통합하며, 빈 폴더나 연결용 복사본은 만들지 않는다.

- 같은 규칙·정책은 한 곳만 원본으로 두고 다른 문서에서는 링크한다.
- 작업 계획·진행 기록은 이슈 또는 개인 자료로, 당시 테스트 통과 결과는 PR 검증 기록으로 남긴다. 재현 가능한 검증 방법·복구 조건·알려진 한계는 기술 문서에 보존한다.
- 사람용·AI용 기술 문서를 복제하지 않는다. AGENTS는 적용 범위의 작업 지침과 관련 문서 링크를 제공한다.
- 루트 소개·기여 규칙, 코드 컨벤션 원본, AGENTS·에이전트 역할 설정은 현재 위치를 유지한다.
- 앱이 읽는 고지 원문·SQL, 테스트 자료·예시 데이터, 도구 옆 사용 안내는 일반 문서 이동 대상이 아니다. 개인 자료의 보관 기준은 [기여 규칙](../CONTRIBUTING.md#로컬-작업-자료)을 따른다.

## 환경 설정

- [환경별 설정 시작점](SETUP.md)
- [로컬 실행](LOCAL_SETUP.md)
- [개발 서버](DEV_SERVER_SETUP.md) · [운영 서버](PROD_SERVER_SETUP.md)
- 프론트엔드 단독 실행은 [프론트엔드 README](../frontend/README.md)를 참고한다.

## 개발 기준

- [아키텍처](../backend/docs/architecture.md) · [계층 경계](../backend/docs/layer-boundaries.md)
- [백엔드 코드 컨벤션](../backend/CODE_CONVENTION.md) · [API](../backend/docs/api-conventions.md) · [오류 처리](../backend/docs/exception-handling.md)
- [DB·트랜잭션](../backend/docs/persistence.md) · [테스트](../backend/docs/testing.md) · [보안](../backend/docs/security.md)
- [프론트엔드 개발 기준](../frontend/docs/development-standards.md) · [검증 기준](../frontend/docs/quality-gates.md) · [디자인 시스템](../frontend/docs/design-system.md)
- 세부 백엔드 문서는 [백엔드 문서 목록](../backend/docs/README.md)에서 찾는다.

## 기능 명세

- 수집·정제: [전체 흐름](../backend/docs/ingest-maintenance.md) · [실행 상태·제어](../backend/docs/data-pipeline-execution.md)
- 관리자: [데이터 관리](../backend/docs/admin-data-management.md) · [비교·검토 계약](../backend/docs/admin-quality-workflow.md) · [화면 사용법](../frontend/docs/admin-quality-workflow.md) · [공고 JSON 등록](ADMIN_ANNOUNCEMENT_IMPORT.md)
- 검색·지도: [통합 검색](INTEGRATED_SEARCH.md) · [지역 검색](../backend/docs/location-search.md) · [거리뷰](../frontend/docs/street-view.md)
- 인증: [소셜 로그인](../backend/docs/user-social-login.md)
- 알림: [회원 설정](../backend/docs/notification-settings.md) · [알림 생성·전달](../backend/docs/notification-delivery.md) · [설정 보유·해제](notification-retention-and-cancellation.md)
- 개인정보: [동의 계약](../backend/docs/privacy-consent.md) · [프론트 수집 제어](../frontend/docs/privacy-consent.md)
- 첨부파일: [다운로드·미리보기](../backend/docs/announcement-attachments.md)

## 운영

- [모니터링·로그·알림](MONITORING_SERVER_SETUP.md) · [제품 분석](../frontend/docs/posthog-analytics.md)
- [DB 서버·메트릭](../infra/db/SETUP.md) · [Flyway 최초 적용](../backend/docs/flyway-adoption.md)
- [HTTPS 인증서 발급·갱신](../infra/certbot/README.md)
- [개인정보 배포·복구](../backend/docs/privacy-deployment.md) · [처리 정책·운영 책임](privacy-policy.md)
- [알림 데이터·스키마 점검](NOTIFICATION_DATA.md)
- CI/CD의 현재 실행 조건·배포 명령은 [GitHub 워크플로 안내](../.github/README.md)와 해당 설정 파일을 원본으로 확인한다.

## 예시·점검 자료

[fixtures/](fixtures/)에는 공고 JSON 예시, [queries/](queries/)에는 알림 대상·집계·보존 상태·스키마 점검 SQL을 둔다.
