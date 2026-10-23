# 저장소·운영 검증

Flyway 검사 도구, 배포 설정과 모니터링 구성의 회귀 테스트를 관리합니다.

## 검증 안내

| 검사 | 검증 대상·실행 위치 |
| --- | --- |
| [`ci/test_flyway_migrations.py`](ci/test_flyway_migrations.py) | 임시 Git 이력으로 Flyway 버전 중복, 낮은 버전 추가와 기존 SQL 변경 차단을 검증합니다. 백엔드 CI에서 실행합니다. |
| [`deployment/test_notification_environment.py`](deployment/test_notification_environment.py) | 알림 기능의 기본 비활성화, 빌드·런타임 환경값과 선택적 인증정보 마운트를 검증합니다. 프론트엔드 CI에서 실행합니다. |
| [`deployment/test_backend_health.py`](deployment/test_backend_health.py) | Compose의 healthcheck·시작 순서와 상태 응답 판정을 검증합니다. 별도로 실행하는 검사입니다. |
| [`monitoring/`](monitoring/) | 대시보드 조회식·요청 필드와 DB 호스트·PostgreSQL·로그 수집 구성을 검증합니다. 별도로 실행하며 일부 검사는 Docker가 필요합니다. |

## 실행과 결과 확인

자동 실행 항목과 명령의 원본은 [백엔드 CI](../.github/workflows/backend-ci.yml)와 [프론트엔드 CI](../.github/workflows/frontend-ci.yml)입니다.
모니터링 검증의 준비·실행·정리 방법은 [모니터링 운영 안내](../docs/operations/monitoring.md)를 따릅니다.
애플리케이션 테스트는 [백엔드 테스트](../backend/src/test/)와 [프론트엔드 소스](../frontend/src/)에 있으며,
실행 기준은 [기여 규칙](../CONTRIBUTING.md)과 [프론트엔드 품질 기준](../docs/development/frontend/testing.md)을 따릅니다.

아래 명령은 저장소 루트에서 실행합니다. 알림 배포 설정은 `python3 tests/deployment/test_notification_environment.py`로 검증합니다.
개인 `.env` 없이 공개 fixture만 사용해 기본 비활성화, 빌드/런타임 전달, 선택적 ADC 읽기 전용 마운트를 확인합니다.

Flyway CI 검사는 `python3 -B tests/ci/test_flyway_migrations.py`로 검증합니다.
DB·원격 저장소에 연결하지 않으며, `.local/`에 만든 테스트용 Git 저장소만 사용하고 종료 시 정리합니다.
